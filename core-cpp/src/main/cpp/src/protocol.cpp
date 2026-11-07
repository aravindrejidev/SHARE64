#include "protocol.h"

#include "net.h"
#include "util.h"

namespace s64 {
namespace proto {

void put_header(uint8_t out[kHeaderSize], Msg type, uint32_t length, uint32_t aux) {
  put_le32(out, kMagic);
  out[4] = kProtoVersion;
  out[5] = static_cast<uint8_t>(type);
  out[6] = 0;
  out[7] = 0;
  put_le32(out + 8, length);
  put_le32(out + 12, aux);
}

Err send_frame(int fd, Msg type, uint32_t aux, const uint8_t* payload, uint32_t len,
               const std::atomic<bool>& abort) {
  uint8_t h[kHeaderSize];
  put_header(h, type, len, aux);
  Err e = net::send_all(fd, h, sizeof h, abort, len > 0);
  if (e != Err::Ok || len == 0) return e;
  return net::send_all(fd, payload, len, abort);
}

Err send_hello(int fd, Msg type, const Hello& h, const std::atomic<bool>& abort) {
  Writer w;
  w.u32(h.caps);
  w.str(h.device);
  if (!w.ok()) return Err::Internal;
  return send_frame(fd, type, 0, w.data(), w.size(), abort);
}

Err send_offer(int fd, const Offer& o, const std::atomic<bool>& abort) {
  Writer w;
  w.u64(o.id);
  w.u64(o.size);
  w.u32(o.chunk);
  w.u64(static_cast<uint64_t>(o.mtime_ms));
  w.str(o.name);
  if (!w.ok()) return Err::Internal;
  return send_frame(fd, Msg::Offer, 0, w.data(), w.size(), abort);
}

Err send_accept(int fd, const Accept& a, const std::atomic<bool>& abort) {
  Writer w;
  w.u64(a.id);
  w.u64(a.resume);
  return send_frame(fd, Msg::Accept, 0, w.data(), w.size(), abort);
}

Err send_fault(int fd, Msg type, Err code, const std::string& msg, const std::atomic<bool>& abort) {
  Writer w;
  w.u32(static_cast<uint32_t>(code));
  w.str(msg);
  if (!w.ok()) return Err::Internal;
  return send_frame(fd, type, 0, w.data(), w.size(), abort);
}

Err send_ack(int fd, uint32_t idx, const std::atomic<bool>& abort) {
  return send_frame(fd, Msg::Ack, idx, nullptr, 0, abort);
}

Err send_done(int fd, const uint8_t root[kHashLen], const std::atomic<bool>& abort) {
  return send_frame(fd, Msg::Done, 0, root, kHashLen, abort);
}

bool decode_hello(const uint8_t* p, size_t n, Hello& out) {
  Reader r(p, n);
  out.caps = r.u32();
  out.device = r.str();
  return r.ok();
}

bool decode_offer(const uint8_t* p, size_t n, Offer& out) {
  Reader r(p, n);
  out.id = r.u64();
  out.size = r.u64();
  out.chunk = r.u32();
  out.mtime_ms = static_cast<int64_t>(r.u64());
  out.name = r.str();
  return r.ok();
}

bool decode_accept(const uint8_t* p, size_t n, Accept& out) {
  Reader r(p, n);
  out.id = r.u64();
  out.resume = r.u64();
  return r.ok();
}

bool decode_fault(const uint8_t* p, size_t n, Fault& out) {
  Reader r(p, n);
  const uint32_t c = r.u32();
  out.msg = r.str();
  out.code = (c >= 1 && c <= static_cast<uint32_t>(Err::Internal)) ? static_cast<Err>(c) : Err::Rejected;
  return r.ok();
}

Err read_header(int fd, Header& h, const std::atomic<bool>& abort) {
  uint8_t b[kHeaderSize];
  Err e = net::recv_all(fd, b, sizeof b, abort);
  if (e != Err::Ok) return e;
  if (get_le32(b) != kMagic) return Err::Protocol;
  h.version = b[4];
  h.type = b[5];
  h.flags = static_cast<uint16_t>(b[6] | (b[7] << 8));
  h.length = get_le32(b + 8);
  h.aux = get_le32(b + 12);
  if (h.version != kProtoVersion) return Err::Version;
  return Err::Ok;
}

Err read_expect(int fd, Msg want, Header& h, uint8_t* buf, size_t cap, const std::atomic<bool>& abort,
                std::string* fault_msg) {
  Err e = read_header(fd, h, abort);
  if (e != Err::Ok) return e;
  if (h.length > cap) return Err::Protocol;
  if (h.length) {
    e = net::recv_all(fd, buf, h.length, abort);
    if (e != Err::Ok) return e;
  }
  if (h.type == static_cast<uint8_t>(Msg::Error) || h.type == static_cast<uint8_t>(Msg::Reject)) {
    Fault f;
    decode_fault(buf, h.length, f);
    if (fault_msg) *fault_msg = f.msg;
    return f.code;
  }
  if (h.type != static_cast<uint8_t>(want)) return Err::Protocol;
  return Err::Ok;
}

}  // namespace proto
}  // namespace s64
