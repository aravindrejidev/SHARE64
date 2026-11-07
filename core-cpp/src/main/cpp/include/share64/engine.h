// SHARE64 core engine facade: one Engine = one receiver listener + any number of outgoing transfers.
#pragma once

#include <memory>
#include <string>

#include "share64/types.h"

namespace s64 {

class Receiver;
class Sender;

class Engine {
 public:
  Engine(const Config& cfg, EventSink* sink);
  ~Engine();
  Engine(const Engine&) = delete;
  Engine& operator=(const Engine&) = delete;

  // Start listening. port 0 = ephemeral; the bound port is returned through *bound.
  Err start_receiver(const std::string& save_dir, uint16_t port, uint16_t* bound);
  void stop_receiver();

  // Sender. Returns the transfer id, or 0 on immediate failure (a Failed event is emitted).
  // peer_key: stable id of the target device (e.g. NSD service name) so resume survives IP changes.
  uint64_t send_file(const std::string& host, uint16_t port, const std::string& path,
                     const std::string& display_name, const std::string& peer_key);
  // Takes ownership of fd (e.g. ParcelFileDescriptor.detachFd()).
  uint64_t send_fd(const std::string& host, uint16_t port, int fd, const std::string& display_name,
                   const std::string& peer_key);

  void respond(uint64_t id, bool accept);  // answer an IncomingOffer
  void cancel(uint64_t id);                // works for both directions

 private:
  Config cfg_;
  std::unique_ptr<Receiver> rx_;
  std::unique_ptr<Sender> tx_;
};

}  // namespace s64
