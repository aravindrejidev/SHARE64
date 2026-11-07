#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "session.h"
#include "share64/types.h"

namespace s64 {

struct SendJob {
  uint64_t id = 0;
  std::string host;
  std::string name;
  uint16_t port = 0;
  int file_fd = -1;  // owned; closed when the job ends
  uint64_t size = 0;
  int64_t mtime_ms = 0;
  Config cfg;
  EventSink* sink = nullptr;
  SessionCtl ctl;
  std::atomic<uint64_t> done_bytes{0};      // absolute, wire level
  std::atomic<uint64_t> verified_bytes{0};  // absolute, receiver-confirmed
  uint8_t root[kHashLen] = {0};
  std::thread thread;
  std::atomic<bool> finished{false};
};

// One connection attempt: connect, handshake, resume negotiation, stream, wait for DONE.
Err run_send_attempt(SendJob& job);

class Sender {
 public:
  Sender(const Config& cfg, EventSink* sink) : cfg_(cfg), sink_(sink) {}
  ~Sender() { cancel_all(); }

  // Takes ownership of file_fd. Returns the (deterministic) transfer id, or 0 on immediate failure.
  uint64_t send(const std::string& host, uint16_t port, int file_fd, const std::string& name,
                const std::string& peer_key);
  void cancel(uint64_t id);
  void cancel_all();  // cancels and joins everything

 private:
  void run_job(SendJob& j);
  void reap();
  void emit(const Event& e) { if (sink_) sink_->on_event(e); }

  Config cfg_;
  EventSink* sink_;
  std::mutex mu_;
  std::vector<std::unique_ptr<SendJob>> jobs_;
};

}  // namespace s64
