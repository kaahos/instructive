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

// Interns the JVMTI-allocated string `bytes`, then frees it.
uint32_t intern(StringDictionary& dictionary, jvmtiEnv* jvmti, char* bytes) {
    uint32_t code = dictionary.intern(bytes, std::strlen(bytes));
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(bytes));
    return code;
}

}  // namespace

Sampler::Sampler(JavaVM* vm, jvmtiEnv* jvmti, size_t counter_capacity)
    : vm_(vm), jvmti_(jvmti), counters_(counter_capacity), timer_(&Sampler::on_tick) {
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
    callbacks.DynamicCodeGenerated = &Sampler::on_dynamic_code_generated;
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
    // Dispatch stubs, so their samples can be attributed to the calls that reach them.
    // Without them, those samples just count as stubs.
    if (jvmti_->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_DYNAMIC_CODE_GENERATED, nullptr) ==
        JVMTI_ERROR_NONE) {
        jvmti_->GenerateEvents(JVMTI_EVENT_DYNAMIC_CODE_GENERATED);
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
    int32_t comp_level = -1;
    int32_t compile_id = -1;
    // The nmethod is live while its load event is posted, so this is the one point
    // where reading it through the code heap is reliable. It supplies what JVMTI
    // doesn't report: the compile level and id, and where the nmethod starts.
    CodeLocation location = sampler->code_cache_.lookup(code_begin);
    if (location.kind == CodeKind::Compiled) {
        comp_level = location.comp_level;
        compile_id = location.compile_id;
        uintptr_t blob = reinterpret_cast<uintptr_t>(location.blob);
        if (blob <= code_begin && code_end <= blob + static_cast<uintptr_t>(location.size)) {
            range_begin = blob;
        }
    }
    uint32_t class_code = StringDictionary::NO_CODE;
    uint32_t name_code = StringDictionary::NO_CODE;
    uint32_t signature_code = StringDictionary::NO_CODE;
    sampler->describe(jvmti, method, &class_code, &name_code, &signature_code);
    // Counters, and the mapped range, end with the code: JVMTI's code_size covers the
    // instructions and the stubs after them, which is everywhere a PC can be. The rest
    // of the blob, which on JDKs before 23 includes the nmethod's metadata (debug info,
    // relocations, oops), would only be counters that never count.
    size_t profile_length =
        std::min<size_t>(CompiledMethod::counters_for(code_end - code_begin), CompiledMethod::MAX_LENGTH);
    CompiledMethod* compiled = sampler->arena_.allocate(profile_length);
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
    compiled->class_code = class_code;
    compiled->name_code = name_code;
    compiled->signature_code = signature_code;
    ProfileArena::publish(compiled);
    sampler->compiled_methods_->insert(compiled);
}

// Interns the method's class signature, name and signature. Leaves a code NO_CODE if
// JVMTI can't supply its string.
void Sampler::describe(jvmtiEnv* jvmti, jmethodID method, uint32_t* class_code, uint32_t* name_code,
                       uint32_t* signature_code) {
    char* name = nullptr;
    char* signature = nullptr;
    if (jvmti->GetMethodName(method, &name, &signature, nullptr) == JVMTI_ERROR_NONE) {
        *name_code = intern(method_names_, jvmti, name);
        *signature_code = intern(method_signatures_, jvmti, signature);
    }
    jclass holder = nullptr;
    char* class_signature = nullptr;
    if (jvmti->GetMethodDeclaringClass(method, &holder) == JVMTI_ERROR_NONE &&
        jvmti->GetClassSignature(holder, &class_signature, nullptr) == JVMTI_ERROR_NONE) {
        *class_code = intern(class_signatures_, jvmti, class_signature);
    }
    // Events replayed by GenerateEvents run in the caller's JNI frame, where local
    // references would otherwise pile up.
    JNIEnv* env = nullptr;
    if (holder != nullptr && vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK) {
        env->DeleteLocalRef(holder);
    }
}

