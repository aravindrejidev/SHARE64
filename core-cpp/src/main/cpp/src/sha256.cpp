#include "share64/sha256.h"

#include <cstring>

#include "sha256_internal.h"

#if defined(__aarch64__) && (defined(__linux__) || defined(__ANDROID__))
#include <sys/auxv.h>
#ifndef HWCAP_SHA2
#define HWCAP_SHA2 (1UL << 6)
#endif
#endif

namespace s64 {
namespace {

using CompressFn = void (*)(uint32_t*, const uint8_t*, size_t);

inline uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

void compress_sw(uint32_t st[8], const uint8_t* p, size_t nblocks) {
  while (nblocks--) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
      w[i] = (uint32_t(p[4 * i]) << 24) | (uint32_t(p[4 * i + 1]) << 16) |
             (uint32_t(p[4 * i + 2]) << 8) | uint32_t(p[4 * i + 3]);
    }
    for (int i = 16; i < 64; ++i) {
      const uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
      const uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
      w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = st[0], b = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
    for (int i = 0; i < 64; ++i) {
      const uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const uint32_t ch = (e & f) ^ (~e & g);
      const uint32_t t1 = h + S1 + ch + detail::kSha256K[i] + w[i];
      const uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
      const uint32_t t2 = S0 + maj;
      h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    st[0] += a; st[1] += b; st[2] += c; st[3] += d;
    st[4] += e; st[5] += f; st[6] += g; st[7] += h;
    p += 64;
  }
}

const uint32_t kInit[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                           0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

bool cpu_has_sha2() {
#if defined(__aarch64__) && (defined(__linux__) || defined(__ANDROID__))
  return (getauxval(AT_HWCAP) & HWCAP_SHA2) != 0;
#else
  return false;
#endif
}

// Hardware path is only used if it reproduces the software result on multi-block data.
bool arm_selftest() {
  uint8_t data[64 * 3];
  uint32_t x = 0x9e3779b9u;
  for (size_t i = 0; i < sizeof data; ++i) {
    x = x * 1664525u + 1013904223u;
    data[i] = uint8_t(x >> 24);
  }
  uint32_t a[8], b[8];
  std::memcpy(a, kInit, sizeof a);
  std::memcpy(b, kInit, sizeof b);
  compress_sw(a, data, 3);
  detail::sha256_compress_arm(b, data, 3);
  return std::memcmp(a, b, sizeof a) == 0;
}

CompressFn pick() {
  static const CompressFn fn = [] {
    if (detail::arm_sha2_compiled() && cpu_has_sha2() && arm_selftest()) {
      return static_cast<CompressFn>(detail::sha256_compress_arm);
    }
    return static_cast<CompressFn>(compress_sw);
  }();
  return fn;
}

void finish_with(CompressFn fn, uint32_t st[8], uint64_t total, uint8_t* buf, size_t buf_len,
                 uint8_t out[32]) {
  buf[buf_len++] = 0x80;
  if (buf_len > 56) {
    std::memset(buf + buf_len, 0, 64 - buf_len);
    fn(st, buf, 1);
    buf_len = 0;
  }
  std::memset(buf + buf_len, 0, 56 - buf_len);
  const uint64_t bits = total * 8;
  for (int i = 0; i < 8; ++i) buf[56 + i] = uint8_t(bits >> (56 - 8 * i));
  fn(st, buf, 1);
  for (int i = 0; i < 8; ++i) {
    out[4 * i] = uint8_t(st[i] >> 24);
    out[4 * i + 1] = uint8_t(st[i] >> 16);
    out[4 * i + 2] = uint8_t(st[i] >> 8);
    out[4 * i + 3] = uint8_t(st[i]);
  }
}

void hash_with(CompressFn fn, const void* data, size_t len, uint8_t out[32]) {
  uint32_t st[8];
  std::memcpy(st, kInit, sizeof st);
  const uint8_t* p = static_cast<const uint8_t*>(data);
  const size_t full = len / 64;
  if (full) fn(st, p, full);
  uint8_t buf[64];
  const size_t rem = len - full * 64;
  if (rem) std::memcpy(buf, p + full * 64, rem);
  finish_with(fn, st, len, buf, rem, out);
}

}  // namespace

void Sha256::reset() {
  std::memcpy(state_, kInit, sizeof state_);
  total_ = 0;
  buf_len_ = 0;
}

void Sha256::update(const void* data, size_t len) {
  const CompressFn fn = pick();
  const uint8_t* p = static_cast<const uint8_t*>(data);
  total_ += len;
  if (buf_len_) {
    const size_t take = len < 64 - buf_len_ ? len : 64 - buf_len_;
    std::memcpy(buf_ + buf_len_, p, take);
    buf_len_ += take;
    p += take;
    len -= take;
    if (buf_len_ < 64) return;
    fn(state_, buf_, 1);
    buf_len_ = 0;
  }
  const size_t full = len / 64;
  if (full) {
    fn(state_, p, full);
    p += full * 64;
    len -= full * 64;
  }
  if (len) {
    std::memcpy(buf_, p, len);
    buf_len_ = len;
  }
}

void Sha256::finish(uint8_t out[32]) { finish_with(pick(), state_, total_, buf_, buf_len_, out); }

void Sha256::hash(const void* data, size_t len, uint8_t out[32]) { hash_with(pick(), data, len, out); }

void Sha256::hash_software(const void* data, size_t len, uint8_t out[32]) {
  hash_with(compress_sw, data, len, out);
}

bool Sha256::hardware_accelerated() { return pick() != compress_sw; }

}  // namespace s64
