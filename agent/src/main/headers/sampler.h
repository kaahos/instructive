#pragma once

#include <atomic>
#include <cstdint>
#include <jni.h>
#include <jvmti.h>
#include <memory>
#include <mutex>
#include <string>

#include "code_cache.h"
#include "compiled_method_map.h"
#include "counter_arena.h"
#include "dispatch_stubs.h"
#include "cpu_timer.h"
#include "profile_arena.h"
#include "string_dictionary.h"
#include "vm_structs.h"

namespace instructive {

// Native sampler state. One instance exists per load of the library: it is created
// in JNI_OnLoad and destroyed in JNI_OnUnload. Sampling itself is controlled with
// start() and stop(), which are exposed to Java through instructive.agent.Sampler.
//
// Compiled methods are tracked from JVMTI CompiledMethodLoad/Unload events in a
// CompiledMethodMap, maintained on ordinary threads. On each CPU timer tick the signal
// handler looks the interrupted PC up in that map and bumps the method's count and the
// count for the PC's offset within the method (CompiledMethod::record_sample). A PC in
// a vtable or itable stub (DispatchStubs) is attributed to the call that reached the
// stub, in the calling compiled method: the cost of virtual dispatch belongs to the call
// site in the user's code. It never reads the code heap or method metadata, which can
// change or be freed under a signal handler; other PCs not in the map are classified
// with CodeCache::classify, which only uses bounds fixed at startup. Only one Sampler may exist at a time because the
// signal handler and JVMTI callbacks reach it through a global.
class Sampler {
public:
    // Per-PC counters for this many bytes of code (on AArch64, where a counter covers an
    // instruction, a quarter as much): 256 MiB of counters' worth of code.
    static constexpr size_t DEFAULT_COUNTER_CAPACITY = (size_t{256} << 20) >> CompiledMethod::PC_SHIFT;

    // `counter_capacity` bytes are reserved for per-PC counters.
    Sampler(JavaVM* vm, jvmtiEnv* jvmti, size_t counter_capacity = DEFAULT_COUNTER_CAPACITY);
    ~Sampler();

    Sampler(const Sampler&) = delete;
    Sampler& operator=(const Sampler&) = delete;

    // Both are idempotent and return whether the call changed the state. start() also
    // returns false if the CPU timer could not be started, leaving errno set and the
    // sampler stopped.
    bool start();
    bool stop();

    bool running();

    // Whether the code cache layout could be read from VMStructs. If not, samples
    // outside tracked compiled methods are CodeKind::Unknown and compiled methods
    // have no compile level or id.
    bool code_cache_available() const { return code_cache_.available(); }

    const CodeCache& code_cache() const { return code_cache_; }

    // Whether JVMTI compiled method events could be enabled.
    bool method_tracking() const { return method_tracking_; }

    CompiledMethodMap& compiled_methods() { return *compiled_methods_; }

    // The storage holding every CompiledMethod record, shared with Java.
    const ProfileArena& arena() const { return arena_; }

    // The storage holding the per-PC counters of sampled methods, shared with Java.
    const CounterArena& counters() const { return counters_; }

    // Samples per CodeKind, indexed by its value.
    uint64_t kind_count(CodeKind kind) const {
        return kind_counts_[static_cast<size_t>(kind)].load(std::memory_order_relaxed);
    }

    // Starts a new sampling window: zeroes the per-kind counts and every tracked method's
    // total, and releases all per-PC counters, which methods claim again when next
    // sampled. Samples in compiled code taken while it runs are dropped.
    void reset_counts();

    // The method with these dictionary codes (a CompiledMethod's class_code, name_code
    // and signature_code) as "package.Class.name(signature)" in modified UTF-8, or empty
    // if any code is unknown. Not async-signal-safe.
    std::string method_name(uint32_t class_code, uint32_t name_code, uint32_t signature_code) const;

    // The dictionaries holding every compiled method's class signature, name and
    // signature, each string once.
    const StringDictionary& class_signatures() const { return class_signatures_; }
    const StringDictionary& method_names() const { return method_names_; }
    const StringDictionary& method_signatures() const { return method_signatures_; }

private:
    static void on_tick(siginfo_t* info, void* ucontext);
    static void JNICALL on_compiled_method_load(jvmtiEnv* jvmti, jmethodID method, jint code_size,
                                                const void* code_addr, jint map_length,
                                                const jvmtiAddrLocationMap* map, const void* compile_info);
    static void JNICALL on_compiled_method_unload(jvmtiEnv* jvmti, jmethodID method, const void* code_addr);
    static void JNICALL on_dynamic_code_generated(jvmtiEnv* jvmti, const char* name, const void* address, jint length);
    static void JNICALL on_thread_start(jvmtiEnv* jvmti, JNIEnv* env, jthread thread);
    static void JNICALL on_thread_end(jvmtiEnv* jvmti, JNIEnv* env, jthread thread);

    bool enable_method_tracking();
    void describe(jvmtiEnv* jvmti, jmethodID method, uint32_t* class_code, uint32_t* name_code,
                  uint32_t* signature_code);

    JavaVM* vm_;
    jvmtiEnv* jvmti_;
    VMStructs vm_structs_;
    CodeCache code_cache_;
    // Declared before the map, which returns records to it.
    ProfileArena arena_;
    CounterArena counters_;
    // vtable and itable stubs, whose samples are attributed to the call that reached them.
    DispatchStubs dispatch_stubs_;
    // Serialises reset_counts().
    std::mutex reset_lock_;
    // Records refer to these by code; strings are never removed, so codes in records
    // copied to Java stay valid for as long as the library is loaded.
    StringDictionary class_signatures_;
    StringDictionary method_names_;
    StringDictionary method_signatures_;
    std::unique_ptr<CompiledMethodMap> compiled_methods_;
    bool method_tracking_ = false;
    std::atomic<uint64_t> kind_counts_[CODE_KIND_COUNT] = {};
    CpuTimer timer_;
    std::mutex lock_;
    bool running_ = false;
};

}  // namespace instructive
