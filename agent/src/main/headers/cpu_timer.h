#pragma once

#include <atomic>
#include <cstdint>
#include <signal.h>

#if defined(__linux__)
#include <mutex>
#include <sys/types.h>
#include <time.h>
#include <unordered_map>
#endif

namespace instructive {

// Delivers SIGPROF every 10ms of CPU time while started.
//
// Linux: one POSIX timer per thread, on that thread's CPU-time clock and directed at
// that thread (SIGEV_THREAD_ID), so each signal interrupts the thread that used the
// CPU. Threads running when start() is called are found in /proc/self/task; threads
// started later get a timer from thread_started(), and lose it in thread_ended().
//
// macOS: there are no per-thread interval timers, so setitimer(ITIMER_PROF) gives one
// process-wide timer, delivered to whichever thread the kernel picks; thread_started()
// and thread_ended() do nothing.
//
// The SIGPROF handler is installed on the first start() and stays installed across
// stop()/start(). It does not chain to a handler registered before it: other sources
// of SIGPROF may be present, and their signals are not ours to interpret. On Linux,
// every timer labels its signals with a tag in sigev_value, and the handler ignores
// any SIGPROF that isn't a timer signal carrying that tag, such as one sent with kill()
// or raised by someone else's timer. setitimer signals carry no value, so on macOS
// every SIGPROF received while started is taken as ours. On destruction the previous
// handler is restored, if ours is still the current one. The handler state is
// process-wide, so only one CpuTimer may exist at a time.
class CpuTimer {
public:
    // Called from the SIGPROF handler for each of our signals while the timer is
    // started. Must be async-signal-safe.
    using Callback = void (*)(siginfo_t* info, void* ucontext);

    explicit CpuTimer(Callback callback);
    ~CpuTimer();

    CpuTimer(const CpuTimer&) = delete;
    CpuTimer& operator=(const CpuTimer&) = delete;

    // Return false and leave errno set on failure. Not idempotent; callers track state.
    bool start();
    bool stop();

    // Gives the calling thread its own timer if started (Linux). Call from each thread
    // that starts after start(), e.g. from JVMTI ThreadStart, which runs on it. Threads
    // that start some other way, such as native threads, go unsampled until the next
    // start().
    void thread_started();

    // Deletes the calling thread's timer (Linux). Call as the thread ends, e.g. from
    // JVMTI ThreadEnd.
    void thread_ended();

    // Number of our SIGPROF signals handled while the timer was started.
    static uint64_t ticks();

private:
#if defined(__linux__)
    // Requires lock_. Replaces any timer left for a previous thread with the same tid.
    bool create_timer(pid_t tid);

    std::mutex lock_;
    bool started_ = false;
    std::unordered_map<pid_t, timer_t> timers_;
#endif
};

}  // namespace instructive
