// Tests for CounterArena, built and run by the :agent:nativeTest task (and under a
// sanitizer with -Pinstructive.sanitize=...). Exits non-zero on failure.

#include "counter_arena.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <memory>
#include <thread>
#include <utility>
#include <vector>

using instructive::CounterArena;

namespace {

int failures = 0;

#define CHECK(condition)                                                                    \
    do {                                                                                    \
        if (!(condition)) {                                                                 \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
            ++failures;                                                                     \
        }                                                                                   \
    } while (0)

void claims_once_per_record() {
    CounterArena arena(1 << 20);
    CHECK(arena.available());
    std::atomic<int32_t> a{CounterArena::UNCLAIMED};
    std::atomic<int32_t> b{CounterArena::UNCLAIMED};
    int32_t first = arena.counters(a, 3);
    CHECK(first == 0);
    CHECK(a.load() == first);
    CHECK(arena.counters(a, 3) == first);
    // 3 counters are 6 bytes, rounded up to the alignment.
    int32_t second = arena.counters(b, 100);
    CHECK(second == static_cast<int32_t>(CounterArena::ALIGNMENT));
    CHECK(arena.used() == CounterArena::ALIGNMENT + 200);
    // Fresh counters are zero.
    for (int i = 0; i < 100; ++i) {
        CHECK(arena.at(second)[i].load() == 0);
    }
}

void marks_records_it_cannot_serve() {
    CounterArena arena(64);
    std::atomic<int32_t> fits{CounterArena::UNCLAIMED};
    std::atomic<int32_t> too_big{CounterArena::UNCLAIMED};
    std::atomic<int32_t> after{CounterArena::UNCLAIMED};
    CHECK(arena.counters(fits, 16) == 0);
    CHECK(arena.counters(too_big, 17) == CounterArena::MISSING);
    CHECK(too_big.load() == CounterArena::MISSING);
    // Sticky: still missing, without claiming, even if space is left.
    CHECK(arena.counters(too_big, 1) == CounterArena::MISSING);
    CHECK(arena.counters(after, 16) == 32);
    CHECK(arena.used() == 64);
    std::atomic<int32_t> full{CounterArena::UNCLAIMED};
    CHECK(arena.counters(full, 1) == CounterArena::MISSING);
    CHECK(arena.used() == 64);
}

void claims_nothing_while_suspended() {
    CounterArena arena(1 << 20);
    std::atomic<int32_t> offset{CounterArena::UNCLAIMED};
    arena.suspend();
    CHECK(arena.counters(offset, 10) == CounterArena::UNCLAIMED);
    CHECK(offset.load() == CounterArena::UNCLAIMED);
    CHECK(arena.used() == 0);
    arena.resume();
    CHECK(arena.counters(offset, 10) == 0);
}

void clears() {
    CounterArena arena(1 << 20);
    std::atomic<int32_t> offset{CounterArena::UNCLAIMED};
    int32_t claimed = arena.counters(offset, 50000);
    for (int i = 0; i < 50000; ++i) {
        arena.at(claimed)[i].store(static_cast<uint16_t>(i | 1));
    }
    arena.suspend();
    offset.store(CounterArena::UNCLAIMED);
    arena.clear();
    arena.resume();
    CHECK(arena.used() == 0);
    // What was claimed reads as zero, and is claimed again from the start.
    std::atomic<int32_t> again{CounterArena::UNCLAIMED};
    CHECK(arena.counters(again, 50000) == 0);
    bool zero = true;
    for (int i = 0; i < 50000; ++i) {
        zero &= arena.at(0)[i].load() == 0;
    }
    CHECK(zero);
}

// Threads sample a set of records at random, as signal handlers on several CPUs would:
// each sample claims the record's counters if needed and increments one. Every record
// must end up with one offset or MISSING, claimed counters must not overlap, and every
// increment to a claimed record must be there.
void samples_concurrently(size_t capacity) {
    CounterArena arena(capacity);
    constexpr int RECORDS = 2000;
    constexpr int THREADS = 8;
    constexpr int SAMPLES = 200000;
    std::vector<std::atomic<int32_t>> offsets(RECORDS);
    std::vector<size_t> lengths(RECORDS);
    for (int r = 0; r < RECORDS; ++r) {
        offsets[r].store(CounterArena::UNCLAIMED);
        lengths[r] = 1 + (r * 37) % 300;
    }
    std::vector<std::unique_ptr<std::atomic<long>[]>> expected(RECORDS);
    for (int r = 0; r < RECORDS; ++r) {
        expected[r].reset(new std::atomic<long>[lengths[r]]);
        for (size_t i = 0; i < lengths[r]; ++i) {
            expected[r][i].store(0);
        }
    }
    std::vector<std::thread> threads;
    for (int t = 0; t < THREADS; ++t) {
        threads.emplace_back([&, t] {
            unsigned state = 12345 + t;
            for (int s = 0; s < SAMPLES; ++s) {
                state = state * 1103515245 + 12345;
                int r = static_cast<int>((state >> 8) % RECORDS);
                size_t i = (state >> 4) % lengths[r];
                int32_t offset = arena.counters(offsets[r], lengths[r]);
                if (offset >= 0) {
                    arena.at(offset)[i].fetch_add(1, std::memory_order_relaxed);
                    expected[r][i].fetch_add(1, std::memory_order_relaxed);
                }
            }
        });
    }
    for (std::thread& thread : threads) {
        thread.join();
    }
    std::vector<std::pair<int32_t, size_t>> claimed;
    int missing = 0;
    for (int r = 0; r < RECORDS; ++r) {
        int32_t offset = offsets[r].load();
        CHECK(offset != CounterArena::UNCLAIMED);
        if (offset == CounterArena::MISSING) {
            ++missing;
            continue;
        }
        claimed.emplace_back(offset, lengths[r] * sizeof(uint16_t));
        for (size_t i = 0; i < lengths[r]; ++i) {
            CHECK(arena.at(offset)[i].load() == expected[r][i].load());
        }
    }
    std::sort(claimed.begin(), claimed.end());
    for (size_t i = 1; i < claimed.size(); ++i) {
        CHECK(claimed[i - 1].first + static_cast<int64_t>(claimed[i - 1].second) <= claimed[i].first);
    }
    CHECK(arena.used() <= arena.capacity());
    size_t needed = 0;
    for (int r = 0; r < RECORDS; ++r) {
        needed += (lengths[r] * sizeof(uint16_t) + CounterArena::ALIGNMENT - 1) & ~(CounterArena::ALIGNMENT - 1);
    }
    if (needed <= capacity) {
        CHECK(missing == 0);
    } else {
        CHECK(missing > 0);
    }
}

}  // namespace

int main() {
    claims_once_per_record();
    marks_records_it_cannot_serve();
    claims_nothing_while_suspended();
    clears();
    samples_concurrently(size_t{4} << 20);  // room for every record
    samples_concurrently(64 << 10);         // room for some
    if (failures != 0) {
        std::fprintf(stderr, "%d checks failed\n", failures);
        return 1;
    }
    std::printf("counter_arena_test: all checks passed\n");
    return 0;
}