std::string Sampler::method_name(uint32_t class_code, uint32_t name_code, uint32_t signature_code) const {
    size_t class_length, name_length, signature_length;
    const char* class_signature = class_signatures_.lookup(class_code, &class_length);
    const char* name = method_names_.lookup(name_code, &name_length);
    const char* signature = method_signatures_.lookup(signature_code, &signature_length);
    if (class_signature == nullptr || name == nullptr || signature == nullptr) {
        return {};
    }
    // "Lpackage/Class;" -> "package.Class"
    std::string result;
    if (class_length >= 2 && class_signature[0] == 'L' && class_signature[class_length - 1] == ';') {
        result.assign(class_signature + 1, class_length - 2);
    } else {
        result.assign(class_signature, class_length);
    }
    std::replace(result.begin(), result.end(), '/', '.');
    result.push_back('.');
    result.append(name, name_length);
    result.append(signature, signature_length);
    return result;
}

void JNICALL Sampler::on_compiled_method_unload(jvmtiEnv* jvmti, jmethodID method, const void* code_addr) {
    Sampler* sampler = instance.load(std::memory_order_acquire);
    if (sampler != nullptr) {
        sampler->compiled_methods_->remove(method, reinterpret_cast<uintptr_t>(code_addr));
    }
}

// HotSpot posts vtable and itable stubs from its service thread, not while generating
// them, so this may wait for signal handlers before freeing the table it replaced.
void JNICALL Sampler::on_dynamic_code_generated(jvmtiEnv* jvmti, const char* name, const void* address,
                                                jint length) {
    Sampler* sampler = instance.load(std::memory_order_acquire);
    if (sampler == nullptr || length <= 0 || !DispatchStubs::is_dispatch_stub(name)) {
        return;
    }
    DispatchStubs::Ranges* replaced =
        sampler->dispatch_stubs_.add(reinterpret_cast<uintptr_t>(address), static_cast<size_t>(length));
    if (replaced != nullptr) {
        // A signal handler may still be searching it, under a ReadGuard.
        sampler->compiled_methods_->synchronize();
        DispatchStubs::release(replaced);
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
        if (method == nullptr && sampler->dispatch_stubs_.contains(pc)) {
            // A vtable or itable stub builds no frame: the caller's return address is
            // still in the link register (or on top of the stack), just after the call.
            // Attribute the sample to that call, if it's in a compiled method.
            uintptr_t call = return_address_from_ucontext(ucontext) - CALL_LENGTH;
            CompiledMethod* caller = sampler->compiled_methods_->lookup(call);
            if (caller != nullptr && call >= caller->code_begin && call < caller->code_end()) {
                pc = call;
                method = caller;
            }
        }
        if (method != nullptr) {
            // While counters are being reset, a sample is dropped rather than counted in
            // the total alone, so totals and per-PC counts always agree.
            if (!sampler->counters_.suspended()) {
                method->record_sample(pc, sampler->counters_);
            }
            kind = CodeKind::Compiled;
        } else {
            kind = sampler->code_cache_.classify(pc);
        }
    }
    sampler->kind_counts_[static_cast<size_t>(kind)].fetch_add(1, std::memory_order_relaxed);
}

void Sampler::reset_counts() {
    std::lock_guard<std::mutex> lock(reset_lock_);
    // No new claims, and no samples counted, until the counters are clear.
    counters_.suspend();
    auto release = [this](bool zero_totals) {
        CompiledMethodMap::ReadGuard guard(*compiled_methods_);
        compiled_methods_->for_each([zero_totals](CompiledMethod& method) {
            method.counter_offset.store(CounterArena::UNCLAIMED, std::memory_order_release);
            if (zero_totals) {
                method.count.store(0, std::memory_order_relaxed);
            }
        });
    };
    release(false);
    // A signal handler that saw claiming as not suspended may still have claimed, or be
    // counting into counters claimed before, until it releases its ReadGuard.
    compiled_methods_->synchronize();
    // Now none can, so this pass catches any claim made since the first, and the totals
    // can't move.
    release(true);
    counters_.clear();
    for (auto& kind_count : kind_counts_) {
        kind_count.store(0, std::memory_order_relaxed);
    }
    counters_.resume();
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
