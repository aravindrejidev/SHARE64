#pragma once

#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstdint>
#include <string>

namespace s64 {

inline int64_t now_ms() {
  using namespace std::chrono;
  return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// Reads exactly n bytes at offset. False on error or short file.
inline bool pread_full(int fd, void* buf, size_t n, uint64_t off) {
  uint8_t* p = static_cast<uint8_t*>(buf);
  while (n) {
    const ssize_t r = ::pread(fd, p, n, static_cast<off_t>(off));
    if (r > 0) {
      p += r;
      n -= static_cast<size_t>(r);
      off += static_cast<uint64_t>(r);
    } else if (r == 0) {
      return false;
    } else if (errno != EINTR) {
      return false;
    }
  }
  return true;
}

// Writes exactly n bytes at offset. Returns 0 or the errno value.
inline int pwrite_full(int fd, const void* buf, size_t n, uint64_t off) {
  const uint8_t* p = static_cast<const uint8_t*>(buf);
  while (n) {
    const ssize_t r = ::pwrite(fd, p, n, static_cast<off_t>(off));
    if (r > 0) {
      p += r;
      n -= static_cast<size_t>(r);
      off += static_cast<uint64_t>(r);
    } else if (r < 0 && errno == EINTR) {
      continue;
    } else {
      return r < 0 ? errno : EIO;
    }
  }
  return 0;
}

inline void put_le32(uint8_t* p, uint32_t v) {
  for (int i = 0; i < 4; ++i) p[i] = uint8_t(v >> (8 * i));
}
inline void put_le64(uint8_t* p, uint64_t v) {
  for (int i = 0; i < 8; ++i) p[i] = uint8_t(v >> (8 * i));
}
inline uint32_t get_le32(const uint8_t* p) {
  uint32_t v = 0;
  for (int i = 3; i >= 0; --i) v = (v << 8) | p[i];
  return v;
}
inline uint64_t get_le64(const uint8_t* p) {
  uint64_t v = 0;
  for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
  return v;
}

inline std::string to_hex(const uint8_t* d, size_t n) {
  static const char* k = "0123456789abcdef";
  std::string s;
  s.reserve(n * 2);
  for (size_t i = 0; i < n; ++i) {
    s.push_back(k[d[i] >> 4]);
    s.push_back(k[d[i] & 15]);
  }
  return s;
}

}  // namespace s64
