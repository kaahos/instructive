#include "counter_arena.h"

#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

namespace instructive {

namespace {

constexpr size_t MAX_CAPACITY = (size_t{1} << 31) - CounterArena::ALIGNMENT;

int map_flags() {
    int flags = MAP_PRIVATE | MAP_ANONYMOUS;
#if defined(MAP_NORESERVE)
    flags |= MAP_NORESERVE;
#endif
    return flags;
}

size_t page_size() {
    return static_cast<size_t>(sysconf(_SC_PAGESIZE));
}

}  // namespace

CounterArena::CounterArena(size_t capacity) {
    capacity = (capacity < MAX_CAPACITY ? capacity : MAX_CAPACITY) & ~(ALIGNMENT - 1);
    if (capacity == 0) {
        return;
    }
    size_t page = page_size();
    size_t reserved = (capacity + page - 1) / page * page;
    void* region = mmap(nullptr, reserved, PROT_READ | PROT_WRITE, map_flags(), -1, 0);
    if (region != MAP_FAILED) {
        base_ = static_cast<char*>(region);
        capacity_ = capacity;
        reserved_ = reserved;
    }
}

CounterArena::~CounterArena() {
    if (base_ != nullptr) {
        munmap(base_, reserved_);
    }
}

int64_t CounterArena::claim(size_t bytes) {
    bytes = (bytes + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
    size_t used = used_.load(std::memory_order_relaxed);
    do {
        // Never past the capacity, so Java never sees more in use than the buffer holds.
        if (bytes > capacity_ - used) {
            return -1;
        }
    } while (!used_.compare_exchange_weak(used, used + bytes, std::memory_order_relaxed));
    return static_cast<int64_t>(used);
}

int32_t CounterArena::counters(std::atomic<int32_t>& offset, size_t length) {
    int32_t current = offset.load(std::memory_order_acquire);
    if (current != UNCLAIMED || suspended()) {
        return current;
    }
    int64_t claimed = base_ == nullptr || length == 0 ? -1 : claim(length * sizeof(uint16_t));
    int32_t desired = claimed < 0 ? MISSING : static_cast<int32_t>(claimed);
    // Publishes the (zero) counters with the offset. Losing to another claim wastes this
    // one's space until the next clear(), which only a race on a first sample causes.
    if (offset.compare_exchange_strong(current, desired, std::memory_order_acq_rel, std::memory_order_acquire)) {
        return desired;
    }
    return current;
}

void CounterArena::clear() {
    size_t used = used_.load(std::memory_order_acquire);
    if (base_ == nullptr || used == 0) {
        return;
    }
    size_t page = page_size();
    size_t touched = (used + page - 1) / page * page;
    // The mapping stays as it is, so Java's buffer over it stays valid; only the pages'
    // contents go.
#if defined(__linux__)
    // A private anonymous page reads as zero after MADV_DONTNEED, and its memory is
    // returned to the OS.
    if (madvise(base_, touched, MADV_DONTNEED) != 0) {
        std::memset(base_, 0, used);
    }
#else
    // MADV_FREE lets the OS take the pages back but doesn't promise zeros, so zero them
    // first: either way they read as zero.
    std::memset(base_, 0, used);
    madvise(base_, touched, MADV_FREE);
#endif
    used_.store(0, std::memory_order_release);
}

}  // namespace instructive
