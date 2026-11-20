#pragma once

#include <cstddef>
#include <cstdint>

namespace instructive {

// A field described by HotSpot's VMStructs table. Non-static fields have an offset
// from the start of their object; static fields have the address of the variable.
struct VMField {
    bool found = false;
    bool is_static = false;
    uint64_t offset = 0;
    const void* address = nullptr;
    // Width in bytes of the field's declared type, or 0 if VMTypes doesn't describe it
    // (embedded structs such as CodeHeap::_memory are only used for their offset).
    uint32_t size = 0;
};

// Reads the gHotSpotVM* tables HotSpot exports for the Serviceability Agent. The
// layout of those tables is itself described by exported offset/stride variables, and
// the set of entries changes between JDK versions, so every lookup may fail and
// callers must handle missing entries. Lookups scan the tables and are meant to be
// done once, outside signal handlers.
class VMStructs {
public:
    // Locates the tables in the libjvm containing `jvm_address` (any code or data
    // address inside libjvm, e.g. a JNI function). Returns false if libjvm doesn't
    // export them; on macOS, JDKs before 25 keep these symbols local.
    bool init(const void* jvm_address);

    bool available() const { return structs_ != nullptr; }

    VMField field(const char* type, const char* name) const;

    // Size of a type as recorded in VMTypes; pointer types are pointer-sized.
    // Returns 0 if the type is unknown.
    uint64_t type_size(const char* type) const;

    bool int_constant(const char* name, int32_t* value) const;

private:
    const char* structs_ = nullptr;
    uint64_t struct_type_name_ = 0;
    uint64_t struct_field_name_ = 0;
    uint64_t struct_type_string_ = 0;
    uint64_t struct_is_static_ = 0;
    uint64_t struct_offset_ = 0;
    uint64_t struct_address_ = 0;
    uint64_t struct_stride_ = 0;

    const char* types_ = nullptr;
    uint64_t type_type_name_ = 0;
    uint64_t type_size_ = 0;
    uint64_t type_stride_ = 0;

    const char* int_constants_ = nullptr;
    uint64_t int_constant_name_ = 0;
    uint64_t int_constant_value_ = 0;
    uint64_t int_constant_stride_ = 0;
};

// Field accessors. These only load memory and are async-signal-safe; the caller is
// responsible for `base` pointing at readable memory.
inline uint64_t read_unsigned(const void* base, const VMField& field) {
    const char* p = static_cast<const char*>(base) + field.offset;
    switch (field.size) {
        case 1: return *reinterpret_cast<const uint8_t*>(p);
        case 2: return *reinterpret_cast<const uint16_t*>(p);
        case 4: return *reinterpret_cast<const uint32_t*>(p);
        case 8: return *reinterpret_cast<const uint64_t*>(p);
        default: return 0;
    }
}

inline int64_t read_signed(const void* base, const VMField& field) {
    const char* p = static_cast<const char*>(base) + field.offset;
    switch (field.size) {
        case 1: return *reinterpret_cast<const int8_t*>(p);
        case 2: return *reinterpret_cast<const int16_t*>(p);
        case 4: return *reinterpret_cast<const int32_t*>(p);
        case 8: return *reinterpret_cast<const int64_t*>(p);
        default: return 0;
    }
}

inline const char* read_pointer(const void* base, const VMField& field) {
    return *reinterpret_cast<const char* const*>(static_cast<const char*>(base) + field.offset);
}

inline const char* read_static_pointer(const VMField& field) {
    return *static_cast<const char* const*>(field.address);
}

}  // namespace instructive
