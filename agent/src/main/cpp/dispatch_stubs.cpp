#include "dispatch_stubs.h"

#include <cstring>
#include <new>

namespace instructive {

struct DispatchStubs::Ranges {
    size_t count;
    Range ranges[1];  // count of them, sorted by begin

    static Ranges* allocate(size_t count) {
        void* memory = ::operator new(sizeof(Ranges) + (count > 0 ? count - 1 : 0) * sizeof(Range), std::nothrow);
        if (memory == nullptr) {
            return nullptr;
        }
        auto* ranges = static_cast<Ranges*>(memory);
        ranges->count = count;
        return ranges;
    }
};

DispatchStubs::~DispatchStubs() {
    release(ranges_.load(std::memory_order_relaxed));
}

bool DispatchStubs::is_dispatch_stub(const char* name) {
    return name != nullptr && (std::strcmp(name, "vtable stub") == 0 || std::strcmp(name, "itable stub") == 0);
}

void DispatchStubs::release(Ranges* ranges) {
    ::operator delete(ranges);
}

DispatchStubs::Ranges* DispatchStubs::add(uintptr_t begin, size_t length) {
    if (length == 0) {
        return nullptr;
    }
    std::lock_guard<std::mutex> guard(lock_);
    Ranges* current = ranges_.load(std::memory_order_relaxed);
    size_t count = current == nullptr ? 0 : current->count;
    size_t at = 0;
    while (at < count && current->ranges[at].begin < begin) {
        ++at;
    }
    if (at < count && current->ranges[at].begin == begin) {
        return nullptr;
    }
    Ranges* grown = Ranges::allocate(count + 1);
    if (grown == nullptr) {
        return nullptr;
    }
    for (size_t i = 0; i < at; ++i) {
        grown->ranges[i] = current->ranges[i];
    }
    grown->ranges[at] = Range{begin, begin + length};
    for (size_t i = at; i < count; ++i) {
        grown->ranges[i + 1] = current->ranges[i];
    }
    ranges_.store(grown, std::memory_order_release);
    return current;
}

bool DispatchStubs::contains(uintptr_t pc) const {
    const Ranges* ranges = ranges_.load(std::memory_order_acquire);
    if (ranges == nullptr) {
        return false;
    }
    // The last range starting at or before pc.
    size_t low = 0;
    size_t high = ranges->count;
    while (low < high) {
        size_t middle = low + (high - low) / 2;
        if (ranges->ranges[middle].begin <= pc) {
            low = middle + 1;
        } else {
            high = middle;
        }
    }
    return low > 0 && pc < ranges->ranges[low - 1].end;
}

size_t DispatchStubs::size() const {
    const Ranges* ranges = ranges_.load(std::memory_order_acquire);
    return ranges == nullptr ? 0 : ranges->count;
}

}  // namespace instructive
