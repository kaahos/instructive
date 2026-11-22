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

CompiledMethod* ProfileArena::allocate(size_t profile_length) {
    if (base_ == nullptr || profile_length > CompiledMethod::MAX_LENGTH) {
        return nullptr;
    }
    CompiledMethod* method = nullptr;
    {
        std::lock_guard<std::mutex> guard(lock_);
        if (!free_list_.empty()) {
            method = free_list_.back();
            free_list_.pop_back();
        } else {
            size_t used = used_.load(std::memory_order_relaxed);
            if (CompiledMethod::SIZE > capacity_ - used) {
                return nullptr;
            }
            // Construct the record once, when the block is carved; reuse only rewrites it,
            // so its atomics stay constructed and generation keeps counting.
            method = new (base_ + used) CompiledMethod();
            used_.store(used + CompiledMethod::SIZE, std::memory_order_release);
        }
    }
    // Odd generation: being written. Java ignores the block until it is even again.
    method->generation.fetch_add(1, std::memory_order_acq_rel);
    std::atomic_thread_fence(std::memory_order_release);
    method->state.store(CompiledMethod::FREE, std::memory_order_relaxed);
    method->count.store(0, std::memory_order_relaxed);
    method->pc_shift = CompiledMethod::PC_SHIFT;
    method->profile_length_.store(static_cast<uint32_t>(profile_length), std::memory_order_relaxed);
    method->counter_offset.store(CounterArena::UNCLAIMED, std::memory_order_relaxed);
    return method;
}

void ProfileArena::publish(CompiledMethod* method) {
    method->generation.fetch_add(1, std::memory_order_release);
}

void ProfileArena::free(CompiledMethod* method) {
    method->state.store(CompiledMethod::FREE, std::memory_order_release);
    std::lock_guard<std::mutex> guard(lock_);
    free_list_.push_back(method);
}

}  // namespace instructive
