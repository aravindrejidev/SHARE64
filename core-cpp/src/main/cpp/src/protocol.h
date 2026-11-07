// SHARE64 wire protocol v1 (little-endian, raw TCP).
//
// Frame header (16 bytes):  magic u32 | version u8 | type u8 | flags u16 | length u32 | aux u32
//   Hello/HelloAck   caps u32, device str
//   Offer            id u64, size u64, chunk u32, mtime_ms i64, name str          (sender -> receiver)
//   Accept           id u64, resume_chunk u64                                      (receiver -> sender)
//   Reject / Error   code u32, message str
//   Chunk            aux = chunk index; payload = data bytes || SHA-256(data)      (sender -> receiver)
//   Ack              aux = chunk index (in order; chunk is verified and on disk)   (receiver -> sender)
//   Done             root hash 32 bytes = SHA-256 over all chunk hashes            (receiver -> sender)
// str = u16 length + UTF-8 bytes.
#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>

#include "share64/types.h"

namespace s64 {
namespace proto {

constexpr size_t kHeaderSize = 16;
constexpr size_t kMaxControl = 2048;

enum class Msg : uint8_t {
  Hello = 1, HelloAck = 2, Offer = 3, Accept = 4, Reject = 5, Chunk = 6, Ack = 7, Done = 8, Error = 9
};

struct Header {
  uint8_t version = 0;
  uint8_t type = 0;
  uint16_t flags = 0;
  uint32_t length = 0;
  uint32_t aux = 0;
};

void put_header(uint8_t out[kHeaderSize], Msg type, uint32_t length, uint32_t aux);

class Writer {
 public:
  void u8(uint8_t v) { put(&v, 1); }
  void u16(uint16_t v) { uint8_t b[2] = {uint8_t(v), uint8_t(v >> 8)}; put(b, 2); }
  void u32(uint32_t v) { for (int i = 0; i < 4; ++i) { uint8_t b = uint8_t(v >> (8 * i)); put(&b, 1); } }
  void u64(uint64_t v) { for (int i = 0; i < 8; ++i) { uint8_t b = uint8_t(v >> (8 * i)); put(&b, 1); } }
  void str(const std::string& s) {
    const size_t n = s.size() < 1024 ? s.size() : 1024;
    u16(static_cast<uint16_t>(n));
    put(s.data(), n);
  }
  bool ok() const { return ok_; }
  const uint8_t* data() const { return buf_; }
  uint32_t size() const { return static_cast<uint32_t>(n_); }

 private:
  void put(const void* p, size_t n) {
    if (n_ + n > sizeof buf_) { ok_ = false; return; }
    std::memcpy(buf_ + n_, p, n);
    n_ += n;
  }
  uint8_t buf_[kMaxControl];
  size_t n_ = 0;
  bool ok_ = true;
};

class Reader {
 public:
  Reader(const uint8_t* p, size_t n) : p_(p), n_(n) {}
  uint8_t u8() { uint8_t v = 0; take(&v, 1); return v; }
  uint16_t u16() { uint8_t b[2] = {0, 0}; take(b, 2); return uint16_t(b[0] | (b[1] << 8)); }
  uint32_t u32() { uint8_t b[4] = {0}; take(b, 4); uint32_t v = 0; for (int i = 3; i >= 0; --i) v = (v << 8) | b[i]; return v; }
  uint64_t u64() { uint8_t b[8] = {0}; take(b, 8); uint64_t v = 0; for (int i = 7; i >= 0; --i) v = (v << 8) | b[i]; return v; }
  std::string str() {
    const size_t n = u16();
    std::string s;
    if (!ok_ || pos_ + n > n_) { ok_ = false; return s; }
    s.assign(reinterpret_cast<const char*>(p_ + pos_), n);
    pos_ += n;
    return s;
  }
  bool ok() const { return ok_; }
  bool at_end() const { return pos_ == n_; }

 private:
  void take(void* out, size_t n) {
    if (!ok_ || pos_ + n > n_) { ok_ = false; return; }
    std::memcpy(out, p_ + pos_, n);
    pos_ += n;
  }
  const uint8_t* p_;
  size_t n_;
  size_t pos_ = 0;
  bool ok_ = true;
};

struct Hello { uint32_t caps = 0; std::string device; };
struct Offer { uint64_t id = 0; uint64_t size = 0; uint32_t chunk = 0; int64_t mtime_ms = 0; std::string name; };
struct Accept { uint64_t id = 0; uint64_t resume = 0; };
struct Fault { Err code = Err::Rejected; std::string msg; };

Err send_frame(int fd, Msg type, uint32_t aux, const uint8_t* payload, uint32_t len,
               const std::atomic<bool>& abort);
Err send_hello(int fd, Msg type, const Hello& h, const std::atomic<bool>& abort);
Err send_offer(int fd, const Offer& o, const std::atomic<bool>& abort);
Err send_accept(int fd, const Accept& a, const std::atomic<bool>& abort);
Err send_fault(int fd, Msg type, Err code, const std::string& msg, const std::atomic<bool>& abort);
Err send_ack(int fd, uint32_t idx, const std::atomic<bool>& abort);
Err send_done(int fd, const uint8_t root[kHashLen], const std::atomic<bool>& abort);

bool decode_hello(const uint8_t* p, size_t n, Hello& out);
bool decode_offer(const uint8_t* p, size_t n, Offer& out);
bool decode_accept(const uint8_t* p, size_t n, Accept& out);
bool decode_fault(const uint8_t* p, size_t n, Fault& out);

// Reads and validates a header (magic + version).
Err read_header(int fd, Header& h, const std::atomic<bool>& abort);
// Reads one control frame of type `want`. A peer Error/Reject frame is returned as its Err code
// (message in *fault_msg). Payload (<= cap) lands in buf.
Err read_expect(int fd, Msg want, Header& h, uint8_t* buf, size_t cap, const std::atomic<bool>& abort,
                std::string* fault_msg);

}  // namespace proto
}  // namespace s64
