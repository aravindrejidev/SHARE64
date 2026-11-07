#include "session.h"

#include <sys/socket.h>

#include <algorithm>

#include "util.h"

namespace s64 {

const char* err_name(Err e) {
  switch (e) {
    case Err::Ok: return "ok";
    case Err::Io: return "io-error";
    case Err::Protocol: return "protocol-error";
    case Err::HashMismatch: return "hash-mismatch";
    case Err::Rejected: return "rejected";
    case Err::Cancelled: return "cancelled";
    case Err::DiskFull: return "disk-full";
    case Err::Timeout: return "timeout";
    case Err::Busy: return "busy";
    case Err::BadName: return "bad-name";
    case Err::Version: return "version-mismatch";
    case Err::NotFound: return "not-found";
    case Err::Internal: return "internal";
  }
  return "unknown";
}

void SessionCtl::reset() {
  std::lock_guard<std::mutex> g(mu_);
  err_.store(0, std::memory_order_release);
  msg_.clear();
  abort_.store(false, std::memory_order_release);
}

void SessionCtl::bind(int sock, SlotPool* pool) {
  std::lock_guard<std::mutex> g(mu_);
  sock_ = sock;
  pool_ = pool;
}

bool SessionCtl::fail(Err e, const std::string& msg) {
  int expected = 0;
  const bool first = err_.compare_exchange_strong(expected, static_cast<int>(e));
  std::lock_guard<std::mutex> g(mu_);
  if (first) msg_ = msg;
  abort_.store(true, std::memory_order_release);
  if (sock_ >= 0) ::shutdown(sock_, SHUT_RDWR);
  if (pool_) pool_->wake_all();
  return first;
}

void SessionCtl::request_cancel() {
  cancel_.store(true, std::memory_order_release);
  fail(Err::Cancelled, "cancelled");
}

std::string SessionCtl::message() {
  std::lock_guard<std::mutex> g(mu_);
  return msg_;
}

Event make_event(EventKind kind, uint64_t id, Direction dir, const std::string& name, uint64_t total) {
  Event e;
  e.kind = kind;
  e.transfer_id = id;
  e.dir = dir;
  e.name = name;
  e.total = total;
  return e;
}

Reporter::Reporter(EventSink* sink, const Config& cfg, uint64_t id, Direction dir, std::string name,
                   uint64_t total, const std::atomic<uint64_t>* done,
                   const std::atomic<uint64_t>* verified)
    : sink_(sink),
      interval_ms_(std::max(20, cfg.progress_interval_ms)),
      id_(id),
      dir_(dir),
      name_(std::move(name)),
      total_(total),
      done_(done),
      verified_(verified) {}

void Reporter::start() { th_ = std::thread([this] { loop(); }); }

void Reporter::stop() {
  if (!th_.joinable()) return;
  stop_.store(1, std::memory_order_release);
  futex_wake_all(&stop_);
  th_.join();
}

void Reporter::loop() {
  int64_t last_t = now_ms();
  uint64_t last_done = done_->load(std::memory_order_relaxed);
  double ema = 0;
  while (stop_.load(std::memory_order_acquire) == 0) {
    futex_wait(&stop_, 0, interval_ms_);
    const int64_t t = now_ms();
    const uint64_t d = done_->load(std::memory_order_relaxed);
    const double dt = std::max<int64_t>(1, t - last_t) / 1000.0;
    const double inst = d >= last_done ? static_cast<double>(d - last_done) / dt : 0.0;
    ema = ema == 0 ? inst : 0.3 * inst + 0.7 * ema;
    last_t = t;
    last_done = d;
    if (sink_) {
      Event e = make_event(EventKind::Progress, id_, dir_, name_, total_);
      e.done = std::min(d, total_);
      e.verified = std::min(verified_->load(std::memory_order_relaxed), total_);
      e.bps = static_cast<uint64_t>(ema);
      sink_->on_event(e);
    }
  }
}

}  // namespace s64
