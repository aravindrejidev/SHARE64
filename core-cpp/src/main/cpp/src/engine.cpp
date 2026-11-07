#include "share64/engine.h"

#include <fcntl.h>
#include <unistd.h>

#include <algorithm>

#include "receiver.h"
#include "sender.h"

namespace s64 {
namespace {

Config sanitized(Config c) {
  c.chunk_size = std::min(std::max(c.chunk_size, kMinChunkSize), kMaxChunkSize);
  c.slots = std::min<uint32_t>(std::max<uint32_t>(c.slots, 2), 16);
  c.workers = std::min<uint32_t>(std::max<uint32_t>(c.workers, 1), 8);
  c.max_sessions = std::max<uint32_t>(c.max_sessions, 1);
  c.io_timeout_ms = std::max(c.io_timeout_ms, 1000);
  c.connect_timeout_ms = std::max(c.connect_timeout_ms, 500);
  c.done_timeout_ms = std::max(c.done_timeout_ms, 1000);
  c.retry_base_ms = std::max(c.retry_base_ms, 50);
  c.retry_max_ms = std::max(c.retry_max_ms, c.retry_base_ms);
  if (c.device_name.size() > 64) c.device_name.resize(64);
  return c;
}

std::string base_name(const std::string& p) {
  const size_t i = p.find_last_of('/');
  return i == std::string::npos ? p : p.substr(i + 1);
}

}  // namespace

Engine::Engine(const Config& cfg, EventSink* sink)
    : cfg_(sanitized(cfg)), rx_(new Receiver(cfg_, sink)), tx_(new Sender(cfg_, sink)) {}

Engine::~Engine() {
  tx_->cancel_all();
  rx_->stop();
}

Err Engine::start_receiver(const std::string& save_dir, uint16_t port, uint16_t* bound) {
  return rx_->start(save_dir, port, bound);
}

void Engine::stop_receiver() { rx_->stop(); }

uint64_t Engine::send_file(const std::string& host, uint16_t port, const std::string& path,
                           const std::string& display_name, const std::string& peer_key) {
  const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
  return send_fd(host, port, fd, display_name.empty() ? base_name(path) : display_name, peer_key);
}

uint64_t Engine::send_fd(const std::string& host, uint16_t port, int fd, const std::string& display_name,
                         const std::string& peer_key) {
  return tx_->send(host, port, fd, display_name, peer_key);
}

void Engine::respond(uint64_t id, bool accept) { rx_->respond(id, accept); }

void Engine::cancel(uint64_t id) {
  tx_->cancel(id);
  rx_->cancel(id);
}

}  // namespace s64
