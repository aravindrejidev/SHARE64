// Host-side test suite for the SHARE64 engine: loopback transfers with fault injection.
//   cmake -S core-cpp/src/main/cpp -B build && cmake --build build && ./build/share64_host_test
#include <arpa/inet.h>
#include <poll.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "share64/engine.h"
#include "share64/sha256.h"

using namespace s64;

static int g_fail = 0;
#define CHECK(cond, ...)                                              \
  do {                                                                \
    if (!(cond)) {                                                    \
      ++g_fail;                                                       \
      std::printf("  FAIL %s:%d  %s  ", __FILE__, __LINE__, #cond);   \
      std::printf(__VA_ARGS__);                                       \
      std::printf("\n");                                              \
    }                                                                 \
  } while (0)

static std::string hex(const uint8_t* d, size_t n) {
  static const char* k = "0123456789abcdef";
  std::string s;
  for (size_t i = 0; i < n; ++i) { s += k[d[i] >> 4]; s += k[d[i] & 15]; }
  return s;
}

// ---------------------------------------------------------------- event collector
struct Sink : EventSink {
  std::mutex m;
  std::condition_variable cv;
  std::vector<Event> ev;
  void on_event(const Event& e) override {
    { std::lock_guard<std::mutex> g(m); ev.push_back(e); }
    cv.notify_all();
  }
  bool wait(EventKind k, int ms, Event* out = nullptr) {
    std::unique_lock<std::mutex> lk(m);
    return cv.wait_for(lk, std::chrono::milliseconds(ms), [&] {
      for (auto& e : ev) if (e.kind == k) { if (out) *out = e; return true; }
      return false;
    });
  }
  bool wait_pred(int ms, bool (*p)(const Event&), Event* out = nullptr) {
    std::unique_lock<std::mutex> lk(m);
    return cv.wait_for(lk, std::chrono::milliseconds(ms), [&] {
      for (auto& e : ev) if (p(e)) { if (out) *out = e; return true; }
      return false;
    });
  }
  size_t count(EventKind k) {
    std::lock_guard<std::mutex> g(m);
    size_t n = 0;
    for (auto& e : ev) if (e.kind == k) ++n;
    return n;
  }
  bool has_started_with_progress() {
    std::lock_guard<std::mutex> g(m);
    for (auto& e : ev) if (e.kind == EventKind::Started && e.done > 0) return true;
    return false;
  }
  bool has_err(EventKind k, Err err) {
    std::lock_guard<std::mutex> g(m);
    for (auto& e : ev) if (e.kind == k && e.err == err) return true;
    return false;
  }
};

// ---------------------------------------------------------------- loopback fault-injection proxy
struct Proxy {
  int lfd = -1;
  uint16_t port = 0, target = 0;
  std::vector<uint64_t> kill_after;  // per connection: kill after this many client->server bytes (0 = never)
  int64_t flip_offset = -1;          // connection #0: flip one bit at this client->server offset
  std::atomic<int> throttle_us{0};
  std::atomic<bool> stop{false};
  std::atomic<int> conns{0};
  std::thread th;
  std::mutex m;
  std::vector<std::thread> mgr;

  void start(uint16_t tgt) {
    target = tgt;
    lfd = ::socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(lfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    ::bind(lfd, (sockaddr*)&a, sizeof a);
    ::listen(lfd, 8);
    socklen_t sl = sizeof a;
    getsockname(lfd, (sockaddr*)&a, &sl);
    port = ntohs(a.sin_port);
    th = std::thread([this] { loop(); });
  }
  void loop() {
    while (!stop) {
      pollfd p{lfd, POLLIN, 0};
      if (::poll(&p, 1, 100) <= 0) continue;
      int c = ::accept(lfd, nullptr, nullptr);
      if (c < 0) continue;
      int s = ::socket(AF_INET, SOCK_STREAM, 0);
      sockaddr_in a{};
      a.sin_family = AF_INET;
      a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
      a.sin_port = htons(target);
      if (::connect(s, (sockaddr*)&a, sizeof a) != 0) { ::close(c); ::close(s); continue; }
      const int k = conns++;
      const uint64_t limit = (size_t)k < kill_after.size() ? kill_after[k] : 0;
      const int64_t flip = k == 0 ? flip_offset : -1;
      std::lock_guard<std::mutex> g(m);
      mgr.emplace_back([=] { relay_pair(c, s, limit, flip); });
    }
  }
  void relay_pair(int c, int s, uint64_t limit, int64_t flip) {
    std::thread up([=] {
      uint8_t buf[65536];
      uint64_t total = 0;
      for (;;) {
        ssize_t n = ::recv(c, buf, sizeof buf, 0);
        if (n <= 0) break;
        if (flip >= 0 && (int64_t)total <= flip && flip < (int64_t)(total + n)) buf[flip - total] ^= 0x10;
        if (limit && total + n >= limit) {
          const size_t part = (size_t)(limit - total);
          if (part) ::send(s, buf, part, MSG_NOSIGNAL);
          break;
        }
        total += n;
        if (::send(s, buf, n, MSG_NOSIGNAL) != n) break;
        if (int t = throttle_us.load()) usleep(t);
      }
      ::shutdown(c, SHUT_RDWR);
      ::shutdown(s, SHUT_RDWR);
    });
    std::thread down([=] {
      uint8_t buf[65536];
      for (;;) {
        ssize_t n = ::recv(s, buf, sizeof buf, 0);
        if (n <= 0) break;
        if (::send(c, buf, n, MSG_NOSIGNAL) != n) break;
      }
      ::shutdown(c, SHUT_RDWR);
      ::shutdown(s, SHUT_RDWR);
    });
    up.join();
    down.join();
    ::close(c);
    ::close(s);
  }
  void shutdown_all() {
    stop = true;
    if (th.joinable()) th.join();
    for (auto& t : mgr) t.join();
    if (lfd >= 0) ::close(lfd);
  }
};

// ---------------------------------------------------------------- helpers
static std::string make_dir() {
  char t[] = "/tmp/s64test.XXXXXX";
  return mkdtemp(t);
}

static void make_file(const std::string& path, size_t n, uint32_t seed) {
  std::ofstream f(path, std::ios::binary);
  std::vector<uint8_t> b(1 << 16);
  uint32_t x = seed * 2654435761u + 1;
  size_t left = n;
  while (left) {
    const size_t k = std::min(left, b.size());
    for (size_t i = 0; i < k; ++i) { x ^= x << 13; x ^= x >> 17; x ^= x << 5; b[i] = (uint8_t)x; }
    f.write((const char*)b.data(), k);
    left -= k;
  }
}

static bool files_equal(const std::string& a, const std::string& b) {
  std::ifstream fa(a, std::ios::binary), fb(b, std::ios::binary);
  if (!fa || !fb) return false;
  std::vector<char> x(1 << 20), y(1 << 20);
  for (;;) {
    fa.read(x.data(), x.size());
    fb.read(y.data(), y.size());
    if (fa.gcount() != fb.gcount()) return false;
    if (fa.gcount() == 0) return true;
    if (std::memcmp(x.data(), y.data(), (size_t)fa.gcount()) != 0) return false;
  }
}

static std::string tree_root(const std::string& path, size_t chunk) {
  std::ifstream f(path, std::ios::binary);
  std::vector<char> b(chunk);
  Sha256 root;
  for (;;) {
    f.read(b.data(), b.size());
    const size_t n = (size_t)f.gcount();
    if (n == 0) break;
    uint8_t h[32];
    Sha256::hash(b.data(), n, h);
    root.update(h, 32);
  }
  uint8_t out[32];
  root.finish(out);
  return hex(out, 32);
}

static Config test_cfg(const char* dev, bool auto_accept) {
  Config c;
  c.device_name = dev;
  c.auto_accept = auto_accept;
  c.io_timeout_ms = 4000;
  c.connect_timeout_ms = 2000;
  c.done_timeout_ms = 10000;
  c.offer_timeout_ms = 5000;
  c.retry_base_ms = 100;
  c.retry_max_ms = 400;
  c.progress_interval_ms = 50;
  return c;
}

struct Rig {
  std::string rxdir = make_dir(), srcdir = make_dir();
  Sink rs, ss;
  std::unique_ptr<Engine> rx, tx;
  uint16_t port = 0;
  Rig(bool auto_accept = true, Config tcfg = test_cfg("sender", true)) {
    rx.reset(new Engine(test_cfg("receiver", auto_accept), &rs));
    tx.reset(new Engine(tcfg, &ss));
    Err e = rx->start_receiver(rxdir, 0, &port);
    CHECK(e == Err::Ok, "start_receiver -> %s", err_name(e));
  }
};

// ---------------------------------------------------------------- tests
static void test_sha() {
  std::printf("[sha256] hardware=%d\n", Sha256::hardware_accelerated());
  uint8_t h[32];
  Sha256::hash("abc", 3, h);
  CHECK(hex(h, 32) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "abc vector");
  const char* two = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
  Sha256::hash(two, std::strlen(two), h);
  CHECK(hex(h, 32) == "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", "two-block vector");
  Sha256::hash("", 0, h);
  CHECK(hex(h, 32) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "empty vector");
  std::vector<uint8_t> big(3 * 1024 * 1024 + 17);
  uint32_t x = 12345;
  for (auto& b : big) { x = x * 1664525u + 1013904223u; b = (uint8_t)(x >> 24); }
  uint8_t a[32], b2[32];
  for (size_t len : {size_t(0), size_t(1), size_t(55), size_t(56), size_t(63), size_t(64), size_t(65), size_t(119),
                     size_t(120), size_t(1000), big.size()}) {
    Sha256::hash(big.data(), len, a);
    Sha256::hash_software(big.data(), len, b2);
    CHECK(std::memcmp(a, b2, 32) == 0, "hw/sw mismatch len=%zu", len);
    Sha256 inc;  // incremental in odd pieces
    for (size_t o = 0; o < len;) { size_t k = std::min<size_t>(len - o, 37 + o % 91); inc.update(big.data() + o, k); o += k; }
    inc.finish(b2);
    CHECK(std::memcmp(a, b2, 32) == 0, "incremental mismatch len=%zu", len);
  }
}

static void test_basic_sizes() {
  std::printf("[basic] sizes around chunk boundaries, hostile names\n");
  Rig r;
  const size_t C = kDefaultChunkSize;
  struct Case { size_t size; const char* name; } cases[] = {
      {0, "empty.bin"}, {1, "one.bin"}, {C, "exact.bin"}, {C + 1, "plus1.bin"}, {5 * C - 3, "big.bin"},
      {1000, "../../evil.bin"}, {777, "emoji \xF0\x9F\x98\x80.txt"}};
  int i = 0;
  for (auto& c : cases) {
    const std::string src = r.srcdir + "/src" + std::to_string(i);
    make_file(src, c.size, 100 + i);
    Sink& ss = r.ss;
    const size_t before = ss.count(EventKind::Completed);
    uint64_t id = r.tx->send_file("127.0.0.1", r.port, src, c.name, "peerA");
    CHECK(id != 0, "send_file returned 0");
    Event ce;
    bool ok = false;
    for (int t = 0; t < 200 && !ok; ++t) {
      std::this_thread::sleep_for(std::chrono::milliseconds(50));
      ok = ss.count(EventKind::Completed) > before;
    }
    CHECK(ok, "sender did not complete size=%zu", c.size);
    ++i;
  }
  // collect receiver completions and verify content + root hash
  std::this_thread::sleep_for(std::chrono::milliseconds(300));
  std::lock_guard<std::mutex> g(r.rs.m);
  int done = 0;
  for (auto& e : r.rs.ev) {
    if (e.kind != EventKind::Completed) continue;
    ++done;
    CHECK(e.name.find("..") == std::string::npos, "path traversal not stripped: %s", e.name.c_str());
    CHECK(e.name.rfind(r.rxdir, 0) == 0, "saved outside rx dir: %s", e.name.c_str());
  }
  CHECK(done == 7, "receiver completions = %d", done);
  for (int k = 0; k < 7; ++k) {
    const std::string src = r.srcdir + "/src" + std::to_string(k);
    bool found = false;
    for (auto& e : r.rs.ev) {
      if (e.kind == EventKind::Completed && e.total == cases[k].size && files_equal(src, e.name)) {
        if (cases[k].size) CHECK(e.message == tree_root(src, C), "root hash mismatch for case %d", k);
        found = true;
        break;
      }
    }
    CHECK(found, "no identical saved file for case %d (size %zu)", k, cases[k].size);
  }
}

static void test_faults() {
  std::printf("[faults] connection kills + bit flip, 37.5 MB, resume must be seamless\n");
  Rig r;
  Proxy px;
  px.kill_after = {9u * 1024 * 1024 + 12345, 14u * 1024 * 1024, 0};
  px.start(r.port);
  const std::string src = r.srcdir + "/fault.bin";
  make_file(src, 37 * 1024 * 1024 + 512 * 1024, 7);
  uint64_t id = r.tx->send_file("127.0.0.1", px.port, src, "fault.bin", "peerB");
  CHECK(id != 0, "id");
  Event ce;
  CHECK(r.ss.wait(EventKind::Completed, 60000, &ce), "sender never completed");
  CHECK(r.ss.count(EventKind::Retrying) >= 2, "expected >=2 retries, got %zu", r.ss.count(EventKind::Retrying));
  CHECK(r.ss.has_started_with_progress(), "resume did not start past offset 0");
  Event rc;
  CHECK(r.rs.wait(EventKind::Completed, 10000, &rc), "receiver never completed");
  CHECK(files_equal(src, rc.name), "FILE CONTENT DIFFERS after resume");
  CHECK(rc.message == tree_root(src, kDefaultChunkSize), "receiver root hash wrong");
  CHECK(ce.message == rc.message, "sender/receiver root mismatch");
  CHECK(r.rs.count(EventKind::Interrupted) >= 2, "receiver should report interruptions");
  px.shutdown_all();

  std::printf("[faults] corrupted byte on the wire -> HashMismatch -> retry -> correct file\n");
  Rig r2;
  Proxy p2;
  p2.flip_offset = 3u * 1024 * 1024 + 4242;  // inside chunk 1 of connection 0
  p2.start(r2.port);
  const std::string s2 = r2.srcdir + "/flip.bin";
  make_file(s2, 9 * 1024 * 1024, 9);
  r2.tx->send_file("127.0.0.1", p2.port, s2, "flip.bin", "peerC");
  CHECK(r2.ss.wait(EventKind::Completed, 30000), "sender never completed (flip)");
  Event rc2;
  CHECK(r2.rs.wait(EventKind::Completed, 10000, &rc2), "receiver never completed (flip)");
  CHECK(files_equal(s2, rc2.name), "FILE CONTENT DIFFERS after corruption retry");
  CHECK(r2.rs.has_err(EventKind::Interrupted, Err::HashMismatch), "receiver did not flag HashMismatch");
  p2.shutdown_all();
}

static void test_restart_resume() {
  std::printf("[resume] sender app killed mid-transfer, new engine resumes from receiver state\n");
  Rig r;
  Proxy px;
  px.throttle_us = 1500;  // slow the wire so we can interrupt in the middle
  px.start(r.port);
  const std::string src = r.srcdir + "/restart.bin";
  make_file(src, 24 * 1024 * 1024, 21);
  r.tx->send_file("127.0.0.1", px.port, src, "restart.bin", "peerD");
  CHECK(r.ss.wait_pred(30000, [](const Event& e) { return e.kind == EventKind::Progress && e.verified >= 6u * 1024 * 1024; }),
        "never reached 6 MB verified");
  r.tx.reset();  // "process death"
  CHECK(r.rs.wait(EventKind::Interrupted, 10000), "receiver did not notice the drop");
  px.throttle_us = 0;
  Sink ss2;
  Engine tx2(test_cfg("sender2", true), &ss2);
  tx2.send_file("127.0.0.1", px.port, src, "restart.bin", "peerD");
  Event ce;
  CHECK(ss2.wait(EventKind::Completed, 40000, &ce), "second sender did not complete");
  CHECK(ss2.has_started_with_progress(), "new engine restarted from zero instead of resuming");
  Event rc;
  CHECK(r.rs.wait(EventKind::Completed, 10000, &rc), "receiver never completed");
  CHECK(files_equal(src, rc.name), "FILE CONTENT DIFFERS after restart-resume");
  px.shutdown_all();
}

static void test_user_decisions() {
  std::printf("[decisions] manual accept, decline, receiver-side cancel\n");
  Rig r(false);
  const std::string src = r.srcdir + "/m.bin";
  make_file(src, 3 * 1024 * 1024, 3);

  uint64_t id = r.tx->send_file("127.0.0.1", r.port, src, "m.bin", "peerE");
  Event offer;
  CHECK(r.rs.wait(EventKind::IncomingOffer, 5000, &offer), "no IncomingOffer event");
  CHECK(offer.message == "sender", "sender device name = '%s'", offer.message.c_str());
  CHECK(offer.transfer_id == id, "offer id mismatch");
  r.rx->respond(id, true);
  Event rc;
  CHECK(r.ss.wait(EventKind::Completed, 15000), "accepted transfer did not complete");
  CHECK(r.rs.wait(EventKind::Completed, 5000, &rc) && files_equal(src, rc.name), "accepted file differs");

  const std::string src2 = r.srcdir + "/d.bin";
  make_file(src2, 1024 * 1024, 4);
  Rig r2(false);
  uint64_t id2 = r2.tx->send_file("127.0.0.1", r2.port, src2, "d.bin", "peerF");
  CHECK(r2.rs.wait(EventKind::IncomingOffer, 5000), "no offer (decline test)");
  r2.rx->respond(id2, false);
  Event fe;
  CHECK(r2.ss.wait(EventKind::Failed, 10000, &fe), "decline did not fail the sender");
  CHECK(fe.err == Err::Rejected, "expected Rejected, got %s", err_name(fe.err));
  CHECK(r2.ss.count(EventKind::Retrying) == 0, "a decline must not be retried");

  Rig r3(true);
  Proxy px;
  px.throttle_us = 2000;
  px.start(r3.port);
  const std::string src3 = r3.srcdir + "/c.bin";
  make_file(src3, 30 * 1024 * 1024, 5);
  uint64_t id3 = r3.tx->send_file("127.0.0.1", px.port, src3, "c.bin", "peerG");
  CHECK(r3.rs.wait_pred(20000, [](const Event& e) { return e.kind == EventKind::Progress && e.done > 2u * 1024 * 1024; }),
        "no progress before cancel");
  r3.rx->cancel(id3);
  CHECK(r3.rs.wait(EventKind::Cancelled, 10000), "receiver did not report Cancelled");
  CHECK(r3.ss.wait_pred(20000, [](const Event& e) { return e.kind == EventKind::Cancelled || e.kind == EventKind::Failed; }),
        "sender did not stop after receiver cancel");
  CHECK(r3.ss.count(EventKind::Completed) == 0, "cancelled transfer must not complete");
  px.shutdown_all();
}

static void test_small_chunks_many_slots() {
  std::printf("[config] 64 KB chunks, 8 slots, 4 workers, throughput\n");
  Config t = test_cfg("sender", true);
  t.chunk_size = 64 * 1024;
  t.slots = 8;
  t.workers = 4;
  Rig r(true, t);
  const std::string src = r.srcdir + "/s.bin";
  make_file(src, 64 * 1024 * 1024 + 999, 11);
  auto t0 = std::chrono::steady_clock::now();
  r.tx->send_file("127.0.0.1", r.port, src, "s.bin", "peerH");
  CHECK(r.ss.wait(EventKind::Completed, 60000), "no completion");
  double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  Event rc;
  CHECK(r.rs.wait(EventKind::Completed, 5000, &rc) && files_equal(src, rc.name), "content differs");
  CHECK(rc.message == tree_root(src, 64 * 1024), "root hash wrong");
  std::printf("  64 MB in %.2fs = %.0f MB/s (loopback, includes SHA-256 both sides)\n", secs, 64.0 / secs);
}

int main() {
  test_sha();
  test_basic_sizes();
  test_faults();
  test_restart_resume();
  test_user_decisions();
  test_small_chunks_many_slots();
  std::printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASSED\n", g_fail);
  return g_fail ? 1 : 0;
}
