#include "sampler.h"

#include <algorithm>
#include <cstring>
#include <string>

#include "ucontext_pc.h"

namespace instructive {

namespace {

std::atomic<Sampler*> instance{nullptr};

// The smallest segment size HotSpot allows (CodeCacheSegmentSize is at least
// CodeEntryAlignment, itself at least 16), used when the real one can't be read.
constexpr int FALLBACK_LOG2_GRANULE = 4;

// "Lpackage/Class;" -> "package.Class"
std::string class_name(const char* signature) {
    std::string name(signature);
    if (name.size() >= 2 && name.front() == 'L' && name.back() == ';') {
        name = name.substr(1, name.size() - 2);
    }
    for (char& c : name) {
        if (c == '/') {
            c = '.';
        }
    }
    return name;
}

// "package.Class.name(signature)", or empty if JVMTI can't describe the method.
std::string method_name(JavaVM* vm, jvmtiEnv* jvmti, jmethodID method) {
    char* name = nullptr;
    char* signature = nullptr;
    if (jvmti->GetMethodName(method, &name, &signature, nullptr) != JVMTI_ERROR_NONE) {
        return {};
    }
    std::string result;
    jclass holder = nullptr;
    char* class_signature = nullptr;
    if (jvmti->GetMethodDeclaringClass(method, &holder) == JVMTI_ERROR_NONE &&
        jvmti->GetClassSignature(holder, &class_signature, nullptr) == JVMTI_ERROR_NONE) {
        result = class_name(class_signature) + "." + name + signature;
        jvmti->Deallocate(reinterpret_cast<unsigned char*>(class_signature));
    }
    // Events replayed by GenerateEvents run in the caller's JNI frame, where local
    // references would otherwise pile up.
    JNIEnv* env = nullptr;
    if (holder != nullptr && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK) {
        env->DeleteLocalRef(holder);
    }
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
    return result;
}

}  // namespace

Sampler::Sampler(JavaVM* vm, jvmtiEnv* jvmti) : vm_(vm), jvmti_(jvmti), timer_(&Sampler::on_tick) {
    // Any address inside libjvm identifies the library holding the VMStructs tables.
    if (vm_structs_.init(reinterpret_cast<const void*>(vm->functions->GetEnv))) {
        code_cache_.init(vm_structs_);
    }
    int log2_granule = code_cache_.log2_segment_size();
    compiled_methods_.reset(new CompiledMethodMap(log2_granule > 0 ? log2_granule : FALLBACK_LOG2_GRANULE, arena_));
    instance.store(this, std::memory_order_release);
    method_tracking_ = enable_method_tracking();
}

Sampler::~Sampler() {
    stop();
    jvmti_->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_COMPILED_METHOD_LOAD, nullptr);
    jvmti_->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_COMPILED_METHOD_UNLOAD, nullptr);
    jvmti_->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_THREAD_START, nullptr);
    jvmti_->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_THREAD_END, nullptr);
    instance.store(nullptr, std::memory_order_release);
    // A signal handler that loaded the instance before it was cleared may still be
    // reading the map.
    compiled_methods_->synchronize();
    jvmti_->DisposeEnvironment();
}

bool Sampler::enable_method_tracking() {
    jvmtiCapabilities capabilities;
    std::memset(&capabilities, 0, sizeof(capabilities));
    capabilities.can_generate_compiled_method_load_events = 1;
    if (jvmti_->AddCapabilities(&capabilities) != JVMTI_ERROR_NONE) {
        return false;
    }
    jvmtiEventCallbacks callbacks;
    std::memset(&callbacks, 0, sizeof(callbacks));
    callbacks.CompiledMethodLoad = &Sampler::on_compiled_method_load;
    callbacks.CompiledMethodUnload = &Sampler::on_compiled_method_unload;
    callbacks.ThreadStart = &Sampler::on_thread_start;
    callbacks.ThreadEnd = &Sampler::on_thread_end;
    if (jvmti_->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) {
        return false;
    }
#if defined(__linux__)
    // Each thread gets its own CPU timer, so the timer needs to hear about threads
    // starting and ending. Failing to enable these only leaves later threads unsampled.
    jvmti_->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, nullptr);
    jvmti_->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_END, nullptr);
#endif
    if (jvmti_->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_COMPILED_METHOD_LOAD, nullptr) !=
            JVMTI_ERROR_NONE ||
        jvmti_->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_COMPILED_METHOD_UNLOAD, nullptr) !=
            JVMTI_ERROR_NONE) {
        return false;
    }
    // Replay methods compiled before the events were enabled. Races with live events
    // only produce duplicates, which the map discards.
    return jvmti_->GenerateEvents(JVMTI_EVENT_COMPILED_METHOD_LOAD) == JVMTI_ERROR_NONE;
}

