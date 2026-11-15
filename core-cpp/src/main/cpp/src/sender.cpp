#include "sender.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>

#include "net.h"
#include "protocol.h"
#include "share64/sha256.h"
#include "sync.h"
#include "util.h"

namespace s64 {
namespace {

constexpr size_t kPiece = 256 * 1024;  // wire write granularity (smooth progress, prompt abort)

Err send_chunk(int fd, uint64_t idx, const Slot& s, SendJob& job, const std::atomic<bool>& ab) {
  uint8_t hdr[proto::kHeaderSize];
  proto::put_header(hdr, proto::Msg::Chunk, s.len + static_cast<uint32_t>(kHashLen),
                    static_cast<uint32_t>(idx));
  Err e = net::send_all(fd, hdr, sizeof hdr, ab, true);
  if (e != Err::Ok) return e;
  const uint8_t* p = s.buf;
  size_t left = s.len;
  while (left) {
    const size_t n = std::min(left, kPiece);
    e = net::send_all(fd, p, n, ab, n == left);  // MSG_MORE on the last piece: hash rides along
    if (e != Err::Ok) return e;
    job.done_bytes.fetch_add(n, std::memory_order_relaxed);
    p += n;
    left -= n;
  }
  return net::send_all(fd, s.hash, kHashLen, ab);
}

}  // namespace

Err run_send_attempt(SendJob& job) {
  const Config& cfg = job.cfg;
  SessionCtl& ctl = job.ctl;
  const std::atomic<bool>& ab = ctl.abort_flag();
  std::string fm;
  auto bail = [&](Err x) -> Err {
    ctl.fail(x, fm);
    return ctl.err();
  };

  Err e = Err::Ok;
  std::string host;
  uint16_t port = 0;
  {
    std::lock_guard<std::mutex> g(job.addr_mu);
    host = job.host;
    port = job.port;
  }
  job.streaming.store(false);
  const int fd = net::connect_tcp(host, port, cfg.connect_timeout_ms, ab, e);
  if (fd < 0) return bail(e);
  net::tune(fd, cfg.io_timeout_ms, cfg.keepalive_idle_s, cfg.keepalive_interval_s, cfg.keepalive_count);
  ctl.bind(fd, nullptr);
  struct Guard {
    SessionCtl& c;
    int fd;
    ~Guard() { c.unbind(); ::close(fd); }
  } guard{ctl, fd};

  uint8_t cbuf[proto::kMaxControl];
  proto::Header h;

  proto::Hello hello;
  hello.device = cfg.device_name;
  if ((e = proto::send_hello(fd, proto::Msg::Hello, hello, ab)) != Err::Ok) return bail(e);
  if ((e = proto::read_expect(fd, proto::Msg::HelloAck, h, cbuf, sizeof cbuf, ab, &fm)) != Err::Ok)
    return bail(e);

  proto::Offer off;
  off.id = job.id;
  off.size = job.size;
  off.chunk = cfg.chunk_size;
  off.mtime_ms = job.mtime_ms;
  off.name = job.name;
  if ((e = proto::send_offer(fd, off, ab)) != Err::Ok) return bail(e);

  net::set_recv_timeout(fd, cfg.offer_timeout_ms + 5000);  // a human may be deciding
  e = proto::read_expect(fd, proto::Msg::Accept, h, cbuf, sizeof cbuf, ab, &fm);
  net::set_recv_timeout(fd, cfg.io_timeout_ms);
  if (e != Err::Ok) return bail(e);
  proto::Accept acc;
  if (!proto::decode_accept(cbuf, h.length, acc) || acc.id != job.id) return bail(Err::Protocol);

  const uint64_t chunk = cfg.chunk_size;
  const uint64_t total = (job.size + chunk - 1) / chunk;
  if (acc.resume > total) return bail(Err::Protocol);
  job.streaming.store(true);
  const uint64_t base = acc.resume;
  const uint64_t base_bytes = std::min<uint64_t>(job.size, base * chunk);
  job.done_bytes.store(base_bytes);
  job.verified_bytes.store(base_bytes);
  {
    Event ev = make_event(EventKind::Started, job.id, Direction::Send, job.name, job.size);
    ev.done = ev.verified = base_bytes;
    if (job.sink) job.sink->on_event(ev);
  }

  SlotPool pool;
  const uint32_t S = cfg.slots;
  if (total > base && !pool.init(S, chunk)) return bail(Err::Internal);
  ctl.bind(fd, &pool);

  Reporter rep(job.sink, cfg, job.id, Direction::Send, job.name, job.size, &job.done_bytes,
               &job.verified_bytes);
  rep.start();

  // ---- disk workers: pread + SHA-256 into pooled buffers (no allocation in this loop) ----
  std::atomic<uint64_t> next_chunk{base};
  std::vector<std::thread> workers;
  const uint64_t nw = std::min<uint64_t>(cfg.workers, total - base);
  for (uint64_t k = 0; k < nw; ++k) {
    workers.emplace_back([&] {
      for (;;) {
        const uint64_t i = next_chunk.fetch_add(1, std::memory_order_relaxed);
        if (i >= total) return;
        const uint64_t rel = i - base;
        Slot& s = pool.at(rel);
        if (!wait_ge(s.state, slot_target(rel, S, kFree), ab)) return;
        const uint64_t o = i * chunk;
        const uint32_t n = static_cast<uint32_t>(std::min<uint64_t>(chunk, job.size - o));
        if (!pread_full(job.file_fd, s.buf, n, o)) {
          ctl.fail(Err::Io, "read failed (file changed or unreadable)");
          return;
        }
        Sha256::hash(s.buf, n, s.hash);
        s.len = n;
        s.state.store(slot_target(rel, S, kFull), std::memory_order_release);
        futex_wake_all(&s.state);
      }
    });
  }

  // ---- ACK reader: frees slots in order, then waits for the receiver's DONE ----
  bool got_done = false;
  std::thread acker([&] {
    std::string afm;
    for (uint64_t i = base; i < total; ++i) {
      const uint64_t rel = i - base;
      if (!wait_ge(pool.seq, static_cast<uint32_t>(rel + 1), ab)) return;  // only read when data is in flight
      proto::Header ah;
      Err ae = proto::read_header(fd, ah, ab);
      if (ae != Err::Ok) { ctl.fail(ae, "ack read failed"); return; }
      if (ah.type == static_cast<uint8_t>(proto::Msg::Error)) {
        uint8_t eb[proto::kMaxControl];
        proto::Fault f;
        if (ah.length <= sizeof eb && net::recv_all(fd, eb, ah.length, ab) == Err::Ok)
          proto::decode_fault(eb, ah.length, f);
        ctl.fail(f.code, f.msg);
        return;
      }
      if (ah.type != static_cast<uint8_t>(proto::Msg::Ack) || ah.aux != static_cast<uint32_t>(i) ||
          ah.length != 0) {
        ctl.fail(Err::Protocol, "unexpected frame while waiting for ACK");
        return;
      }
      Slot& s = pool.at(rel);
      job.verified_bytes.store(std::min<uint64_t>(job.size, (i + 1) * chunk), std::memory_order_relaxed);
      s.state.store(4u * static_cast<uint32_t>(rel / S + 1), std::memory_order_release);  // FREE, next round
      futex_wake_all(&s.state);
    }
    net::set_recv_timeout(fd, cfg.done_timeout_ms);  // receiver may fsync + rename a large file
    uint8_t db[proto::kMaxControl];
    proto::Header dh;
    Err de = proto::read_expect(fd, proto::Msg::Done, dh, db, sizeof db, ab, &afm);
    if (de != Err::Ok) { ctl.fail(de, afm); return; }
    if (dh.length != kHashLen) { ctl.fail(Err::Protocol, "bad DONE"); return; }
    std::memcpy(job.root, db, kHashLen);
    got_done = true;
  });

  // ---- wire sender (this thread): strictly in-order chunk frames ----
  for (uint64_t i = base; i < total; ++i) {
    const uint64_t rel = i - base;
    Slot& s = pool.at(rel);
    if (!wait_ge(s.state, slot_target(rel, S, kFull), ab)) break;
    const Err se = send_chunk(fd, i, s, job, ab);
    if (se != Err::Ok) { ctl.fail(se, "send failed"); break; }
    s.state.store(slot_target(rel, S, kDone), std::memory_order_release);
    pool.seq.store(static_cast<uint32_t>(rel + 1), std::memory_order_release);
    futex_wake_all(&pool.seq);
  }

  for (auto& t : workers) t.join();
  acker.join();
  ctl.bind(fd, nullptr);  // pool is about to die
  rep.stop();

  if (ctl.aborted()) return ctl.err();
  return got_done ? Err::Ok : Err::Internal;
}

uint64_t Sender::send(const std::string& host, uint16_t port, int file_fd, const std::string& name,
                      const std::string& peer_key) {
  reap();
  auto fail_now = [&](Err e, const char* msg) -> uint64_t {
    if (file_fd >= 0) ::close(file_fd);
    Event ev = make_event(EventKind::Failed, 0, Direction::Send, name, 0);
    ev.err = e;
    ev.message = msg;
    emit(ev);
    return 0;
  };
  struct stat st;
  if (file_fd < 0 || fstat(file_fd, &st) != 0 || !S_ISREG(st.st_mode)) {
    return fail_now(Err::NotFound, "not a readable regular file");
  }
  if (name.empty()) return fail_now(Err::BadName, "empty file name");

  auto job = std::make_unique<SendJob>();
  job->host = host;
  job->port = port;
  job->name = name;
  job->peer_key = peer_key.empty() ? host : peer_key;
  job->file_fd = file_fd;
  job->size = static_cast<uint64_t>(st.st_size);
  job->mtime_ms = static_cast<int64_t>(st.st_mtim.tv_sec) * 1000 + st.st_mtim.tv_nsec / 1000000;
  job->cfg = cfg_;
  job->sink = sink_;

  // Deterministic id: the same file to the same peer resumes even after an app restart.
  std::string key = name;
  key.push_back('\0');
  key += std::to_string(job->size);
  key.push_back('\0');
  key += std::to_string(job->mtime_ms);
  key.push_back('\0');
  key += peer_key.empty() ? host : peer_key;
  uint8_t digest[kHashLen];
  Sha256::hash(key.data(), key.size(), digest);
  job->id = get_le64(digest);
  if (job->id == 0) job->id = 1;

  if (job->size > static_cast<uint64_t>(cfg_.chunk_size) * kMaxChunks) {
    return fail_now(Err::Protocol, "file too large for chunk size");
  }

  std::lock_guard<std::mutex> g(mu_);
  for (auto& j : jobs_) {
    if (j->id == job->id && !j->finished.load()) {  // already running: idempotent
      ::close(file_fd);
      return job->id;
    }
  }
  SendJob* raw = job.get();
  const uint64_t id = raw->id;
  jobs_.push_back(std::move(job));
  raw->thread = std::thread([this, raw] {
    run_job(*raw);
    raw->finished.store(true, std::memory_order_release);
  });
  return id;
}

void Sender::run_job(SendJob& j) {
  const Config& cfg = j.cfg;
  int attempt = 0;
  int stalled = 0;
  int delay = cfg.retry_base_ms;
  uint64_t last_verified = 0;
  Event ev;
  for (;;) {
    if (j.ctl.cancel_requested()) {
      ev = make_event(EventKind::Cancelled, j.id, Direction::Send, j.name, j.size);
      ev.err = Err::Cancelled;
      emit(ev);
      break;
    }
    j.ctl.reset();
    ++attempt;
    // A network event or an address update that arrives during this attempt (or its back-off)
    // must cut the back-off short, even if it lands before the back-off loop starts.
    const uint32_t epoch_at_start = epoch_.load();
    const uint32_t kick_at_start = j.kick.load();
    const Err e = run_send_attempt(j);
    if (e == Err::Ok) {
      ev = make_event(EventKind::Completed, j.id, Direction::Send, j.name, j.size);
      ev.done = ev.verified = j.size;
      ev.message = to_hex(j.root, kHashLen);
      emit(ev);
      break;
    }
    if (e == Err::Cancelled && j.ctl.cancel_requested()) continue;  // handled at loop top
    const std::string msg = j.ctl.message();
    if (!is_retryable(e)) {
      ev = make_event(e == Err::Cancelled ? EventKind::Cancelled : EventKind::Failed, j.id,
                      Direction::Send, j.name, j.size);
      ev.err = e;
      ev.message = msg;
      ev.verified = j.verified_bytes.load();
      emit(ev);
      break;
    }
    j.streaming.store(false);
    const uint64_t v = j.verified_bytes.load();
    if (v > last_verified) {  // made progress: restart the give-up counter and backoff
      last_verified = v;
      stalled = 0;
      delay = cfg.retry_base_ms;
    }
    if (!net_up_.load(std::memory_order_acquire)) {
      // The OS says the network is down. Waiting is free: it does not use up max_retries.
      // set_network(true, ...) wakes us; the partial data on the receiver stays valid.
      ev = make_event(EventKind::Retrying, j.id, Direction::Send, j.name, j.size);
      ev.err = Err::Io;
      ev.attempt = attempt;
      ev.done = ev.verified = v;
      ev.message = "network lost";
      emit(ev);
      const int64_t t0 = now_ms();
      while (!j.ctl.cancel_requested() && !net_up_.load(std::memory_order_acquire) &&
             now_ms() - t0 < cfg.offline_patience_ms) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
      }
      if (j.ctl.cancel_requested()) continue;  // handled at loop top
      if (!net_up_.load(std::memory_order_acquire) && now_ms() - t0 >= cfg.offline_patience_ms) {
        ev = make_event(EventKind::Failed, j.id, Direction::Send, j.name, j.size);
        ev.err = Err::Timeout;
        ev.message = "network unavailable";
        ev.verified = v;
        emit(ev);
        break;
      }
      delay = cfg.retry_base_ms;
      stalled = 0;
      continue;  // reconnect right away
    }
    if (++stalled > cfg.max_retries) {
      ev = make_event(EventKind::Failed, j.id, Direction::Send, j.name, j.size);
      ev.err = e;
      ev.message = msg.empty() ? err_name(e) : msg;
      ev.verified = v;
      emit(ev);
      break;
    }
    ev = make_event(EventKind::Retrying, j.id, Direction::Send, j.name, j.size);
    ev.err = e;
    ev.attempt = attempt;
    ev.done = ev.verified = v;
    ev.message = msg;
    emit(ev);
    bool kicked = false;
    for (int waited = 0; waited < delay && !j.ctl.cancel_requested(); waited += 50) {
      if (epoch_.load() != epoch_at_start || j.kick.load() != kick_at_start) { kicked = true; break; }
      std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    delay = kicked ? cfg.retry_base_ms : std::min(delay * 2, cfg.retry_max_ms);
  }
  ::close(j.file_fd);
  j.file_fd = -1;
}

void Sender::set_network(bool up, bool drop_connections) {
  net_up_.store(up, std::memory_order_release);
  epoch_.fetch_add(1, std::memory_order_acq_rel);  // wakes every sleeping retry loop
  if (!drop_connections) return;
  std::lock_guard<std::mutex> g(mu_);
  for (auto& j : jobs_) {
    // A normal failure (not a user cancel): the retry loop reconnects and resumes.
    if (!j->finished.load()) j->ctl.fail(Err::Io, "network lost");
  }
}

size_t Sender::update_peer(const std::string& peer_key, const std::string& host, uint16_t port) {
  size_t n = 0;
  std::lock_guard<std::mutex> g(mu_);
  for (auto& j : jobs_) {
    if (j->finished.load() || j->peer_key != peer_key) continue;
    bool changed = false;
    {
      std::lock_guard<std::mutex> ag(j->addr_mu);
      changed = j->host != host || j->port != port;
      j->host = host;
      j->port = port;
    }
    ++n;
    if (!changed) continue;
    j->kick.fetch_add(1);
    // Still connecting to (or handshaking with) the old address: restart at the new one now.
    // A running stream is left alone; if its old connection is dead the keep-alive ends it.
    if (!j->streaming.load()) j->ctl.fail(Err::Io, "peer address changed");
  }
  return n;
}

void Sender::reap() {
  std::vector<std::unique_ptr<SendJob>> dead;
  {
    std::lock_guard<std::mutex> g(mu_);
    for (auto it = jobs_.begin(); it != jobs_.end();) {
      if ((*it)->finished.load(std::memory_order_acquire)) {
        dead.push_back(std::move(*it));
        it = jobs_.erase(it);
      } else {
        ++it;
      }
    }
  }
  for (auto& j : dead) if (j->thread.joinable()) j->thread.join();
}

void Sender::cancel(uint64_t id) {
  std::lock_guard<std::mutex> g(mu_);
  for (auto& j : jobs_) if (j->id == id) j->ctl.request_cancel();
}

void Sender::cancel_all() {
  std::vector<std::unique_ptr<SendJob>> all;
  {
    std::lock_guard<std::mutex> g(mu_);
    for (auto& j : jobs_) j->ctl.request_cancel();
    all.swap(jobs_);
  }
  for (auto& j : all) if (j->thread.joinable()) j->thread.join();
}

}  // namespace s64
