// SHARE64 core engine - public types.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace s64 {

constexpr uint32_t kMagic = 0x34365348u;  // bytes "SH64" on the wire (little-endian)
constexpr uint8_t kProtoVersion = 1;
constexpr uint32_t kDefaultChunkSize = 2u * 1024u * 1024u;
constexpr uint32_t kMinChunkSize = 64u * 1024u;
constexpr uint32_t kMaxChunkSize = 16u * 1024u * 1024u;
constexpr uint64_t kMaxChunks = 1ull << 22;  // bounds the resume hash table (128 MB worst case)
constexpr size_t kHashLen = 32;
constexpr uint16_t kDefaultPort = 52364;

enum class Direction : int { Send = 0, Receive = 1 };

enum class EventKind : int {
  Started = 0,        // connection accepted, streaming begins (done = resume offset)
  Progress = 1,       // throttled progress tick
  Retrying = 2,       // sender: link lost, will reconnect and resume
  Completed = 3,      // verified and finalized. name = final path (receiver), message = root hash hex
  Failed = 4,         // terminal failure (sender)
  Cancelled = 5,      // terminal cancel
  IncomingOffer = 6,  // receiver: waiting for accept/decline. name = file, message = sender device
  Listening = 7,      // receiver is listening (done = port)
  Interrupted = 8     // receiver: link lost, partial data kept for resume
};

enum class Err : int {
  Ok = 0,
  Io = 1,
  Protocol = 2,
  HashMismatch = 3,
  Rejected = 4,
  Cancelled = 5,
  DiskFull = 6,
  Timeout = 7,
  Busy = 8,
  BadName = 9,
  Version = 10,
  NotFound = 11,
  Internal = 12
};

inline bool is_retryable(Err e) {
  return e == Err::Io || e == Err::Timeout || e == Err::HashMismatch || e == Err::Busy;
}

const char* err_name(Err e);

struct Config {
  uint32_t chunk_size = kDefaultChunkSize;  // sender decides; receiver follows the offer
  uint32_t slots = 4;                       // in-flight chunks (= window), each chunk_size bytes
  uint32_t workers = 2;                     // disk-read / hash-verify-write workers per transfer
  int connect_timeout_ms = 5000;
  int io_timeout_ms = 15000;                // no-progress timeout on a socket
  int done_timeout_ms = 60000;              // sender waits this long for receiver finalize
  int offer_timeout_ms = 60000;             // how long a user may take to accept
  int progress_interval_ms = 100;
  int max_retries = 60;                     // consecutive attempts without progress
  int retry_base_ms = 400;
  int retry_max_ms = 4000;
  uint32_t max_sessions = 8;                // concurrent incoming connections
  bool auto_accept = false;
  std::string device_name = "SHARE64";
};

struct Event {
  EventKind kind = EventKind::Progress;
  uint64_t transfer_id = 0;
  Direction dir = Direction::Send;
  Err err = Err::Ok;
  int attempt = 0;
  uint64_t total = 0;
  uint64_t done = 0;      // bytes on the wire (smooth progress)
  uint64_t verified = 0;  // bytes confirmed by SHA-256 + receiver ACK
  uint64_t bps = 0;
  std::string name;
  std::string message;
};

// Called from native worker threads. Must be thread-safe and must not block.
class EventSink {
 public:
  virtual ~EventSink() = default;
  virtual void on_event(const Event& e) = 0;
};

}  // namespace s64
