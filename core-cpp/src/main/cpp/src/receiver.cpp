#include "receiver.h"

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

#include <algorithm>
#include <condition_variable>
#include <cstring>
#include <ctime>

#include "net.h"
#include "protocol.h"
#include "share64/sha256.h"
#include "sync.h"
#include "util.h"

namespace s64 {
namespace {

constexpr size_t kPiece = 256 * 1024;
constexpr size_t kMetaHeader = 64;
constexpr uint32_t kMetaMagic = 0x4d343653u;  // "S64M"
constexpr int kCompletedWindowSec = 600;      // re-offer after a lost DONE is answered from the meta
constexpr int kStaleDays = 7;

// Strips paths and hostile characters. Returns false when nothing usable remains.
bool sanitize_name(const std::string& in, std::string& out) {
  const size_t cut = in.find_last_of("/\\");
  const std::string s = cut == std::string::npos ? in : in.substr(cut + 1);
  std::string t;
  for (unsigned char c : s) {
    if (c < 0x20 || c == 0x7f) continue;
    if (std::strchr(":*?\"<>|", c)) c = '_';
    t.push_back(static_cast<char>(c));
  }
  const size_t b = t.find_first_not_of(" .");
  if (b == std::string::npos) return false;
  const size_t e = t.find_last_not_of(" .");
  t = t.substr(b, e - b + 1);
  if (t.size() > 200) {
    const size_t dot = t.rfind('.');
    const bool has_ext = dot != std::string::npos && dot > 0 && t.size() - dot <= 16;
    const std::string ext = has_ext ? t.substr(dot) : std::string();
    std::string stem = has_ext ? t.substr(0, dot) : t;
    size_t keep = 200 - ext.size();
    if (stem.size() > keep) {
      while (keep > 0 && (static_cast<unsigned char>(stem[keep]) & 0xC0) == 0x80) --keep;
      stem.resize(keep);
    }
    t = stem + ext;
  }
  out = t;
  return !out.empty();
}

std::string unique_path(const std::string& dir, const std::string& name) {
  std::string p = dir + "/" + name;
  if (::access(p.c_str(), F_OK) != 0) return p;
  const size_t dot = name.rfind('.');
  const bool has_ext = dot != std::string::npos && dot > 0;
  const std::string stem = has_ext ? name.substr(0, dot) : name;
  const std::string ext = has_ext ? name.substr(dot) : std::string();
  for (int n = 1; n < 10000; ++n) {
    p = dir + "/" + stem + " (" + std::to_string(n) + ")" + ext;
    if (::access(p.c_str(), F_OK) != 0) return p;
  }
  return dir + "/" + stem + "." + std::to_string(now_ms()) + ext;
}

// Partial file + resume metadata:  .share64/<id>.part  and  .share64/<id>.meta
//   meta = 64-byte header (magic, version, id, size, chunk, committed_chunks) + 32-byte hash per chunk.
class PartStore {
 public:
  PartStore() = default;
  PartStore(const PartStore&) = delete;
  PartStore& operator=(const PartStore&) = delete;
  ~PartStore() { close_all(); }

