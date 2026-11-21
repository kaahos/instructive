#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <jni.h>

namespace instructive {

// A compiled method as reported by JVMTI CompiledMethodLoad, stored in a block of the
// ProfileArena, which Java reads directly through a ByteBuffer. The layout is shared
// with instructive.agent.InstructionProfile and must match it exactly, hence the
// fixed-width fields and the static_asserts below. Native-only fields are part of the
// layout too, so Java knows to skip them.
//
//    0 u32 lengths         profile_length | name_length << 16, written as one word
//    4 u32 generation      odd while the block is being (re)initialised
//    8 u64 code_begin      start of the instructions JVMTI reported
//   16 u64 count           samples anywhere in the mapped range
//   24 u64 method          jmethodID, matched against CompiledMethodUnload (native only)
//   32 i32 compile_id      -1 if unknown
//   36 u32 header_offset   code_begin - range_begin: the mapped range starts this far
//                          before the instructions (the nmethod's header) and ends at
//                          code_begin + (profile_length << pc_shift) (native only)
//   40 u32 state           FREE, LIVE or RETIRED; a full word because it is CASed
//   44 u16 code_size       bytes of instructions, saturated at UINT16_MAX
//   46 i8  comp_level      -1 if unknown
//   47 u8  pc_shift        log2 of the bytes each counter covers (PC_SHIFT)
//   48 u16 pc_counts[profile_length], then name[name_length] and a NUL, then padding
//      to 8 bytes
//
// There is no block length: it is block_size(profile_length, name_length), which
// readers compute. That holds because a freed block is only reused for a record of the
// same size class, and stays safe for a reader racing a reuse because both lengths
// live in one word written atomically, so they are never seen from different records.
//
// pc_counts covers [code_begin, code_begin + (profile_length << PC_SHIFT)): the
// instructions and the stubs after them, with one counter per possible instruction
// start. On AArch64 every instruction is 4 bytes and 4-byte aligned, so a counter covers
// 4 bytes and the PC's offset is divided by 4; elsewhere (x86 instructions vary in
// length) a counter covers one byte. profile_length and code_size are 16 bits, so an
// nmethod is mapped and profiled for at most 65535 counters from code_begin (256 KiB of
// code on AArch64, 64 KiB elsewhere), and its code size saturates at 65535 bytes;
// samples beyond the profile count as stubs. Counters saturate at MAX_PC_COUNT, about
// 11 minutes of samples at one PC at 10ms.
//
// state goes FREE -> LIVE when the method is inserted into the map, LIVE -> RETIRED
// when a thread takes responsibility for unlinking and freeing it (retire()), and back
// to FREE when the arena reclaims the block.
struct CompiledMethod {
    static constexpr uint32_t FREE = 0;
    static constexpr uint32_t LIVE = 1;
    static constexpr uint32_t RETIRED = 2;
    static constexpr uint16_t MAX_PC_COUNT = UINT16_MAX;
    static constexpr size_t MAX_LENGTH = UINT16_MAX;
    static constexpr size_t HEADER_SIZE = 48;
    static constexpr size_t ALIGNMENT = 8;
    static constexpr size_t MIN_BLOCK_SIZE = 128;
#if defined(__aarch64__)
    static constexpr int PC_SHIFT = 2;
#else
    static constexpr int PC_SHIFT = 0;
#endif

    std::atomic<uint32_t> lengths;
    std::atomic<uint32_t> generation;
    uint64_t code_begin;
    std::atomic<uint64_t> count;
    jmethodID method;
    int32_t compile_id;
    uint32_t header_offset;
    std::atomic<uint32_t> state;
    uint16_t code_size;
    int8_t comp_level;
    uint8_t pc_shift;

