#include "code_cache.h"

#include <initializer_list>

namespace instructive {

namespace {

// Segment map value marking a segment that isn't part of any block.
constexpr uint8_t FREE_SEGMENT = 0xFF;

// The first of `alternatives` (type names a field has had across JDK versions) that
// declares `name`.
template <size_t N>
VMField first_field(const VMStructs& vm, const char* const (&alternatives)[N], const char* name) {
    for (const char* type : alternatives) {
        VMField field = vm.field(type, name);
        if (field.found) {
            return field;
        }
    }
    return VMField{};
}

VMField nested(VMField outer, const VMField& inner) {
    outer.offset += inner.offset;
    outer.size = inner.size;
    return outer;
}

// Appends to a fixed buffer, truncating at a UTF-8 character boundary so the result
// stays valid (modified) UTF-8 for NewStringUTF.
class NameWriter {
public:
    NameWriter(char* out, size_t capacity) : out_(out), capacity_(capacity) {}

    void append(const char* s, size_t length, bool dots_for_slashes = false) {
        if (truncated_) {
            return;
        }
        for (size_t i = 0; i < length; ++i) {
            if (length_ + 1 >= capacity_) {
                truncated_ = true;
                // Don't leave half of a multi-byte sequence behind.
                while (length_ > 0 && (static_cast<uint8_t>(out_[length_ - 1]) & 0xC0) == 0x80) {
                    --length_;
                }
                if (length_ > 0 && (static_cast<uint8_t>(out_[length_ - 1]) & 0xC0) == 0xC0) {
                    --length_;
                }
                return;
            }
            char c = s[i];
            out_[length_++] = dots_for_slashes && c == '/' ? '.' : c;
        }
    }

