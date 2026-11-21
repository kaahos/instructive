#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

namespace instructive {

// Assigns each distinct string a code: dense and sequential from 0, in the order the
// strings were first interned. Strings are byte sequences (JVMTI's modified UTF-8) and
// are never removed, so a code stays valid, and lookup(code) keeps returning the same
// bytes, for the dictionary's lifetime.
//
// intern() is safe to call from any number of threads. A string already present is
// found without locking, by probing an open-addressing table of codes. A new string is
// added under a mutex, which is what hands out codes, so codes have no gaps even when
// threads race to add the same string. The mutex also covers growing the table: a
// grown table is published atomically, and tables are kept until the dictionary is
// destroyed, so a lock-free probe of an old one is still safe; it can only miss a
// string, and then finds it under the mutex. lookup() never locks.
//
// Neither is async-signal-safe: intern() allocates and lookup() is meant for JNI calls.
//
// Storage is two regions of address space reserved up front, whose pages are only
// committed as they are touched, like the ProfileArena:
//  - the strings, each with an 8-byte header (hash and length) and a NUL, packed at
//    4-byte alignment: MAX_STRING_BYTES in all;
//  - the code index, a 4-byte entry per code giving its string's offset in 4-byte
//    units: MAX_STRINGS in all.
// Each string also costs, with the table at most half full, between two and four 4-byte
// slots. Past either limit, intern() returns NO_CODE.
class StringDictionary {
public:
    static constexpr uint32_t NO_CODE = UINT32_MAX;
    static constexpr size_t MAX_STRING_BYTES = size_t{64} << 20;
    static constexpr size_t MAX_STRINGS = size_t{4} << 20;

    StringDictionary();
    ~StringDictionary();

    StringDictionary(const StringDictionary&) = delete;
    StringDictionary& operator=(const StringDictionary&) = delete;

    // The code of `bytes`, adding it if it isn't present. NO_CODE if it can't be added.
    uint32_t intern(const char* bytes, size_t length);

    // The string with this code, or null if there is none. *length receives its length;
    // the bytes are followed by a NUL.
    const char* lookup(uint32_t code, size_t* length) const;

    // The number of strings: the next code to be assigned.
    uint32_t size() const { return size_.load(std::memory_order_acquire); }

    // Bytes of memory in use: the pages of the two regions touched so far, and the
    // tables.
    size_t bytes() const;

private:
    struct Entry {
        uint32_t hash;
        uint32_t length;
        // `length` bytes and a NUL follow.
        const char* bytes() const { return reinterpret_cast<const char*>(this + 1); }
    };

    // Open addressing: each slot holds 0 (empty) or code + 1.
    struct Table {
        explicit Table(size_t capacity);
        ~Table();
        size_t capacity;
        std::atomic<uint32_t>* slots;
    };

    static constexpr size_t ENTRY_ALIGNMENT = alignof(Entry);

    static uint32_t hash(const char* bytes, size_t length);

    // The code of the string in `table`, or NO_CODE if it isn't there. Lock-free.
    uint32_t find(const Table* table, uint32_t hash, const char* bytes, size_t length) const;
    // The entry for a code below size(), or one being added under lock_.
    const Entry* entry(uint32_t code) const;
    // Under lock_.
    bool grow();
    static void place(Table* table, uint32_t hash, uint32_t code);

    char* strings_ = nullptr;
    std::atomic<uint32_t>* index_ = nullptr;
    std::atomic<Table*> table_{nullptr};
    std::atomic<uint32_t> size_{0};

    mutable std::mutex lock_;
    // Every table ever published, so old ones outlive lock-free probes of them.
    std::vector<Table*> tables_;
    size_t strings_used_ = 0;
    size_t table_bytes_ = 0;
};

}  // namespace instructive