    // The size of the block holding a record with these variable parts: header, counts,
    // name and NUL, padded to ALIGNMENT, then rounded up to a size class (four per power
    // of two, so at most 25% over). Must match InstructionProfile.blockLength.
    static size_t block_size(size_t profile_length, size_t name_length) {
        size_t bytes = HEADER_SIZE + profile_length * sizeof(uint16_t) + name_length + 1;
        bytes = (bytes + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
        if (bytes <= MIN_BLOCK_SIZE) {
            return MIN_BLOCK_SIZE;
        }
        int log2 = 63 - __builtin_clzll(static_cast<unsigned long long>(bytes));
        size_t step = size_t{1} << (log2 - 2);
        return (bytes + step - 1) & ~(step - 1);
    }

    static uint32_t pack_lengths(size_t profile_length, size_t name_length) {
        return static_cast<uint32_t>(profile_length) | static_cast<uint32_t>(name_length) << 16;
    }

    uint32_t profile_length() const { return lengths.load(std::memory_order_relaxed) & 0xFFFF; }
    uint32_t name_length() const { return lengths.load(std::memory_order_relaxed) >> 16; }
    size_t block_length() const { return block_size(profile_length(), name_length()); }

    uint64_t code_end() const { return code_begin + code_size; }
    uint64_t range_begin() const { return code_begin - header_offset; }
    uint64_t range_end() const { return code_begin + (uint64_t{profile_length()} << PC_SHIFT); }

    // The number of counters needed to cover `bytes` of code from code_begin.
    static size_t counters_for(size_t bytes) {
        return (bytes + (size_t{1} << PC_SHIFT) - 1) >> PC_SHIFT;
    }

    // Takes responsibility for unlinking and freeing a LIVE method: exactly one caller
    // gets true.
    bool retire() {
        uint32_t expected = LIVE;
        return state.compare_exchange_strong(expected, RETIRED);
    }

    std::atomic<uint16_t>* pc_counts() {
        return reinterpret_cast<std::atomic<uint16_t>*>(reinterpret_cast<char*>(this) + HEADER_SIZE);
    }
    const std::atomic<uint16_t>* pc_counts() const {
        return reinterpret_cast<const std::atomic<uint16_t>*>(reinterpret_cast<const char*>(this) + HEADER_SIZE);
    }

    char* name() { return reinterpret_cast<char*>(pc_counts() + profile_length()); }
    const char* name() const { return reinterpret_cast<const char*>(pc_counts() + profile_length()); }

    // Async-signal-safe.
    void record_sample(uintptr_t pc) {
        count.fetch_add(1, std::memory_order_relaxed);
        uint64_t offset = (pc - code_begin) >> PC_SHIFT;
        if (pc >= code_begin && offset < profile_length()) {
            std::atomic<uint16_t>& pc_count = pc_counts()[offset];
            uint16_t current = pc_count.load(std::memory_order_relaxed);
            while (current != MAX_PC_COUNT &&
                   !pc_count.compare_exchange_weak(current, static_cast<uint16_t>(current + 1),
                                                   std::memory_order_relaxed)) {
            }
        }
    }

    // Zeroes the total and per-PC counts. Samples racing with the reset may land on
    // either side of it.
    void reset_counts() {
        count.store(0, std::memory_order_relaxed);
        std::atomic<uint16_t>* counts = pc_counts();
        for (uint32_t i = 0, length = profile_length(); i < length; ++i) {
            counts[i].store(0, std::memory_order_relaxed);
        }
    }
};

static_assert(sizeof(jmethodID) == 8, "the layout assumes 64-bit pointers");
static_assert(offsetof(CompiledMethod, lengths) == 0, "layout");
static_assert(offsetof(CompiledMethod, generation) == 4, "layout");
static_assert(offsetof(CompiledMethod, code_begin) == 8, "layout");
static_assert(offsetof(CompiledMethod, count) == 16, "layout");
static_assert(offsetof(CompiledMethod, method) == 24, "layout");
static_assert(offsetof(CompiledMethod, compile_id) == 32, "layout");
static_assert(offsetof(CompiledMethod, header_offset) == 36, "layout");
static_assert(offsetof(CompiledMethod, state) == 40, "layout");
static_assert(offsetof(CompiledMethod, code_size) == 44, "layout");
static_assert(offsetof(CompiledMethod, comp_level) == 46, "layout");
static_assert(offsetof(CompiledMethod, pc_shift) == 47, "layout");
static_assert(sizeof(CompiledMethod) == CompiledMethod::HEADER_SIZE, "header size");
static_assert(sizeof(std::atomic<uint16_t>) == 2 &&
                  sizeof(std::atomic<uint32_t>) == 4 && sizeof(std::atomic<uint64_t>) == 8,
              "atomics must have the size of the values Java reads");
static_assert(CompiledMethod::MIN_BLOCK_SIZE % CompiledMethod::ALIGNMENT == 0, "blocks stay aligned");

}  // namespace instructive