    bool finish() {
        if (capacity_ > 0) {
            out_[length_] = '\0';
        }
        return truncated_;
    }

private:
    char* out_;
    size_t capacity_;
    size_t length_ = 0;
    bool truncated_ = false;
};

bool equals(const char* a, const char* b) {
    while (*a != '\0' && *a == *b) {
        ++a;
        ++b;
    }
    return *a == *b;
}

}  // namespace

bool CodeCache::init(const VMStructs& vm) {
    if (!vm.available()) {
        return false;
    }
    VMField low_bound = vm.field("CodeCache", "_low_bound");
    VMField high_bound = vm.field("CodeCache", "_high_bound");
    VMField heaps = vm.field("CodeCache", "_heaps");
    // GrowableArrayBase was GenericGrowableArray before JDK 16.
    VMField array_length = first_field(vm, {"GrowableArrayBase", "GenericGrowableArray"}, "_len");
    VMField array_data = vm.field("GrowableArray<int>", "_data");

    VMField heap_memory = vm.field("CodeHeap", "_memory");
    VMField heap_segmap = vm.field("CodeHeap", "_segmap");
    VMField log2_segment_size = vm.field("CodeHeap", "_log2_segment_size");
    VMField space_low_boundary = vm.field("VirtualSpace", "_low_boundary");
    VMField space_low = vm.field("VirtualSpace", "_low");
    VMField space_high = vm.field("VirtualSpace", "_high");

    VMField interpreter_code = vm.field("AbstractInterpreter", "_code");
    VMField stub_buffer = vm.field("StubQueue", "_stub_buffer");
    VMField buffer_limit = vm.field("StubQueue", "_buffer_limit");

    heap_block_size_ = vm.type_size("HeapBlock");
    heap_block_used_ = nested(vm.field("HeapBlock", "_header"), vm.field("HeapBlock::Header", "_used"));

    blob_name_ = vm.field("CodeBlob", "_name");
    blob_size_ = vm.field("CodeBlob", "_size");
    // JDK 23+ records the blob's kind; before that nmethods are identified by name.
    blob_kind_ = vm.field("CodeBlob", "_kind");
    // Optional: only needed by code_range().
    blob_code_offset_ = vm.field("CodeBlob", "_code_offset");
    blob_data_offset_ = vm.field("CodeBlob", "_data_offset");
    blob_code_begin_ = vm.field("CodeBlob", "_code_begin");
    blob_code_end_ = vm.field("CodeBlob", "_code_end");
    if (blob_kind_.found && !vm.int_constant("CodeBlobKind::Nmethod", &nmethod_kind_)) {
        return false;
    }
    // _method moved from CompiledMethod to nmethod in JDK 23.
    nmethod_method_ = first_field(vm, {"nmethod", "CompiledMethod"}, "_method");
    nmethod_comp_level_ = vm.field("nmethod", "_comp_level");
    nmethod_compile_id_ = vm.field("nmethod", "_compile_id");

    method_const_method_ = vm.field("Method", "_constMethod");
    const_method_constants_ = vm.field("ConstMethod", "_constants");
    const_method_name_index_ = vm.field("ConstMethod", "_name_index");
    const_method_signature_index_ = vm.field("ConstMethod", "_signature_index");
    constant_pool_holder_ = vm.field("ConstantPool", "_pool_holder");
    constant_pool_size_ = vm.type_size("ConstantPool");
    klass_name_ = vm.field("Klass", "_name");
    symbol_length_ = vm.field("Symbol", "_length");
    symbol_body_ = vm.field("Symbol", "_body");

    for (const VMField* field : {&low_bound, &high_bound, &heaps, &array_length, &array_data, &heap_memory,
                                 &heap_segmap, &log2_segment_size, &space_low_boundary, &space_low, &space_high,
                                 &interpreter_code, &stub_buffer, &buffer_limit, &heap_block_used_, &blob_name_,
                                 &blob_size_, &nmethod_method_, &nmethod_comp_level_, &nmethod_compile_id_,
                                 &method_const_method_, &const_method_constants_, &const_method_name_index_,
                                 &const_method_signature_index_, &constant_pool_holder_, &klass_name_,
                                 &symbol_length_, &symbol_body_}) {
        if (!field->found) {
            return false;
        }
    }
    if (heap_block_size_ == 0 || constant_pool_size_ == 0) {
        return false;
    }

    low_bound_ = reinterpret_cast<uintptr_t>(read_static_pointer(low_bound));
    high_bound_ = reinterpret_cast<uintptr_t>(read_static_pointer(high_bound));

    const char* queue = read_static_pointer(interpreter_code);
    if (queue != nullptr) {
        interpreter_begin_ = reinterpret_cast<uintptr_t>(read_pointer(queue, stub_buffer));
        interpreter_end_ = interpreter_begin_ + read_unsigned(queue, buffer_limit);
    }

    // The set of heaps is fixed at startup; only their committed size changes.
    heap_memory_high_ = nested(heap_memory, space_high);
    heap_segmap_high_ = nested(heap_segmap, space_high);
    const char* heap_array = read_static_pointer(heaps);
    if (heap_array == nullptr) {
        return false;
    }
    uint64_t length = read_unsigned(heap_array, array_length);
    auto* data = reinterpret_cast<const char* const*>(read_pointer(heap_array, array_data));
    for (uint64_t i = 0; i < length && heap_count_ < MAX_HEAPS; ++i) {
        const char* code_heap = data[i];
        heaps_[heap_count_++] = Heap{
            code_heap,
            reinterpret_cast<uintptr_t>(read_pointer(code_heap, nested(heap_memory, space_low_boundary))),
            reinterpret_cast<const uint8_t*>(read_pointer(code_heap, nested(heap_segmap, space_low))),
            static_cast<int>(read_signed(code_heap, log2_segment_size)),
        };
    }
    available_ = heap_count_ > 0;
    return available_;
}

// Mirrors CodeHeap::find_start: each segment map entry is the distance back (in
// segments) towards the segment where its block starts, 0 at the start itself.
const char* CodeCache::find_blob(const Heap& heap, uintptr_t pc, int32_t* size) const {
    uintptr_t committed_high = reinterpret_cast<uintptr_t>(read_pointer(heap.code_heap, heap_memory_high_));
    if (pc < heap.low || pc >= committed_high) {
        return nullptr;
    }
    uintptr_t map_size = reinterpret_cast<uintptr_t>(read_pointer(heap.code_heap, heap_segmap_high_)) -
                         reinterpret_cast<uintptr_t>(heap.segment_map);
    size_t segment = (pc - heap.low) >> heap.log2_segment_size;
    if (segment >= map_size || heap.segment_map[segment] == FREE_SEGMENT) {
        return nullptr;
    }
    // The map can change underneath us while code is being allocated or freed, so
    // validate each hop and the block found rather than trusting the map.
    for (uint8_t hop = heap.segment_map[segment]; hop > 0; hop = heap.segment_map[segment]) {
        if (hop == FREE_SEGMENT || hop > segment) {
            return nullptr;
        }
        segment -= hop;
    }
    const char* block = reinterpret_cast<const char*>(heap.low + (segment << heap.log2_segment_size));
    if (read_unsigned(block, heap_block_used_) == 0) {
        return nullptr;
    }
    const char* blob = block + heap_block_size_;
    uintptr_t blob_start = reinterpret_cast<uintptr_t>(blob);
    int64_t blob_size = read_signed(blob, blob_size_);
    if (blob_size <= 0 || pc < blob_start || pc >= blob_start + static_cast<uint64_t>(blob_size)) {
        return nullptr;
    }
    *size = static_cast<int32_t>(blob_size);
    return blob;
}

CodeKind CodeCache::classify(uintptr_t pc) const {
    if (!available_) {
        return CodeKind::Unknown;
    }
    if (pc < low_bound_ || pc >= high_bound_) {
        return CodeKind::Native;
    }
    if (pc >= interpreter_begin_ && pc < interpreter_end_) {
        return CodeKind::Interpreted;
    }
    return CodeKind::Stub;
}

int CodeCache::log2_segment_size() const {
    if (!available_) {
        return -1;
    }
    int smallest = heaps_[0].log2_segment_size;
    for (size_t i = 1; i < heap_count_; ++i) {
        if (heaps_[i].log2_segment_size < smallest) {
            smallest = heaps_[i].log2_segment_size;
        }
    }
    return smallest;
}

bool CodeCache::code_range(const char* blob, uintptr_t* begin, uintptr_t* end) const {
    if (!available_ || blob == nullptr) {
        return false;
    }
    if (blob_code_offset_.found && blob_data_offset_.found) {
        uintptr_t base = reinterpret_cast<uintptr_t>(blob);
        *begin = base + read_unsigned(blob, blob_code_offset_);
        *end = base + read_unsigned(blob, blob_data_offset_);
    } else if (blob_code_begin_.found && blob_code_end_.found) {
        *begin = reinterpret_cast<uintptr_t>(read_pointer(blob, blob_code_begin_));
        *end = reinterpret_cast<uintptr_t>(read_pointer(blob, blob_code_end_));
    } else {
        return false;
    }
    return *begin < *end;
}

CodeLocation CodeCache::lookup(uintptr_t pc) const {
    CodeLocation location;
    if (!available_) {
        return location;
    }
    if (pc < low_bound_ || pc >= high_bound_) {
        location.kind = CodeKind::Native;
        return location;
    }
    if (pc >= interpreter_begin_ && pc < interpreter_end_) {
        location.kind = CodeKind::Interpreted;
        return location;
    }
    for (size_t i = 0; i < heap_count_; ++i) {
        const char* blob = find_blob(heaps_[i], pc, &location.size);
        if (blob == nullptr) {
            continue;
        }
        location.blob = blob;
        location.name = read_pointer(blob, blob_name_);
        bool is_nmethod = blob_kind_.found
                              ? static_cast<int32_t>(read_unsigned(blob, blob_kind_)) == nmethod_kind_
                              : location.name != nullptr && equals(location.name, "nmethod");
        if (is_nmethod) {
            location.kind = CodeKind::Compiled;
            location.method = read_pointer(blob, nmethod_method_);
            location.comp_level = static_cast<int32_t>(read_signed(blob, nmethod_comp_level_));
            location.compile_id = static_cast<int32_t>(read_signed(blob, nmethod_compile_id_));
        } else {
            location.kind = CodeKind::Stub;
        }
        return location;
    }
    // In the code cache's range but not in a live blob, e.g. freed or being installed.
    location.kind = CodeKind::Stub;
    return location;
}

bool CodeCache::method_name(const void* method, char* out, size_t capacity) const {
    NameWriter writer(out, capacity);
    auto append_symbol = [&](const char* symbol, bool dots_for_slashes) {
        if (symbol == nullptr) {
            writer.append("?", 1);
        } else {
            writer.append(symbol + symbol_body_.offset, read_unsigned(symbol, symbol_length_), dots_for_slashes);
        }
    };
    if (!available_ || method == nullptr) {
        writer.append("?", 1);
        return writer.finish();
    }
    const char* const_method = read_pointer(method, method_const_method_);
    const char* constants = read_pointer(const_method, const_method_constants_);
    // Constant pool entries follow the ConstantPool object; Utf8 entries are Symbol*.
    auto* entries = reinterpret_cast<const char* const*>(constants + constant_pool_size_);
    const char* holder = read_pointer(constants, constant_pool_holder_);

    append_symbol(holder == nullptr ? nullptr : read_pointer(holder, klass_name_), true);
    writer.append(".", 1);
    append_symbol(entries[read_unsigned(const_method, const_method_name_index_)], false);
    append_symbol(entries[read_unsigned(const_method, const_method_signature_index_)], false);
    return writer.finish();
}

}  // namespace instructive
