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

}  // namespace instructive
