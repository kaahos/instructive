#include "vm_structs.h"

#include <cstring>
#include <dlfcn.h>

namespace instructive {

namespace {

const char* string_at(const char* entry, uint64_t offset) {
    return *reinterpret_cast<const char* const*>(entry + offset);
}

uint64_t u64_at(const char* entry, uint64_t offset) {
    return *reinterpret_cast<const uint64_t*>(entry + offset);
}

const char* strip_prefix(const char* s, const char* prefix) {
    size_t n = std::strlen(prefix);
    return std::strncmp(s, prefix, n) == 0 ? s + n : s;
}

}  // namespace

bool VMStructs::init(const void* jvm_address) {
    Dl_info info;
    if (dladdr(jvm_address, &info) == 0 || info.dli_fname == nullptr) {
        return false;
    }
    // RTLD_NOLOAD: only take a reference to the already loaded libjvm.
    void* handle = dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        return false;
    }
    bool missing = false;
    auto pointer = [&](const char* name) -> const char* {
        auto* symbol = static_cast<const char* const*>(dlsym(handle, name));
        missing |= symbol == nullptr;
        return symbol == nullptr ? nullptr : *symbol;
    };
    auto value = [&](const char* name) -> uint64_t {
        auto* symbol = static_cast<const uint64_t*>(dlsym(handle, name));
        missing |= symbol == nullptr;
        return symbol == nullptr ? 0 : *symbol;
    };

    const char* structs = pointer("gHotSpotVMStructs");
    struct_type_name_ = value("gHotSpotVMStructEntryTypeNameOffset");
    struct_field_name_ = value("gHotSpotVMStructEntryFieldNameOffset");
    struct_type_string_ = value("gHotSpotVMStructEntryTypeStringOffset");
    struct_is_static_ = value("gHotSpotVMStructEntryIsStaticOffset");
    struct_offset_ = value("gHotSpotVMStructEntryOffsetOffset");
    struct_address_ = value("gHotSpotVMStructEntryAddressOffset");
    struct_stride_ = value("gHotSpotVMStructEntryArrayStride");

    types_ = pointer("gHotSpotVMTypes");
    type_type_name_ = value("gHotSpotVMTypeEntryTypeNameOffset");
    type_size_ = value("gHotSpotVMTypeEntrySizeOffset");
    type_stride_ = value("gHotSpotVMTypeEntryArrayStride");

    int_constants_ = pointer("gHotSpotVMIntConstants");
    int_constant_name_ = value("gHotSpotVMIntConstantEntryNameOffset");
    int_constant_value_ = value("gHotSpotVMIntConstantEntryValueOffset");
    int_constant_stride_ = value("gHotSpotVMIntConstantEntryArrayStride");

    // libjvm stays loaded while the JVM runs; drop the reference taken above.
    dlclose(handle);
    if (missing || structs == nullptr || types_ == nullptr || int_constants_ == nullptr) {
        types_ = nullptr;
        int_constants_ = nullptr;
        return false;
    }
    structs_ = structs;
    return true;
}

VMField VMStructs::field(const char* type, const char* name) const {
    VMField result;
    if (structs_ == nullptr) {
        return result;
    }
    for (const char* entry = structs_;; entry += struct_stride_) {
        const char* entry_type = string_at(entry, struct_type_name_);
        if (entry_type == nullptr) {
            break;
        }
        const char* entry_field = string_at(entry, struct_field_name_);
        if (entry_field == nullptr || std::strcmp(entry_type, type) != 0 || std::strcmp(entry_field, name) != 0) {
            continue;
        }
        result.found = true;
        result.is_static = *reinterpret_cast<const int32_t*>(entry + struct_is_static_) != 0;
        result.offset = u64_at(entry, struct_offset_);
        result.address = *reinterpret_cast<const void* const*>(entry + struct_address_);
        const char* type_string = string_at(entry, struct_type_string_);
        result.size = type_string == nullptr ? 0 : static_cast<uint32_t>(type_size(type_string));
        break;
    }
    return result;
}

uint64_t VMStructs::type_size(const char* type) const {
    if (types_ == nullptr) {
        return 0;
    }
    type = strip_prefix(strip_prefix(type, "volatile "), "const ");
    size_t length = std::strlen(type);
    if (length > 0 && type[length - 1] == '*') {
        return sizeof(void*);
    }
    for (const char* entry = types_;; entry += type_stride_) {
        const char* entry_type = string_at(entry, type_type_name_);
        if (entry_type == nullptr) {
            break;
        }
        if (std::strcmp(entry_type, type) == 0) {
            return u64_at(entry, type_size_);
        }
    }
    // Spelled-out character types appear in type strings but not in VMTypes.
    if (std::strcmp(type, "signed char") == 0 || std::strcmp(type, "unsigned char") == 0) {
        return 1;
    }
    return 0;
}

bool VMStructs::int_constant(const char* name, int32_t* value) const {
    if (int_constants_ == nullptr) {
        return false;
    }
    for (const char* entry = int_constants_;; entry += int_constant_stride_) {
        const char* entry_name = string_at(entry, int_constant_name_);
        if (entry_name == nullptr) {
            break;
        }
        if (std::strcmp(entry_name, name) == 0) {
            *value = *reinterpret_cast<const int32_t*>(entry + int_constant_value_);
            return true;
        }
    }
    return false;
}

}  // namespace instructive
