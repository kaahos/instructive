// Tests for StringDictionary, built and run by the :agent:nativeTest task (and under
// ThreadSanitizer with -Pinstructive.sanitize=thread). Exits non-zero on failure.

#include "string_dictionary.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

using instructive::StringDictionary;

namespace {

int failures = 0;

#define CHECK(condition)                                                                    \
    do {                                                                                    \
        if (!(condition)) {                                                                 \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
            ++failures;                                                                     \
        }                                                                                   \
    } while (0)

uint32_t intern(StringDictionary& dictionary, const std::string& s) {
    return dictionary.intern(s.data(), s.size());
}

std::string lookup(const StringDictionary& dictionary, uint32_t code) {
    size_t length = 0;
    const char* bytes = dictionary.lookup(code, &length);
    return bytes == nullptr ? std::string("<none>") : std::string(bytes, length);
}

void assigns_sequential_codes() {
    StringDictionary dictionary;
    CHECK(dictionary.size() == 0);
    CHECK(intern(dictionary, "java/lang/String") == 0);
    CHECK(intern(dictionary, "hashCode") == 1);
    CHECK(intern(dictionary, "java/lang/String") == 0);
    CHECK(intern(dictionary, "()I") == 2);
    CHECK(dictionary.size() == 3);
    CHECK(lookup(dictionary, 1) == "hashCode");
    size_t length = 0;
    CHECK(dictionary.lookup(3, &length) == nullptr);
    CHECK(dictionary.lookup(StringDictionary::NO_CODE, &length) == nullptr);
    // NUL-terminated, for callers that want a C string.
    CHECK(std::strcmp(dictionary.lookup(0, &length), "java/lang/String") == 0);
}

void handles_unusual_strings() {
    StringDictionary dictionary;
    uint32_t empty = intern(dictionary, "");
    CHECK(intern(dictionary, "") == empty);
    CHECK(lookup(dictionary, empty).empty());
    std::string with_nul("a\0b", 3);
    std::string prefix("a");
    uint32_t a = intern(dictionary, prefix);
    uint32_t a_nul_b = intern(dictionary, with_nul);
    CHECK(a != a_nul_b);
    CHECK(lookup(dictionary, a_nul_b) == with_nul);
    std::string long_string(200000, 'x');
    uint32_t long_code = intern(dictionary, long_string);
    CHECK(lookup(dictionary, long_code) == long_string);
    CHECK(intern(dictionary, long_string) == long_code);
    CHECK(intern(dictionary, "after") == long_code + 1);
}

void grows() {
    StringDictionary dictionary;
    constexpr uint32_t COUNT = 200000;
    for (uint32_t i = 0; i < COUNT; ++i) {
        CHECK(intern(dictionary, "s" + std::to_string(i)) == i);
    }
    CHECK(dictionary.size() == COUNT);
    for (uint32_t i = 0; i < COUNT; i += 997) {
        CHECK(intern(dictionary, "s" + std::to_string(i)) == i);
        CHECK(lookup(dictionary, i) == "s" + std::to_string(i));
    }
    CHECK(dictionary.bytes() > 0);
}

// Past its string storage, nothing more is added, and what is there still works.
void stops_at_its_limit() {
    StringDictionary dictionary;
    uint32_t first = intern(dictionary, "first");
    std::string big(size_t{1} << 20, 'b');
    uint32_t added = 0;
    for (int i = 0; i < 100; ++i) {
        big[0] = static_cast<char>(i);
        if (intern(dictionary, big) == StringDictionary::NO_CODE) {
            break;
        }
        ++added;
    }
    // 64 MiB of storage holds 63 strings of 1 MiB with their headers, beside "first".
    CHECK(added == StringDictionary::MAX_STRING_BYTES / (size_t{1} << 20) - 1);
    CHECK(intern(dictionary, "first") == first);
    CHECK(lookup(dictionary, first) == "first");
    big[0] = 0;
    CHECK(intern(dictionary, big) == first + 1);
    CHECK(dictionary.size() == added + 1);
    // Every touched page of storage is counted.
    CHECK(dictionary.bytes() >= StringDictionary::MAX_STRING_BYTES - (size_t{1} << 20));
}

void counts_its_memory() {
    StringDictionary dictionary;
    size_t empty = dictionary.bytes();
    for (int i = 0; i < 100000; ++i) {
        intern(dictionary, "java/lang/Class" + std::to_string(i));
    }
    size_t full = dictionary.bytes();
    // About 30 bytes of string and header, 4 of index and 8 to 16 of table each.
    CHECK(full - empty > 100000 * 40);
    CHECK(full - empty < 100000 * 60);
}

// Threads intern overlapping sets of strings in different orders while a reader decodes
// codes as they appear. Every string must end up with exactly one code, the codes must
// be 0..n-1, and every code must decode to its string throughout.
void interns_concurrently() {
    StringDictionary dictionary;
    constexpr int THREADS = 8;
    constexpr int SHARED = 20000;
    constexpr int OWN = 5000;
    std::atomic<bool> done{false};
    std::atomic<long> bad_reads{0};
    std::thread reader([&] {
        while (!done.load()) {
            uint32_t size = dictionary.size();
            for (uint32_t code = size > 64 ? size - 64 : 0; code < size; ++code) {
                std::string s = lookup(dictionary, code);
                // Each string's code, interned again, is itself.
                if (s == "<none>" || intern(dictionary, s) != code) {
                    bad_reads.fetch_add(1);
                }
            }
        }
    });
    std::vector<std::vector<uint32_t>> codes(THREADS);
    std::vector<std::thread> writers;
    for (int t = 0; t < THREADS; ++t) {
        writers.emplace_back([&, t] {
            for (int i = 0; i < SHARED; ++i) {
                // A different order on each thread.
                int k = (i * (2 * t + 1) + t * 7919) % SHARED;
                codes[t].push_back(intern(dictionary, "shared/" + std::to_string(k)));
            }
            for (int i = 0; i < OWN; ++i) {
                intern(dictionary, "own/" + std::to_string(t) + "/" + std::to_string(i));
            }
        });
    }
    for (std::thread& writer : writers) {
        writer.join();
    }
    done.store(true);
    reader.join();
    CHECK(bad_reads.load() == 0);
    CHECK(dictionary.size() == SHARED + THREADS * OWN);
    std::unordered_map<std::string, uint32_t> seen;
    std::unordered_set<uint32_t> codes_seen;
    for (uint32_t code = 0; code < dictionary.size(); ++code) {
        std::string s = lookup(dictionary, code);
        CHECK(seen.emplace(s, code).second);
        codes_seen.insert(code);
    }
    CHECK(seen.size() == dictionary.size());
    for (int t = 0; t < THREADS; ++t) {
        for (int i = 0; i < SHARED; ++i) {
            int k = (i * (2 * t + 1) + t * 7919) % SHARED;
            CHECK(seen.at("shared/" + std::to_string(k)) == codes[t][i]);
        }
    }
}

}  // namespace

int main() {
    assigns_sequential_codes();
    handles_unusual_strings();
    grows();
    stops_at_its_limit();
    counts_its_memory();
    interns_concurrently();
    if (failures != 0) {
        std::fprintf(stderr, "%d checks failed\n", failures);
        return 1;
    }
    std::printf("string_dictionary_test: all checks passed\n");
    return 0;
}