void JNICALL Sampler::on_compiled_method_load(jvmtiEnv* jvmti, jmethodID method, jint code_size,
                                              const void* code_addr, jint map_length,
                                              const jvmtiAddrLocationMap* map, const void* compile_info) {
    Sampler* sampler = instance.load(std::memory_order_acquire);
    if (sampler == nullptr || code_size <= 0) {
        return;
    }
    uintptr_t code_begin = reinterpret_cast<uintptr_t>(code_addr);
    uintptr_t code_end = code_begin + static_cast<uintptr_t>(code_size);
    uintptr_t range_begin = code_begin;
    uintptr_t range_end = code_end;
    int32_t comp_level = -1;
    int32_t compile_id = -1;
    // The nmethod is live while its load event is posted, so this is the one point
    // where reading it through the code heap is reliable. It supplies what JVMTI
    // doesn't report: the compile level and id, and the full blob extent, so PCs in
    // the nmethod's stubs and data are attributed to it too.
    CodeLocation location = sampler->code_cache_.lookup(code_begin);
    if (location.kind == CodeKind::Compiled) {
        comp_level = location.comp_level;
        compile_id = location.compile_id;
        uintptr_t blob = reinterpret_cast<uintptr_t>(location.blob);
        if (blob <= code_begin && code_end <= blob + static_cast<uintptr_t>(location.size)) {
            range_begin = blob;
            range_end = blob + static_cast<uintptr_t>(location.size);
        }
    }
    std::string name = method_name(sampler->vm_, jvmti, method);
    // Lengths are 16 bits. Names that long don't occur in practice; nmethods needing more
    // counters can, and are mapped and profiled for as much as the counters cover.
    if (name.size() > CompiledMethod::MAX_LENGTH) {
        size_t cut = CompiledMethod::MAX_LENGTH;
        // Don't split a (modified) UTF-8 sequence: back up over continuation bytes.
        while (cut > 0 && (static_cast<uint8_t>(name[cut]) & 0xC0) == 0x80) {
            --cut;
        }
        name.resize(cut);
    }
    size_t profile_length =
        std::min<size_t>(CompiledMethod::counters_for(range_end - code_begin), CompiledMethod::MAX_LENGTH);
    CompiledMethod* compiled = sampler->arena_.allocate(profile_length, name.size());
    if (compiled == nullptr) {
        // The arena is full: the method's samples will count as Stub.
        return;
    }
    compiled->code_begin = code_begin;
    compiled->code_size = static_cast<uint16_t>(std::min<size_t>(code_size, CompiledMethod::MAX_LENGTH));
    compiled->header_offset = static_cast<uint32_t>(code_begin - range_begin);
    compiled->method = method;
    compiled->comp_level = static_cast<int8_t>(comp_level);
    compiled->compile_id = compile_id;
    std::memcpy(compiled->name(), name.data(), name.size());
    ProfileArena::publish(compiled);
    sampler->compiled_methods_->insert(compiled);
}

void JNICALL Sampler::on_compiled_method_unload(jvmtiEnv* jvmti, jmethodID method, const void* code_addr) {
    Sampler* sampler = instance.load(std::memory_order_acquire);
    if (sampler != nullptr) {
        sampler->compiled_methods_->remove(method, reinterpret_cast<uintptr_t>(code_addr));
    }
}

// Both run on the thread that is starting or ending.
void JNICALL Sampler::on_thread_start(jvmtiEnv* jvmti, JNIEnv* env, jthread thread) {
    Sampler* sampler = instance.load(std::memory_order_acquire);
    if (sampler != nullptr) {
        sampler->timer_.thread_started();
    }
}

void JNICALL Sampler::on_thread_end(jvmtiEnv* jvmti, JNIEnv* env, jthread thread) {
    Sampler* sampler = instance.load(std::memory_order_acquire);
    if (sampler != nullptr) {
        sampler->timer_.thread_ended();
    }
}

void Sampler::on_tick(siginfo_t* info, void* ucontext) {
    Sampler* sampler = instance.load(std::memory_order_acquire);
    if (sampler == nullptr) {
        return;
    }
    uintptr_t pc = pc_from_ucontext(ucontext);
    CodeKind kind;
    {
        CompiledMethodMap::ReadGuard guard(*sampler->compiled_methods_);
        CompiledMethod* method = sampler->compiled_methods_->lookup(pc);
        if (method != nullptr) {
            method->record_sample(pc);
            kind = CodeKind::Compiled;
        } else {
            kind = sampler->code_cache_.classify(pc);
        }
    }
    sampler->kind_counts_[static_cast<size_t>(kind)].fetch_add(1, std::memory_order_relaxed);
}

void Sampler::reset_counts() {
    for (auto& kind_count : kind_counts_) {
        kind_count.store(0, std::memory_order_relaxed);
    }
    CompiledMethodMap::ReadGuard guard(*compiled_methods_);
    compiled_methods_->for_each([](CompiledMethod& method) { method.reset_counts(); });
}

bool Sampler::start() {
    std::lock_guard<std::mutex> guard(lock_);
    if (running_ || !timer_.start()) {
        return false;
    }
    running_ = true;
    return true;
}

bool Sampler::stop() {
    std::lock_guard<std::mutex> guard(lock_);
    if (!running_) {
        return false;
    }
    timer_.stop();
    running_ = false;
    return true;
}

bool Sampler::running() {
    std::lock_guard<std::mutex> guard(lock_);
    return running_;
}

}  // namespace instructive
