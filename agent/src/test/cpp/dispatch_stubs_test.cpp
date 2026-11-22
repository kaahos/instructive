// Tests for DispatchStubs, built and run by the :agent:nativeTest task. Exits non-zero on
// failure.

#include "dispatch_stubs.h"

#include <atomic>
#include <cstdio>
#include <thread>
#include <vector>

using instructive::DispatchStubs;

namespace {

int failures = 0;

#define CHECK(condition)                                                                    \
    do {                                                                                    \
        if (!(condition)) {                                                                 \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
            ++failures;                                                                     \
        }                                                                                   \
    } while (0)

void recognises_dispatch_stubs() {
    CHECK(DispatchStubs::is_dispatch_stub("vtable stub"));
    CHECK(DispatchStubs::is_dispatch_stub("itable stub"));
    CHECK(!DispatchStubs::is_dispatch_stub("call_stub"));
    CHECK(!DispatchStubs::is_dispatch_stub(nullptr));
}

void finds_ranges() {
    DispatchStubs stubs;
    CHECK(!stubs.contains(0x1000));
    // Out of order, as stubs are created: the table keeps them sorted.
    DispatchStubs::release(stubs.add(0x2000, 24));
    DispatchStubs::release(stubs.add(0x1000, 160));
    DispatchStubs::release(stubs.add(0x1100, 24));
    CHECK(stubs.size() == 3);
    CHECK(stubs.contains(0x1000));
    CHECK(stubs.contains(0x109f));
    CHECK(!stubs.contains(0x10a0));
    CHECK(!stubs.contains(0x0fff));
    CHECK(stubs.contains(0x1117));
    CHECK(!stubs.contains(0x1118));
    CHECK(stubs.contains(0x2000));
    CHECK(!stubs.contains(0x2018));
    // The same stub again (GenerateEvents racing a live event): ignored.
    CHECK(stubs.add(0x1000, 160) == nullptr);
    CHECK(stubs.size() == 3);
    CHECK(stubs.add(0x3000, 0) == nullptr);
}

// Readers search while a writer adds stubs: every stub added stays found, and nothing
// between stubs is. Replaced tables are kept until the end, as the Sampler keeps them
// until no signal handler can be reading them.
void searches_while_stubs_are_added() {
    DispatchStubs stubs;
    constexpr int STUBS = 2000;
    std::atomic<int> added{0};
    std::atomic<bool> done{false};
    std::atomic<long> wrong{0};
    std::vector<std::thread> readers;
    for (int t = 0; t < 4; ++t) {
        readers.emplace_back([&] {
            while (!done.load()) {
                int count = added.load();
                for (int i = 0; i < count; ++i) {
                    uintptr_t begin = 0x100000 + static_cast<uintptr_t>((i * 7919) % STUBS) * 0x100;
                    if (!stubs.contains(begin) || !stubs.contains(begin + 0x7f) || stubs.contains(begin + 0x80)) {
                        wrong.fetch_add(1);
                    }
                }
            }
        });
    }
    std::vector<DispatchStubs::Ranges*> replaced;
    for (int i = 0; i < STUBS; ++i) {
        replaced.push_back(stubs.add(0x100000 + static_cast<uintptr_t>((i * 7919) % STUBS) * 0x100, 0x80));
        added.store(i + 1);
    }
    done.store(true);
    for (std::thread& reader : readers) {
        reader.join();
    }
    for (DispatchStubs::Ranges* ranges : replaced) {
        DispatchStubs::release(ranges);
    }
    CHECK(wrong.load() == 0);
    CHECK(stubs.size() == STUBS);
}

}  // namespace

int main() {
    recognises_dispatch_stubs();
    finds_ranges();
    searches_while_stubs_are_added();
    if (failures != 0) {
        std::fprintf(stderr, "%d checks failed\n", failures);
        return 1;
    }
    std::printf("dispatch_stubs_test: all checks passed\n");
    return 0;
}
