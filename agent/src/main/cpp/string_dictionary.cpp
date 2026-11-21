#include "string_dictionary.h"

#include <cstring>
#include <new>
#include <sys/mman.h>
#include <unistd.h>

namespace instructive {

namespace {

constexpr size_t INITIAL_CAPACITY = 1024;

// Address space whose pages are committed as they are touched.
void* reserve(size_t bytes) {
    int flags = MAP_PRIVATE | MAP_ANONYMOUS;
#if defined(MAP_NORESERVE)
    flags |= MAP_NORESERVE;
#endif
    void* region = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, flags, -1, 0);
    return region == MAP_FAILED ? nullptr : region;
}

size_t touched(size_t bytes) {
    size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    return (bytes + page - 1) / page * page;
}

}  // namespace

StringDictionary::Table::Table(size_t capacity)
    : capacity(capacity), slots(new (std::nothrow) std::atomic<uint32_t>[capacity]) {
    if (slots != nullptr) {
        for (size_t i = 0; i < capacity; ++i) {
            slots[i].store(0, std::memory_order_relaxed);
        }
    }
}

StringDictionary::Table::~Table() {
    delete[] slots;
}

StringDictionary::StringDictionary() {
    strings_ = static_cast<char*>(reserve(MAX_STRING_BYTES));
    index_ = static_cast<std::atomic<uint32_t>*>(reserve(MAX_STRINGS * sizeof(uint32_t)));
    Table* table = new (std::nothrow) Table(INITIAL_CAPACITY);
    if (strings_ == nullptr || index_ == nullptr || table == nullptr || table->slots == nullptr) {
        // Unusable: intern() finds no table and adds nothing.
        delete table;
        return;
    }
    tables_.push_back(table);
    table_bytes_ = INITIAL_CAPACITY * sizeof(uint32_t);
    table_.store(table, std::memory_order_release);
}

StringDictionary::~StringDictionary() {
    for (Table* table : tables_) {
        delete table;
    }
    if (strings_ != nullptr) {
        munmap(strings_, MAX_STRING_BYTES);
    }
    if (index_ != nullptr) {
        munmap(index_, MAX_STRINGS * sizeof(uint32_t));
    }
}

// FNV-1a.
uint32_t StringDictionary::hash(const char* bytes, size_t length) {
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < length; ++i) {
        h = (h ^ static_cast<uint8_t>(bytes[i])) * 16777619u;
    }
    return h;
}

const StringDictionary::Entry* StringDictionary::entry(uint32_t code) const {
    uint32_t offset = index_[code].load(std::memory_order_acquire);
    return reinterpret_cast<const Entry*>(strings_ + size_t{offset} * ENTRY_ALIGNMENT);
}

uint32_t StringDictionary::find(const Table* table, uint32_t hash, const char* bytes, size_t length) const {
    size_t mask = table->capacity - 1;
    for (size_t i = hash & mask;; i = (i + 1) & mask) {
        uint32_t slot = table->slots[i].load(std::memory_order_acquire);
        if (slot == 0) {
            return NO_CODE;
        }
        // A code is in a table only once its index entry and string are written.
        const Entry* candidate = entry(slot - 1);
        if (candidate->hash == hash && candidate->length == length &&
            std::memcmp(candidate->bytes(), bytes, length) == 0) {
            return slot - 1;
        }
    }
}

void StringDictionary::place(Table* table, uint32_t hash, uint32_t code) {
    size_t mask = table->capacity - 1;
    size_t i = hash & mask;
    while (table->slots[i].load(std::memory_order_relaxed) != 0) {
        i = (i + 1) & mask;
    }
    table->slots[i].store(code + 1, std::memory_order_release);
}

bool StringDictionary::grow() {
    Table* current = table_.load(std::memory_order_relaxed);
    Table* grown = new (std::nothrow) Table(current->capacity * 2);
    if (grown == nullptr || grown->slots == nullptr) {
        delete grown;
        return false;
    }
    uint32_t size = size_.load(std::memory_order_relaxed);
    for (uint32_t code = 0; code < size; ++code) {
        place(grown, entry(code)->hash, code);
    }
    tables_.push_back(grown);
    table_bytes_ += grown->capacity * sizeof(uint32_t);
    table_.store(grown, std::memory_order_release);
    return true;
}

uint32_t StringDictionary::intern(const char* bytes, size_t length) {
    Table* table = table_.load(std::memory_order_acquire);
    if (table == nullptr) {
        return NO_CODE;
    }
    uint32_t h = hash(bytes, length);
    uint32_t code = find(table, h, bytes, length);
    if (code != NO_CODE) {
        return code;
    }
    std::lock_guard<std::mutex> guard(lock_);
    // Another thread may have added it, or grown the table, since the probe.
    code = find(table_.load(std::memory_order_relaxed), h, bytes, length);
    if (code != NO_CODE) {
        return code;
    }
    code = size_.load(std::memory_order_relaxed);
    size_t space = sizeof(Entry) + length + 1;
    space = (space + ENTRY_ALIGNMENT - 1) & ~(ENTRY_ALIGNMENT - 1);
    if (code >= MAX_STRINGS || length > MAX_STRING_BYTES || space > MAX_STRING_BYTES - strings_used_) {
        return NO_CODE;
    }
    // At most half full, so probes stay short and always reach an empty slot.
    if ((uint64_t{code} + 1) * 2 > table_.load(std::memory_order_relaxed)->capacity && !grow()) {
        return NO_CODE;
    }
    auto* added = reinterpret_cast<Entry*>(strings_ + strings_used_);
    added->hash = h;
    added->length = static_cast<uint32_t>(length);
    char* copy = reinterpret_cast<char*>(added + 1);
    std::memcpy(copy, bytes, length);
    copy[length] = '\0';
    // The string is reachable by code before the table refers to it, and both before the
    // size says it exists.
    index_[code].store(static_cast<uint32_t>(strings_used_ / ENTRY_ALIGNMENT), std::memory_order_release);
    strings_used_ += space;
    place(table_.load(std::memory_order_relaxed), h, code);
    size_.store(code + 1, std::memory_order_release);
    return code;
}

const char* StringDictionary::lookup(uint32_t code, size_t* length) const {
    if (code >= size()) {
        return nullptr;
    }
    const Entry* found = entry(code);
    *length = found->length;
    return found->bytes();
}

size_t StringDictionary::bytes() const {
    std::lock_guard<std::mutex> guard(lock_);
    return touched(strings_used_) + touched(size_t{size_.load(std::memory_order_relaxed)} * sizeof(uint32_t)) +
           table_bytes_;
}

}  // namespace instructive
