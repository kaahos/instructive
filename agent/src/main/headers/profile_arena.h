#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

#include "compiled_method.h"

namespace instructive {

// Storage for CompiledMethod records, shared with Java as one direct ByteBuffer.
//
// The arena is a single region reserved up front; pages are only committed as they
// are touched. Every record is CompiledMethod::SIZE bytes, carved by bumping `used`, so
// [0, used) is always a sequence of records that Java walks with a fixed stride. A freed
// block goes on a free list and is reused for the next record, so churn doesn't grow
// the arena.
//
// allocate() and free() take a lock and are not async-signal-safe. Freeing a block
// that signal handlers may still reach must wait until they can't (see
// CompiledMethodMap::synchronize).
class ProfileArena {
public:
    // 524288 records: the code cache holds far fewer methods.
    static constexpr size_t DEFAULT_CAPACITY = size_t{32} << 20;

    explicit ProfileArena(size_t capacity = DEFAULT_CAPACITY);
    ~ProfileArena();

    ProfileArena(const ProfileArena&) = delete;
    ProfileArena& operator=(const ProfileArena&) = delete;

    bool available() const { return base_ != nullptr; }
    char* base() const { return base_; }
    size_t capacity() const { return capacity_; }
    // The extent of the carved blocks; Java walks [0, used()).
    size_t used() const { return used_.load(std::memory_order_acquire); }

    // A block for a record with this many counters (at most CompiledMethod::MAX_LENGTH),
    // with its length set, no counters claimed, state FREE and an odd generation: the
    // caller fills in the rest and then calls publish(). Returns null when the arena is
    // full.
    CompiledMethod* allocate(size_t profile_length);

    // Marks a filled-in block as stable (even generation).
    static void publish(CompiledMethod* method);

    // Returns a block to the free list. Its state becomes FREE; its contents stay
    // readable until the block is reused.
    void free(CompiledMethod* method);

private:
    char* base_ = nullptr;
    size_t capacity_ = 0;
    std::atomic<size_t> used_{0};
    std::mutex lock_;
    std::vector<CompiledMethod*> free_list_;
};

}  // namespace instructive