  Err open(const std::string& dir, const proto::Offer& o, uint64_t total, uint64_t& committed,
           bool& already_done) {
    size_ = o.size;
    chunk_ = o.chunk;
    total_ = total;
    const std::string sd = dir + "/.share64";
    if (::mkdir(sd.c_str(), 0700) != 0 && errno != EEXIST) return Err::Io;
    uint8_t idb[8];
    put_le64(idb, o.id);
    const std::string stem = sd + "/" + to_hex(idb, 8);
    part_path_ = stem + ".part";
    meta_path_ = stem + ".meta";
    committed = 0;
    already_done = false;

    bool reuse = false;
    meta_fd_ = ::open(meta_path_.c_str(), O_RDWR | O_CLOEXEC);
    if (meta_fd_ >= 0) {
      uint8_t hdr[kMetaHeader];
      struct stat ms;
      if (pread_full(meta_fd_, hdr, sizeof hdr, 0) && fstat(meta_fd_, &ms) == 0 &&
          get_le32(hdr) == kMetaMagic && get_le32(hdr + 4) == 1 && get_le64(hdr + 8) == o.id &&
          get_le64(hdr + 16) == o.size && get_le32(hdr + 24) == o.chunk &&
          static_cast<uint64_t>(ms.st_size) == kMetaHeader + total * kHashLen) {
        const uint64_t c = std::min(get_le64(hdr + 32), total);
        part_fd_ = ::open(part_path_.c_str(), O_RDWR | O_CLOEXEC);
        struct stat ps;
        if (part_fd_ >= 0 && fstat(part_fd_, &ps) == 0 && static_cast<uint64_t>(ps.st_size) == o.size) {
          committed = c;
          reuse = true;
        } else if (part_fd_ < 0 && errno == ENOENT && c == total &&
                   std::time(nullptr) - ms.st_mtime < kCompletedWindowSec) {
          committed = total;  // finalized moments ago, the DONE was lost: just answer it again
          already_done = true;
          return Err::Ok;
        }
      }
    }
    if (!reuse) {
      close_all();
      struct statvfs vfs;
      if (::statvfs(sd.c_str(), &vfs) == 0) {
        const uint64_t unit = vfs.f_frsize ? vfs.f_frsize : vfs.f_bsize;
        if (static_cast<uint64_t>(vfs.f_bavail) * unit < o.size + kMetaHeader + total * kHashLen)
          return Err::DiskFull;
      }
      part_fd_ = ::open(part_path_.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
      if (part_fd_ < 0) return Err::Io;
      if (::fallocate(part_fd_, 0, 0, static_cast<off_t>(o.size)) != 0) {
        if (errno == ENOSPC) { purge(); return Err::DiskFull; }
        if (::ftruncate(part_fd_, static_cast<off_t>(o.size)) != 0) {
          const bool full = errno == ENOSPC;
          purge();
          return full ? Err::DiskFull : Err::Io;
        }
      }
      meta_fd_ = ::open(meta_path_.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
      if (meta_fd_ < 0) { purge(); return Err::Io; }
      uint8_t hdr[kMetaHeader] = {0};
      put_le32(hdr, kMetaMagic);
      put_le32(hdr + 4, 1);
      put_le64(hdr + 8, o.id);
      put_le64(hdr + 16, o.size);
      put_le32(hdr + 24, o.chunk);
      if (pwrite_full(meta_fd_, hdr, sizeof hdr, 0) != 0 ||
          ::ftruncate(meta_fd_, static_cast<off_t>(kMetaHeader + total * kHashLen)) != 0) {
        purge();
        return Err::Io;
      }
      return Err::Ok;
    }
    // Resumed: re-verify the newest committed chunks against their stored hashes (power-loss safety).
    if (committed > 0) {
      std::vector<uint8_t> tmp(chunk_);
      const uint64_t top = committed;
      uint64_t lowest_bad = top;
      for (uint64_t k = 0; k < 3 && k < top; ++k) {
        const uint64_t idx = top - 1 - k;
        const uint64_t n = std::min<uint64_t>(chunk_, size_ - idx * chunk_);
        uint8_t want[kHashLen], got[kHashLen];
        const bool ok = pread_full(part_fd_, tmp.data(), n, idx * chunk_) &&
                        pread_full(meta_fd_, want, kHashLen, kMetaHeader + idx * kHashLen) &&
                        (Sha256::hash(tmp.data(), n, got), std::memcmp(got, want, kHashLen) == 0);
        if (!ok) lowest_bad = idx;
      }
      if (lowest_bad < top) {
        committed = lowest_bad;
        uint8_t b[8];
        put_le64(b, committed);
        if (pwrite_full(meta_fd_, b, 8, 32) != 0) return Err::Io;
      }
    }
    return Err::Ok;
  }

  Err commit(uint64_t idx, const uint8_t* hash) {
    if (pwrite_full(meta_fd_, hash, kHashLen, kMetaHeader + idx * kHashLen) != 0) return Err::Io;
    uint8_t b[8];
    put_le64(b, idx + 1);
    return pwrite_full(meta_fd_, b, 8, 32) == 0 ? Err::Ok : Err::Io;
  }

  // Root = SHA-256 over every chunk hash, in order.
  Err root(uint8_t out[kHashLen]) {
    Sha256 h;
    uint8_t buf[4096];
    uint64_t off = kMetaHeader;
    uint64_t left = total_ * kHashLen;
    while (left) {
      const size_t n = static_cast<size_t>(std::min<uint64_t>(left, sizeof buf));
      if (!pread_full(meta_fd_, buf, n, off)) return Err::Io;
      h.update(buf, n);
      off += n;
      left -= n;
    }
    h.finish(out);
    return Err::Ok;
  }

  Err finalize(const std::string& dir, const std::string& name, int64_t mtime_ms, std::string& final_path) {
    if (::fdatasync(part_fd_) != 0 && (errno == EIO || errno == ENOSPC)) return Err::Io;
    if (mtime_ms > 0) {
      timespec ts[2];
      ts[0].tv_sec = 0;
      ts[0].tv_nsec = UTIME_OMIT;
      ts[1].tv_sec = static_cast<time_t>(mtime_ms / 1000);
      ts[1].tv_nsec = static_cast<long>(mtime_ms % 1000) * 1000000L;
      ::futimens(part_fd_, ts);
    }
    ::close(part_fd_);
    part_fd_ = -1;
    final_path = unique_path(dir, name);
    if (::rename(part_path_.c_str(), final_path.c_str()) != 0) return Err::Io;
    ::futimens(meta_fd_, nullptr);  // keeps the "already complete" window anchored to now
    return Err::Ok;
  }

  void purge() {
    close_all();
    if (!part_path_.empty()) ::unlink(part_path_.c_str());
    if (!meta_path_.empty()) ::unlink(meta_path_.c_str());
  }

  int part_fd() const { return part_fd_; }

 private:
  void close_all() {
    if (part_fd_ >= 0) ::close(part_fd_);
    if (meta_fd_ >= 0) ::close(meta_fd_);
    part_fd_ = meta_fd_ = -1;
  }
  int part_fd_ = -1, meta_fd_ = -1;
  uint64_t size_ = 0, total_ = 0;
  uint32_t chunk_ = 0;
  std::string part_path_, meta_path_;
};

void purge_stale(const std::string& dir) {
  const std::string sd = dir + "/.share64";
  DIR* d = ::opendir(sd.c_str());
  if (!d) return;
  const time_t cutoff = std::time(nullptr) - static_cast<time_t>(kStaleDays) * 86400;
  while (dirent* de = ::readdir(d)) {
    if (de->d_name[0] == '.') continue;
    const std::string p = sd + "/" + de->d_name;
    struct stat st;
    if (::stat(p.c_str(), &st) == 0 && st.st_mtime < cutoff) ::unlink(p.c_str());
  }
  ::closedir(d);
}

}  // namespace

struct Receiver::Session {
  int fd = -1;
  SessionCtl ctl;
  std::thread th;
  std::atomic<bool> finished{false};
  std::atomic<uint64_t> id{0};
  std::atomic<bool> purge{false};
  std::mutex dm;
  std::condition_variable dcv;
  int decision = 0;  // 0 pending, 1 accept, -1 decline
  std::atomic<uint64_t> done_bytes{0};
  std::atomic<uint64_t> verified_bytes{0};
};

struct Receiver::ServeState {
  PartStore store;
  bool started = false;
  uint64_t id = 0;
  uint64_t size = 0;
  std::string name;
};

Err Receiver::start(const std::string& dir, uint16_t port, uint16_t* bound) {
  stop();
  if (::mkdir(dir.c_str(), 0700) != 0 && errno != EEXIST) return Err::Io;
  purge_stale(dir);
  Err e = Err::Ok;
  uint16_t p = 0;
  const int fd = net::listen_tcp(port, &p, e);
  if (fd < 0) return e;
  dir_ = dir;
  lfd_ = fd;
  stopping_.store(false);
  stop_.store(false);
  accept_th_ = std::thread([this] { accept_loop(); });
  if (bound) *bound = p;
  Event ev = make_event(EventKind::Listening, 0, Direction::Receive, std::string(), 0);
  ev.done = p;
  emit(ev);
  return Err::Ok;
}

void Receiver::stop() {
  if (stop_.exchange(true)) return;
  stopping_.store(true);
  if (accept_th_.joinable()) accept_th_.join();
  if (lfd_ >= 0) { ::close(lfd_); lfd_ = -1; }
  std::vector<std::shared_ptr<Session>> all;
  {
    std::lock_guard<std::mutex> g(mu_);
    all.swap(sessions_);
  }
  for (auto& s : all) { s->ctl.request_cancel(); s->dcv.notify_all(); }
  for (auto& s : all) if (s->th.joinable()) s->th.join();
}

void Receiver::accept_loop() {
  while (!stop_.load(std::memory_order_acquire)) {
    const int fd = net::accept_tcp(lfd_, stop_);
    reap();
    if (fd < 0) continue;
    std::shared_ptr<Session> s;
    {
      std::lock_guard<std::mutex> g(mu_);
      uint32_t live = 0;
      for (auto& x : sessions_) if (!x->finished.load()) ++live;
      if (live >= cfg_.max_sessions) { ::close(fd); continue; }
      s = std::make_shared<Session>();
      s->fd = fd;
      sessions_.push_back(s);
    }
    Session* raw = s.get();  // the registry owns the Session until its thread is joined
    raw->th = std::thread([this, raw] {
      run_session(*raw);
      raw->finished.store(true, std::memory_order_release);
    });
  }
}

void Receiver::reap() {
  std::vector<std::shared_ptr<Session>> dead;
  {
    std::lock_guard<std::mutex> g(mu_);
    for (auto it = sessions_.begin(); it != sessions_.end();) {
      if ((*it)->finished.load(std::memory_order_acquire)) {
        dead.push_back(*it);
        it = sessions_.erase(it);
      } else {
        ++it;
      }
    }
  }
  for (auto& s : dead) if (s->th.joinable()) s->th.join();
}

void Receiver::respond(uint64_t id, bool accept) {
  std::lock_guard<std::mutex> g(mu_);
  for (auto& s : sessions_) {
    if (s->id.load() == id && !s->finished.load()) {
      { std::lock_guard<std::mutex> dl(s->dm); s->decision = accept ? 1 : -1; }
      s->dcv.notify_all();
    }
  }
}

void Receiver::cancel(uint64_t id) {
  std::lock_guard<std::mutex> g(mu_);
  const int64_t now = now_ms();
  for (auto it = cancelled_.begin(); it != cancelled_.end();) {
    it = (now - it->second > 600000) ? cancelled_.erase(it) : std::next(it);
  }
  cancelled_[id] = now;  // the sender's next reconnect gets a final "cancelled" reject
  for (auto& s : sessions_) {
    if (s->id.load() == id && !s->finished.load()) {
      s->purge.store(true);
      s->ctl.request_cancel();
      s->dcv.notify_all();
    }
  }
}

void Receiver::run_session(Session& s) {
  ServeState st;
  const Err e = serve(s, st);
  s.ctl.unbind();
  ::close(s.fd);
  s.fd = -1;
  if (e == Err::Ok || !st.started) return;
  if (e == Err::Cancelled && s.purge.load()) {
    st.store.purge();
    Event ev = make_event(EventKind::Cancelled, st.id, Direction::Receive, st.name, st.size);
    ev.err = Err::Cancelled;
    emit(ev);
    return;
  }
  Event ev = make_event(EventKind::Interrupted, st.id, Direction::Receive, st.name, st.size);
  ev.err = e;
  ev.done = s.done_bytes.load();
  ev.verified = s.verified_bytes.load();
  ev.message = s.ctl.message();
  emit(ev);
}

Err Receiver::serve(Session& s, ServeState& st) {
  SessionCtl& ctl = s.ctl;
  const std::atomic<bool>& ab = ctl.abort_flag();
  const int fd = s.fd;
  net::tune(fd, cfg_.io_timeout_ms);
  ctl.bind(fd, nullptr);

  uint8_t cbuf[proto::kMaxControl];
  proto::Header h;
  std::string fm;
  auto bail = [&](Err x, const std::string& m = std::string()) -> Err {
    ctl.fail(x, m.empty() ? fm : m);
    return ctl.err();
  };
  auto reject = [&](Err code, const char* m) -> Err {
    proto::send_fault(fd, proto::Msg::Reject, code, m, ab);
    return bail(code, m);
  };

  Err e = proto::read_expect(fd, proto::Msg::Hello, h, cbuf, sizeof cbuf, ab, &fm);
  if (e != Err::Ok) return bail(e);
  proto::Hello hello;
  if (!proto::decode_hello(cbuf, h.length, hello)) return bail(Err::Protocol);
  proto::Hello mine;
  mine.device = cfg_.device_name;
  if ((e = proto::send_hello(fd, proto::Msg::HelloAck, mine, ab)) != Err::Ok) return bail(e);

  e = proto::read_expect(fd, proto::Msg::Offer, h, cbuf, sizeof cbuf, ab, &fm);
  if (e != Err::Ok) return bail(e);
  proto::Offer off;
  if (!proto::decode_offer(cbuf, h.length, off)) return bail(Err::Protocol);

  std::string name;
  if (!sanitize_name(off.name, name)) return reject(Err::BadName, "invalid file name");
  if (off.chunk < kMinChunkSize || off.chunk > kMaxChunkSize) return reject(Err::Protocol, "bad chunk size");
  const uint64_t chunk = off.chunk;
  const uint64_t total = (off.size + chunk - 1) / chunk;
  if (total > kMaxChunks) return reject(Err::Protocol, "file too large");
  st.id = off.id;
  st.size = off.size;
  st.name = name;

  bool was_cancelled = false;
  {
    std::lock_guard<std::mutex> g(mu_);
    auto it = cancelled_.find(off.id);
    if (it != cancelled_.end()) { cancelled_.erase(it); was_cancelled = true; }
  }
  if (was_cancelled) return reject(Err::Cancelled, "cancelled by receiver");

  // A reconnecting sender supersedes our half-dead connection for the same transfer.
  std::shared_ptr<Session> old;
  {
    std::lock_guard<std::mutex> g(mu_);
    for (auto& o : sessions_) {
      if (o.get() != &s && o->id.load() == off.id && !o->finished.load()) { old = o; break; }
    }
    s.id.store(off.id);
  }
  if (old) {
    old->ctl.fail(Err::Io, "superseded by reconnect");
    for (int i = 0; i < 300 && !old->finished.load(); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(10));
    if (!old->finished.load()) return reject(Err::Busy, "previous connection still closing");
  }

  if (!cfg_.auto_accept) {
    Event ev = make_event(EventKind::IncomingOffer, off.id, Direction::Receive, name, off.size);
    ev.message = hello.device;
    emit(ev);
    const int64_t until = now_ms() + cfg_.offer_timeout_ms;
    std::unique_lock<std::mutex> lk(s.dm);
    while (s.decision == 0 && !ctl.aborted() && now_ms() < until) {
      s.dcv.wait_for(lk, std::chrono::milliseconds(200));
    }
    const int d = s.decision;
    lk.unlock();
    if (ctl.aborted()) return ctl.err();
    if (d != 1) return reject(Err::Rejected, d == -1 ? "declined by receiver" : "no response");
  }

  // Empty file: nothing to stream.
  if (off.size == 0) {
    const std::string fp = unique_path(dir_, name);
    const int ofd = ::open(fp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0644);
    if (ofd < 0) return reject(Err::Io, "cannot create file");
    ::close(ofd);
    uint8_t root[kHashLen];
    Sha256::hash("", 0, root);
    proto::Accept a;
    a.id = off.id;
    a.resume = 0;
    if ((e = proto::send_accept(fd, a, ab)) != Err::Ok) return bail(e);
    proto::send_done(fd, root, ab);
    Event ev = make_event(EventKind::Completed, off.id, Direction::Receive, fp, 0);
    ev.message = to_hex(root, kHashLen);
    emit(ev);
    return Err::Ok;
  }

  uint64_t base = 0;
  bool already_done = false;
  e = st.store.open(dir_, off, total, base, already_done);
  if (e != Err::Ok) return reject(e, e == Err::DiskFull ? "not enough free space" : "cannot open partial file");

  proto::Accept acc;
  acc.id = off.id;
  acc.resume = base;
  if ((e = proto::send_accept(fd, acc, ab)) != Err::Ok) return bail(e);

  uint8_t root[kHashLen];
  std::string final_path;
  bool completed = false;
  const uint64_t base_bytes = std::min<uint64_t>(off.size, base * chunk);
  s.done_bytes.store(base_bytes);
  s.verified_bytes.store(base_bytes);

  auto finish = [&]() -> Err {  // fsync, root hash, atomic rename, DONE
    Err fe = st.store.root(root);
    if (fe != Err::Ok) return fe;
    fe = st.store.finalize(dir_, name, off.mtime_ms, final_path);
    if (fe != Err::Ok) return fe;
    completed = true;
    Event ev = make_event(EventKind::Completed, off.id, Direction::Receive, final_path, off.size);
    ev.done = ev.verified = off.size;
    ev.message = to_hex(root, kHashLen);
    emit(ev);
    proto::send_done(fd, root, ab);  // best effort: if it is lost, a re-offer is answered from the meta
    return Err::Ok;
  };

  if (already_done) {
    if ((e = st.store.root(root)) != Err::Ok) return bail(e);
    proto::send_done(fd, root, ab);
    return Err::Ok;
  }

  st.started = true;
  {
    Event ev = make_event(EventKind::Started, off.id, Direction::Receive, name, off.size);
    ev.done = ev.verified = base_bytes;
    emit(ev);
  }

  if (base == total) {  // everything was already verified; only finalize was missing
    e = finish();
    return e == Err::Ok ? Err::Ok : bail(e, "finalize failed");
  }

  const uint32_t S = cfg_.slots;
  SlotPool pool;
  if (!pool.init(S, chunk)) return bail(Err::Internal, "out of memory");
  ctl.bind(fd, &pool);
  Reporter rep(sink_, cfg_, off.id, Direction::Receive, name, off.size, &s.done_bytes, &s.verified_bytes);
  rep.start();

  // ---- verify + write workers ----
  std::atomic<uint64_t> next_work{base};
  std::vector<std::thread> workers;
  const uint64_t nw = std::min<uint64_t>(cfg_.workers, total - base);
  for (uint64_t k = 0; k < nw; ++k) {
    workers.emplace_back([&] {
      for (;;) {
        const uint64_t i = next_work.fetch_add(1, std::memory_order_relaxed);
        if (i >= total) return;
        const uint64_t rel = i - base;
        Slot& sl = pool.at(rel);
        if (!wait_ge(sl.state, slot_target(rel, S, kFull), ab)) return;
        uint8_t got[kHashLen];
        Sha256::hash(sl.buf, sl.len, got);
        if (std::memcmp(got, sl.hash, kHashLen) != 0) {
          ctl.fail(Err::HashMismatch, "chunk " + std::to_string(i) + " failed SHA-256");
          return;
        }
        const int we = pwrite_full(st.store.part_fd(), sl.buf, sl.len, i * chunk);
        if (we != 0) {
          ctl.fail(we == ENOSPC ? Err::DiskFull : Err::Io, "disk write failed");
          return;
        }
        sl.state.store(slot_target(rel, S, kDone), std::memory_order_release);
        futex_wake_all(&sl.state);
      }
    });
  }

  // ---- in-order commit + ACK, then finalize ----
  std::thread acker([&] {
    for (uint64_t i = base; i < total; ++i) {
      const uint64_t rel = i - base;
      Slot& sl = pool.at(rel);
      if (!wait_ge(sl.state, slot_target(rel, S, kDone), ab)) return;
      Err ce = st.store.commit(i, sl.hash);
      if (ce != Err::Ok) { ctl.fail(ce, "resume metadata write failed"); return; }
      ce = proto::send_ack(fd, static_cast<uint32_t>(i), ab);
      if (ce != Err::Ok) { ctl.fail(ce, "ack send failed"); return; }
      s.verified_bytes.store(std::min<uint64_t>(off.size, (i + 1) * chunk), std::memory_order_relaxed);
      sl.state.store(4u * static_cast<uint32_t>(rel / S + 1), std::memory_order_release);
      futex_wake_all(&sl.state);
    }
    const Err fe = finish();
    if (fe != Err::Ok) ctl.fail(fe, "finalize failed");
  });

  // ---- network reader (this thread): chunk frames straight into pooled slots ----
  for (uint64_t i = base; i < total && !ctl.aborted(); ++i) {
    const uint64_t rel = i - base;
    Slot& sl = pool.at(rel);
    proto::Header ch;
    Err re = proto::read_header(fd, ch, ab);
    if (re != Err::Ok) { ctl.fail(re, "connection lost"); break; }
    const uint64_t n = std::min<uint64_t>(chunk, off.size - i * chunk);
    if (ch.type != static_cast<uint8_t>(proto::Msg::Chunk) || ch.aux != static_cast<uint32_t>(i) ||
        ch.length != n + kHashLen) {
      ctl.fail(Err::Protocol, "unexpected frame");
      break;
    }
    if (!wait_ge(sl.state, slot_target(rel, S, kFree), ab)) break;
    uint8_t* p = sl.buf;
    uint64_t left = n;
    bool bad = false;
    while (left) {
      const size_t piece = static_cast<size_t>(std::min<uint64_t>(left, kPiece));
      re = net::recv_all(fd, p, piece, ab);
      if (re != Err::Ok) { ctl.fail(re, "connection lost"); bad = true; break; }
      s.done_bytes.fetch_add(piece, std::memory_order_relaxed);
      p += piece;
      left -= piece;
    }
    if (bad) break;
    re = net::recv_all(fd, sl.hash, kHashLen, ab);
    if (re != Err::Ok) { ctl.fail(re, "connection lost"); break; }
    sl.len = static_cast<uint32_t>(n);
    sl.state.store(slot_target(rel, S, kFull), std::memory_order_release);
    futex_wake_all(&sl.state);
  }

  for (auto& t : workers) t.join();
  acker.join();
  ctl.bind(fd, nullptr);  // pool is about to die
  rep.stop();

  if (completed) return Err::Ok;
  if (ctl.aborted()) return ctl.err();
  return Err::Internal;
}

}  // namespace s64
