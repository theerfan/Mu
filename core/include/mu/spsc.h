// Lock-free single-producer / single-consumer queues (fixed capacity, power of two, no allocation after
// construction). Producer and consumer may run on different threads; each side touches only its own index
// plus an acquire load of the other's.
#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>
namespace mu {

template <typename T>
class SpscQueue {
 public:
  explicit SpscQueue(size_t capacityPow2) : buf_(capacityPow2), mask_(capacityPow2 - 1) {}
  // Producer side. Returns the number of items written (fewer if full; the rest is dropped and counted).
  size_t push(const T* items, size_t n) {
    const uint64_t w = w_.load(std::memory_order_relaxed), r = r_.load(std::memory_order_acquire);
    size_t space = buf_.size() - (size_t)(w - r), k = n < space ? n : space;
    for (size_t i = 0; i < k; ++i) buf_[(w + i) & mask_] = items[i];
    w_.store(w + k, std::memory_order_release);
    if (k < n) dropped_.fetch_add(n - k, std::memory_order_relaxed);
    return k;
  }
  bool push(const T& item) { return push(&item, 1) == 1; }
  // Consumer side.
  size_t pop(T* out, size_t max) {
    const uint64_t r = r_.load(std::memory_order_relaxed), w = w_.load(std::memory_order_acquire);
    size_t avail = (size_t)(w - r), k = max < avail ? max : avail;
    for (size_t i = 0; i < k; ++i) out[i] = buf_[(r + i) & mask_];
    r_.store(r + k, std::memory_order_release);
    return k;
  }
  size_t size() const { return (size_t)(w_.load(std::memory_order_acquire) - r_.load(std::memory_order_acquire)); }
  size_t capacity() const { return buf_.size(); }
  uint64_t dropped() const { return dropped_.load(std::memory_order_relaxed); }
  // Only when neither side is active.
  void clear() { r_.store(w_.load()); }
 private:
  std::vector<T> buf_; size_t mask_;
  alignas(64) std::atomic<uint64_t> w_{0};
  alignas(64) std::atomic<uint64_t> r_{0};
  std::atomic<uint64_t> dropped_{0};
};

}  // namespace mu
