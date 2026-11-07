// ARMv8 SHA-256 block function. Compiled with -march=armv8-a+crypto (see CMakeLists.txt).
// Selection is done at runtime in sha256.cpp (HWCAP_SHA2) and guarded by a self-test.
#include "sha256_internal.h"

#if defined(__aarch64__) && defined(__ARM_FEATURE_SHA2)
#include <arm_neon.h>

namespace s64 {
namespace detail {

bool arm_sha2_compiled() { return true; }

void sha256_compress_arm(uint32_t state[8], const uint8_t* data, size_t nblocks) {
  uint32x4_t s0 = vld1q_u32(&state[0]);  // a b c d
  uint32x4_t s1 = vld1q_u32(&state[4]);  // e f g h
  while (nblocks--) {
    const uint32x4_t abcd_save = s0;
    const uint32x4_t efgh_save = s1;
    uint32x4_t w[4];
    for (int j = 0; j < 4; ++j) {
      w[j] = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(data + 16 * j)));
    }
    for (int i = 0; i < 16; ++i) {
      const uint32x4_t wk = vaddq_u32(w[i & 3], vld1q_u32(&kSha256K[4 * i]));
      const uint32x4_t prev = s0;
      s0 = vsha256hq_u32(s0, s1, wk);
      s1 = vsha256h2q_u32(s1, prev, wk);
      if (i < 12) {
        w[i & 3] = vsha256su1q_u32(vsha256su0q_u32(w[i & 3], w[(i + 1) & 3]), w[(i + 2) & 3],
                                   w[(i + 3) & 3]);
      }
    }
    s0 = vaddq_u32(s0, abcd_save);
    s1 = vaddq_u32(s1, efgh_save);
    data += 64;
  }
  vst1q_u32(&state[0], s0);
  vst1q_u32(&state[4], s1);
}

}  // namespace detail
}  // namespace s64

#else

namespace s64 {
namespace detail {
bool arm_sha2_compiled() { return false; }
void sha256_compress_arm(uint32_t*, const uint8_t*, size_t) {}
}  // namespace detail
}  // namespace s64

#endif
