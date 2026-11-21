#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "compiled_method.h"

namespace instructive {

// Storage for CompiledMethod records, shared with Java as one direct ByteBuffer.
//
// The arena is a single region reserved up front; pages are only committed as they
// are touched. Blocks are carved from it by bumping `used`, so [0, used) is always a
// sequence of blocks that Java can walk, deriving each block's length from its lengths
// word (CompiledMethod::block_size). Blocks are sized by size class and a freed block
// goes on its class's free list, only to be reused for a record of the same class, so
// the derived length never changes and churn doesn't grow the arena.
//
// allocate() and free() take a lock and are not async-signal-safe. Freeing a block
// that signal handlers may still reach must wait until they can't (see
// CompiledMethodMap::synchronize).
class ProfileArena {
public:
    // Per-PC counters dominate the space, and on AArch64 there are a quarter as many.
    static constexpr size_t DEFAULT_CAPACITY = (size_t{512} << 20) >> CompiledMethod::PC_SHIFT;

    explicit ProfileArena(size_t capacity = DEFAULT_CAPACITY);
    ~ProfileArena();

    ProfileArena(const ProfileArena&) = delete;
    ProfileArena& operator=(const ProfileArena&) = delete;

    bool available() const { return base_ != nullptr; }
    char* base() const { return base_; }
    size_t capacity() const { return capacity_; }
    // The extent of the carved blocks; Java walks [0, used()).
    size_t used() const { return used_.load(std::memory_order_acquire); }

    // A block for a record with these variable parts (each at most
    // CompiledMethod::MAX_LENGTH), with its lengths set, counts and name zeroed, state
    // FREE and an odd generation: the caller fills in the rest and then calls
    // publish(). Returns null when the arena is full.
    CompiledMethod* allocate(size_t profile_length, size_t name_length);

    // Marks a filled-in block as stable (even generation).
    static void publish(CompiledMethod* method);

    // Returns a block to its free list. Its state becomes FREE; its contents stay
    // readable until the block is reused.
    void free(CompiledMethod* method);

private:
    char* base_ = nullptr;
    size_t capacity_ = 0;
    std::atomic<size_t> used_{0};
    std::mutex lock_;
    std::unordered_map<size_t, std::vector<CompiledMethod*>> free_lists_;
};

}  // namespace instructive
