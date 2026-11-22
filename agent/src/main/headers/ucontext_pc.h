#pragma once

#include <cstdint>
#include <signal.h>

#if defined(__APPLE__)
#include <sys/ucontext.h>
#else
#include <ucontext.h>
#endif

namespace instructive {

// The program counter of the interrupted code, from a signal handler's ucontext.
inline uintptr_t pc_from_ucontext(void* ucontext) {
    auto* context = static_cast<ucontext_t*>(ucontext);
#if defined(__linux__) && defined(__x86_64__)
    return static_cast<uintptr_t>(context->uc_mcontext.gregs[REG_RIP]);
#elif defined(__linux__) && defined(__aarch64__)
    return static_cast<uintptr_t>(context->uc_mcontext.pc);
#elif defined(__APPLE__) && defined(__x86_64__)
    return static_cast<uintptr_t>(context->uc_mcontext->__ss.__rip);
#elif defined(__APPLE__) && defined(__aarch64__)
    // Handles pointer authentication on arm64e, where __pc is opaque.
    return static_cast<uintptr_t>(__darwin_arm_thread_state64_get_pc(context->uc_mcontext->__ss));
#else
#error "pc_from_ucontext is not implemented for this platform"
#endif
}

// The address a frameless stub will return to, such as a vtable or itable stub, which
// the calling code reached with a call and which jumps on to its target without saving
// anything: on AArch64 the link register (x30), on x86-64 the word at the top of the
// stack, which the call pushed. Only meaningful while the PC is in such a stub. Reads the
// interrupted thread's own stack on x86-64, which is always mapped.
inline uintptr_t return_address_from_ucontext(void* ucontext) {
    auto* context = static_cast<ucontext_t*>(ucontext);
#if defined(__linux__) && defined(__x86_64__)
    return *reinterpret_cast<const uintptr_t*>(context->uc_mcontext.gregs[REG_RSP]);
#elif defined(__linux__) && defined(__aarch64__)
    return static_cast<uintptr_t>(context->uc_mcontext.regs[30]);
#elif defined(__APPLE__) && defined(__x86_64__)
    return *reinterpret_cast<const uintptr_t*>(context->uc_mcontext->__ss.__rsp);
#elif defined(__APPLE__) && defined(__aarch64__)
    // Handles pointer authentication on arm64e, where __lr is opaque.
    return static_cast<uintptr_t>(__darwin_arm_thread_state64_get_lr(context->uc_mcontext->__ss));
#else
#error "return_address_from_ucontext is not implemented for this platform"
#endif
}

// The length of the instruction compiled code calls a stub with: bl on AArch64, a
// rel32 call (how C2 calls through an inline cache) on x86-64. The call starts this far
// before the return address.
#if defined(__aarch64__)
constexpr uintptr_t CALL_LENGTH = 4;
#else
constexpr uintptr_t CALL_LENGTH = 5;
#endif

}  // namespace instructive
