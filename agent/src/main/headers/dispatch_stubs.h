#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace instructive {

// The address ranges of HotSpot's vtable and itable stubs: the code that makes a virtual
// or interface call where the call site has seen too many receiver types to inline it.
// A sample in one of these is attributed to the call that reached it, so the cost shows
// up at the call site in the user's code rather than in the dispatch mechanism.
//
// Stubs are reported by JVMTI DynamicCodeGenerated and never freed, so the table only
// grows. Several share one code blob, so they can't go in the CompiledMethodMap, whose
// slots each hold one method. The ranges are a sorted array, binary searched by the
// signal handler without locking. add() publishes a new array atomically and returns the
// one it replaced, which the caller frees once no reader can still be using it.
class DispatchStubs {
public:
    struct Range {
        uintptr_t begin;
        uintptr_t end;
    };

    DispatchStubs() = default;
    ~DispatchStubs();

    DispatchStubs(const DispatchStubs&) = delete;
    DispatchStubs& operator=(const DispatchStubs&) = delete;

    // Whether a DynamicCodeGenerated event with this name reports a dispatch stub.
    static bool is_dispatch_stub(const char* name);

    // Adds [begin, begin + length), returning the array it replaced (to free once no
    // reader can be using it), or null. A range already present is ignored. Not
    // async-signal-safe.
    struct Ranges;
    Ranges* add(uintptr_t begin, size_t length);

    // Whether `pc` is in a dispatch stub. Async-signal-safe; the caller must keep the
    // array it reads alive (in the Sampler, by holding the CompiledMethodMap's ReadGuard).
    bool contains(uintptr_t pc) const;

    size_t size() const;

    static void release(Ranges* ranges);

private:
    std::atomic<Ranges*> ranges_{nullptr};
    std::mutex lock_;
};

}  // namespace instructive
