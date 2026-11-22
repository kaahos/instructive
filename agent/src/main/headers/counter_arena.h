#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace instructive {

// Storage for per-PC sample counters, claimed only for methods that are sampled, and
// shared with Java as one direct ByteBuffer.
//
// A method's record holds the byte offset of its counters here, or UNCLAIMED until its
// first sample. That sample claims the counters, in the signal handler, by bumping
// `used` with a CAS: no locks and no allocation, and the claimed bytes are zero because
// the region is only ever handed out fresh. Pages are committed as they are touched.
// If the arena is full, the record's offset becomes MISSING instead: the method's
// samples still count towards its total, but its per-PC counts are incomplete.
//
// Nothing is freed individually. clear() empties the whole arena, between sampling
// windows: Sampler::reset_counts suspends claiming, makes sure no signal handler can
// still use an offset, clears, and resumes.
class CounterArena {
public:
    // A record's offset before its first sample.
    static constexpr int32_t UNCLAIMED = -1;
    // A record's offset when its counters couldn't be claimed.
    static constexpr int32_t MISSING = -2;
    static constexpr size_t ALIGNMENT = 8;

    // `capacity` is rounded down to the alignment and capped below 2 GiB, the most a
    // Java ByteBuffer can index.
    explicit CounterArena(size_t capacity);
    ~CounterArena();

    CounterArena(const CounterArena&) = delete;
    CounterArena& operator=(const CounterArena&) = delete;

    bool available() const { return base_ != nullptr; }
    char* base() const { return base_; }
    size_t capacity() const { return capacity_; }
    size_t used() const { return used_.load(std::memory_order_acquire); }

    // The offset of the counters for a record whose offset field is `offset` and that
    // has `length` counters: claimed now if the field is UNCLAIMED. Returns MISSING if
    // they couldn't be claimed, and UNCLAIMED, without claiming, while claiming is
    // suspended. If two threads claim for the same record at once, one claim wins and
    // both get its offset. Async-signal-safe.
    int32_t counters(std::atomic<int32_t>& offset, size_t length);

    // The counters at an offset counters() returned.
    std::atomic<uint16_t>* at(int32_t offset) const {
        return reinterpret_cast<std::atomic<uint16_t>*>(base_ + offset);
    }

    // While suspended, counters() claims nothing. Async-signal-safe to query.
    void suspend() { suspended_.store(true, std::memory_order_seq_cst); }
    void resume() { suspended_.store(false, std::memory_order_seq_cst); }
    bool suspended() const { return suspended_.load(std::memory_order_seq_cst); }

    // Empties the arena: everything claimed is zeroed and its pages released to the OS
    // (madvise), and claiming starts again from the beginning. Only safe while nothing can use an
    // offset claimed before: claiming suspended, every record's offset reset, and no
    // signal handler still holding one. Not async-signal-safe.
    void clear();

private:
    // `bytes` from the arena, or -1 if they don't fit.
    int64_t claim(size_t bytes);

    char* base_ = nullptr;
    size_t capacity_ = 0;
    size_t reserved_ = 0;
    std::atomic<size_t> used_{0};
    std::atomic<bool> suspended_{false};
};

}  // namespace instructive
