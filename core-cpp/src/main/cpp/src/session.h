#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

#include "share64/types.h"
#include "sync.h"

namespace s64 {

// Abort/failure state shared by every thread of one connection attempt. First error wins.
class SessionCtl {
 public:
  void reset();  // before each attempt (a user cancel is sticky and survives reset)
  void bind(int sock, SlotPool* pool);
  void unbind() { bind(-1, nullptr); }
  bool fail(Err e, const std::string& msg = std::string());  // wakes every waiter, kills the socket
  void request_cancel();                                     // user cancel: sticky
  bool cancel_requested() const { return cancel_.load(std::memory_order_acquire); }
  bool aborted() const { return abort_.load(std::memory_order_acquire); }
  const std::atomic<bool>& abort_flag() const { return abort_; }
  Err err() const { return static_cast<Err>(err_.load(std::memory_order_acquire)); }
  std::string message();

 private:
  std::atomic<bool> abort_{false};
  std::atomic<bool> cancel_{false};
  std::atomic<int> err_{0};
  std::mutex mu_;
  int sock_ = -1;
  SlotPool* pool_ = nullptr;
  std::string msg_;
};

Event make_event(EventKind kind, uint64_t id, Direction dir, const std::string& name, uint64_t total);

// Emits throttled Progress events from its own thread (never from the streaming loop).
class Reporter {
 public:
  Reporter(EventSink* sink, const Config& cfg, uint64_t id, Direction dir, std::string name,
           uint64_t total, const std::atomic<uint64_t>* done, const std::atomic<uint64_t>* verified);
  ~Reporter() { stop(); }
  void start();
  void stop();

 private:
  void loop();
  EventSink* sink_;
  int interval_ms_;
  uint64_t id_;
  Direction dir_;
  std::string name_;
  uint64_t total_;
  const std::atomic<uint64_t>* done_;
  const std::atomic<uint64_t>* verified_;
  std::atomic<uint32_t> stop_{0};
  std::thread th_;
};

}  // namespace s64
