// Futex-based wait/wake and the slot ring used by both the sender and receiver pipelines.
//
// Each slot owns one 32-bit "state" word that only ever increases:
//     4*round + 0  FREE  (ready to be filled for the chunk of this round)
//     4*round + 1  FULL  (chunk bytes + SHA-256 present)
//     4*round + 2  DONE  (chunk consumed: sent / verified+written)
//     4*(round+1)  FREE  for the next round (set after the peer ACK / ack send)
// Because the word is monotonic, waiting is "state >= target": no ABA, no lost wakeups, no mutex.
#pragma once

#include <linux/futex.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <ctime>
#include <new>

#include "share64/types.h"

namespace s64 {

static_assert(sizeof(std::atomic<uint32_t>) == sizeof(uint32_t), "futex needs a plain 32-bit word");
static_assert(std::atomic<uint32_t>::is_always_lock_free, "atomics must be lock-free");

inline void cpu_relax() {
#if defined(__aarch64__) || defined(__arm__)
  asm volatile("yield" ::: "memory");
#elif defined(__x86_64__) || defined(__i386__)
  asm volatile("pause" ::: "memory");
#endif
}

inline void futex_wait(std::atomic<uint32_t>* w, uint32_t expected, int timeout_ms) {
  timespec ts;
  ts.tv_sec = timeout_ms / 1000;
  ts.tv_nsec = static_cast<long>(timeout_ms % 1000) * 1000000L;
  syscall(SYS_futex, reinterpret_cast<uint32_t*>(w), FUTEX_WAIT_PRIVATE, expected, &ts, nullptr, 0);
}

inline void futex_wake_all(std::atomic<uint32_t>* w) {
  syscall(SYS_futex, reinterpret_cast<uint32_t*>(w), FUTEX_WAKE_PRIVATE, INT_MAX, nullptr, nullptr, 0);
}

// Blocks until w >= target. Returns false only if `abort` was raised while the target was unmet.
inline bool wait_ge(std::atomic<uint32_t>& w, uint32_t target, const std::atomic<bool>& abort) {
  for (int i = 0; i < 100; ++i) {
    if (w.load(std::memory_order_acquire) >= target) return true;
    cpu_relax();
  }
  for (;;) {
    const uint32_t v = w.load(std::memory_order_acquire);
    if (v >= target) return true;
    if (abort.load(std::memory_order_acquire)) return false;
    futex_wait(&w, v, 200);  // timeout is only a safety net; wakeups are explicit
  }
}

enum SlotPhase : uint32_t { kFree = 0, kFull = 1, kDone = 2 };

inline uint32_t slot_target(uint64_t rel, uint32_t slots, SlotPhase phase) {
  return 4u * static_cast<uint32_t>(rel / slots) + phase;
}

struct Slot {
  std::atomic<uint32_t> state{0};
  uint32_t len = 0;
  uint8_t hash[kHashLen] = {0};
  uint8_t* buf = nullptr;
};

// Fixed pool: all memory is allocated once, before streaming starts.
class SlotPool {
 public:
  SlotPool() = default;
  SlotPool(const SlotPool&) = delete;
  SlotPool& operator=(const SlotPool&) = delete;
  ~SlotPool() {
    if (slots_) {
      for (uint32_t i = 0; i < n_; ++i) slots_[i].~Slot();
      std::free(slots_);
    }
    std::free(mem_);
  }

  bool init(uint32_t n, size_t buf_size) {
    if (n == 0 || buf_size == 0) return false;
    buf_size = (buf_size + 4095) & ~static_cast<size_t>(4095);
    void* m = nullptr;
    if (posix_memalign(&m, 4096, buf_size * n) != 0) return false;
    mem_ = static_cast<uint8_t*>(m);
    void* s = nullptr;
    if (posix_memalign(&s, 64, sizeof(Slot) * n) != 0) return false;
    slots_ = static_cast<Slot*>(s);
    n_ = n;
    for (uint32_t i = 0; i < n; ++i) {
      new (&slots_[i]) Slot();
      slots_[i].buf = mem_ + i * buf_size;
    }
    return true;
  }

  uint32_t size() const { return n_; }
  Slot& at(uint64_t rel) { return slots_[rel % n_]; }

  void wake_all() {
    for (uint32_t i = 0; i < n_; ++i) futex_wake_all(&slots_[i].state);
    futex_wake_all(&seq);
  }

  std::atomic<uint32_t> seq{0};  // sender: number of chunks put on the wire in this attempt

 private:
  Slot* slots_ = nullptr;
  uint8_t* mem_ = nullptr;
  uint32_t n_ = 0;
};

}  // namespace s64
