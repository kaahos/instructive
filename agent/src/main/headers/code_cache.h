#pragma once

#include <cstddef>
#include <cstdint>

#include "vm_structs.h"

namespace instructive {

enum class CodeKind : uint8_t {
    // The code cache layout couldn't be read from VMStructs.
    Unknown = 0,
    // Outside the code cache: the JVM itself, JNI or other native code.
    Native = 1,
    // The template interpreter.
    Interpreted = 2,
    // An nmethod, i.e. JIT-compiled Java code.
    Compiled = 3,
    // Any other code blob: runtime stubs, adapters, buffers...
    Stub = 4,
};

constexpr size_t CODE_KIND_COUNT = 5;

struct CodeLocation {
    CodeKind kind = CodeKind::Unknown;
    const char* blob = nullptr;
    // Compiled and Stub: the blob's size in bytes, header included.
    int32_t size = 0;
    // Compiled only.
    const void* method = nullptr;
    int32_t comp_level = -1;
    int32_t compile_id = -1;
    // Compiled and Stub: the blob's name, not dereferenced by lookup().
    const char* name = nullptr;
};

// Maps program counters to code blobs using HotSpot's code cache data structures,
// whose layout comes from VMStructs: CodeCache's bounds and heaps, each CodeHeap's
// memory and segment map, and the CodeBlob/nmethod fields of the blob found there.
class CodeCache {
public:
    // Reads the layout and the parts of the code cache that are fixed once the JVM is
    // initialised. Returns false (and lookup() returns Unknown) if anything is missing.
    bool init(const VMStructs& vm_structs);

    bool available() const { return available_; }

    // Walks the code heap to find the blob containing `pc` and reads its header and,
    // for nmethods, method metadata. Only loads, but the code heap changes underneath
    // concurrent readers, so a racing read can see a freed or half-written blob. Not
    // used from the signal handler; meant for contexts where the blob is known to be
    // live (e.g. a CompiledMethodLoad callback) or races are unlikely.
    CodeLocation lookup(uintptr_t pc) const;

    // Classifies `pc` using only bounds fixed at startup (the code cache and the
    // interpreter), never touching the code heap, so it is safe in a signal handler.
    // Anything inside the code cache other than the interpreter is reported as Stub.
    CodeKind classify(uintptr_t pc) const;

    // The smallest segment size of any code heap, as log2, or -1 if unavailable. No
    // two code blobs share a segment.
    int log2_segment_size() const;

    // The code section of `blob` (from lookup()), [*begin, *end): what JVMTI reports
    // as a compiled method's code. Returns false if its layout is unavailable. Only
    // loads from the blob's header; same caveats as lookup().
    bool code_range(const char* blob, uintptr_t* begin, uintptr_t* end) const;

    // Writes a Method* as "package.Class.name(signature)" into `out`, NUL-terminated
    // and truncated to fit `capacity` at a UTF-8 character boundary. Returns whether it
    // was truncated. Async-signal-safe, but only valid while the method's class is
    // loaded, e.g. while one of its nmethods is live.
    bool method_name(const void* method, char* out, size_t capacity) const;

private:
    static constexpr size_t MAX_HEAPS = 8;

    struct Heap {
        const char* code_heap;
        uintptr_t low;
        const uint8_t* segment_map;
        int log2_segment_size;
    };

    const char* find_blob(const Heap& heap, uintptr_t pc, int32_t* size) const;

    bool available_ = false;

    uintptr_t low_bound_ = 0;
    uintptr_t high_bound_ = 0;
    uintptr_t interpreter_begin_ = 0;
    uintptr_t interpreter_end_ = 0;
    Heap heaps_[MAX_HEAPS] = {};
    size_t heap_count_ = 0;

    // CodeHeap
    VMField heap_memory_high_;
    VMField heap_segmap_high_;
    // HeapBlock
    uint64_t heap_block_size_ = 0;
    VMField heap_block_used_;
    // CodeBlob
    VMField blob_name_;
    VMField blob_size_;
    VMField blob_kind_;
    // The code section: offsets from the blob (JDK 23+) or pointers (earlier).
    VMField blob_code_offset_;
    VMField blob_data_offset_;
    VMField blob_code_begin_;
    VMField blob_code_end_;
    int32_t nmethod_kind_ = -1;
    // nmethod
    VMField nmethod_method_;
    VMField nmethod_comp_level_;
    VMField nmethod_compile_id_;
    // Method name resolution
    VMField method_const_method_;
    VMField const_method_constants_;
    VMField const_method_name_index_;
    VMField const_method_signature_index_;
    VMField constant_pool_holder_;
    uint64_t constant_pool_size_ = 0;
    VMField klass_name_;
    VMField symbol_length_;
    VMField symbol_body_;
};

}  // namespace instructive
