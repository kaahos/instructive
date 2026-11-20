#include "profile_arena.h"

#include <cstring>
#include <new>
#include <sys/mman.h>

namespace instructive {

ProfileArena::ProfileArena(size_t capacity) {
    int flags = MAP_PRIVATE | MAP_ANONYMOUS;
#if defined(MAP_NORESERVE)
    flags |= MAP_NORESERVE;
#endif
    void* region = mmap(nullptr, capacity, PROT_READ | PROT_WRITE, flags, -1, 0);
    if (region != MAP_FAILED) {
        base_ = static_cast<char*>(region);
        capacity_ = capacity;
    }
}

ProfileArena::~ProfileArena() {
    if (base_ != nullptr) {
        munmap(base_, capacity_);
    }
}

CompiledMethod* ProfileArena::allocate(size_t profile_length, size_t name_length) {
    if (base_ == nullptr || profile_length > CompiledMethod::MAX_LENGTH || name_length > CompiledMethod::MAX_LENGTH) {
        return nullptr;
    }
    size_t size = CompiledMethod::block_size(profile_length, name_length);
    uint32_t lengths = CompiledMethod::pack_lengths(profile_length, name_length);
    CompiledMethod* method = nullptr;
    {
        std::lock_guard<std::mutex> guard(lock_);
        auto& free_list = free_lists_[size];
        if (!free_list.empty()) {
            method = free_list.back();
            free_list.pop_back();
        } else {
            size_t used = used_.load(std::memory_order_relaxed);
            if (size > capacity_ - used) {
                return nullptr;
            }
            // Construct the header once, when the block is carved; reuse only rewrites it,
            // so its atomics stay constructed and generation keeps counting. Its lengths
            // must be in place before Java can see the block in [0, used), since they give
            // the block's length.
            method = new (base_ + used) CompiledMethod();
            method->lengths.store(lengths, std::memory_order_relaxed);
            used_.store(used + size, std::memory_order_release);
        }
    }
    // Odd generation: being written. Java ignores the block until it is even again.
    method->generation.fetch_add(1, std::memory_order_acq_rel);
    std::atomic_thread_fence(std::memory_order_release);
    method->state.store(CompiledMethod::FREE, std::memory_order_relaxed);
    method->count.store(0, std::memory_order_relaxed);
    // A reused block keeps its size class, so a reader deriving the length from either
    // the old or the new lengths gets the same answer.
    method->lengths.store(lengths, std::memory_order_relaxed);
    std::memset(reinterpret_cast<char*>(method) + CompiledMethod::HEADER_SIZE, 0, size - CompiledMethod::HEADER_SIZE);
    return method;
}

void ProfileArena::publish(CompiledMethod* method) {
    method->generation.fetch_add(1, std::memory_order_release);
}

void ProfileArena::free(CompiledMethod* method) {
    method->state.store(CompiledMethod::FREE, std::memory_order_release);
    std::lock_guard<std::mutex> guard(lock_);
    free_lists_[method->block_length()].push_back(method);
}

}  // namespace instructive
