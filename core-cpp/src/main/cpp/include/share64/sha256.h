// SHA-256 with ARMv8 Crypto Extensions (runtime-detected and self-tested) and a portable fallback.
#pragma once

#include <cstddef>
#include <cstdint>

namespace s64 {

class Sha256 {
 public:
  Sha256() { reset(); }
  void reset();
  void update(const void* data, size_t len);
  void finish(uint8_t out[32]);

  static void hash(const void* data, size_t len, uint8_t out[32]);
  static void hash_software(const void* data, size_t len, uint8_t out[32]);  // for tests
  static bool hardware_accelerated();

 private:
  uint32_t state_[8];
  uint64_t total_;
  uint8_t buf_[64];
  size_t buf_len_;
};

}  // namespace s64
