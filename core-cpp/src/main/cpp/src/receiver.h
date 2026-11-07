#pragma once

#include <atomic>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "session.h"
#include "share64/types.h"

namespace s64 {

class Receiver {
 public:
  Receiver(const Config& cfg, EventSink* sink) : cfg_(cfg), sink_(sink) {}
  ~Receiver() { stop(); }

  // port 0 = ephemeral. Incoming files are finalized into `dir`; partials live in dir/.share64.
  Err start(const std::string& dir, uint16_t port, uint16_t* bound);
  void stop();
  void respond(uint64_t id, bool accept);  // answer an IncomingOffer event
  void cancel(uint64_t id);                // user cancel: deletes the partial, rejects reconnects

 private:
  struct Session;
  struct ServeState;

  void accept_loop();
  void run_session(Session& s);
  Err serve(Session& s, ServeState& st);
  void reap();
  void emit(const Event& e) { if (sink_ && !stopping_.load()) sink_->on_event(e); }

  Config cfg_;
  EventSink* sink_;
  std::string dir_;
  int lfd_ = -1;
  std::atomic<bool> stop_{true};
  std::atomic<bool> stopping_{false};
  std::thread accept_th_;
  std::mutex mu_;
  std::vector<std::shared_ptr<Session>> sessions_;
  std::map<uint64_t, int64_t> cancelled_;  // transfer id -> time (one-shot reject marker)
};

}  // namespace s64
