#include "cpu_timer.h"

#include <cerrno>
#include <cstring>

#if defined(__APPLE__)
#include <sys/time.h>
#elif defined(__linux__)
#include <cstdlib>
#include <dirent.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace instructive {

namespace {

constexpr long INTERVAL_NANOS = 10'000'000;  // 10ms

// Written before our handler is installed and only read afterwards.
struct sigaction previous_action;
bool installed = false;

std::atomic<bool> active{false};
std::atomic<CpuTimer::Callback> callback{nullptr};
std::atomic<uint64_t> tick_count{0};

void handle_sigprof(int signo, siginfo_t* info, void* ucontext) {
    // Preserve errno for the interrupted code.
    int saved_errno = errno;
    if (active.load(std::memory_order_relaxed)) {
        tick_count.fetch_add(1, std::memory_order_relaxed);
        CpuTimer::Callback on_tick = callback.load(std::memory_order_acquire);
        if (on_tick != nullptr) {
            on_tick(info, ucontext);
        }
    }

    // Delegate to the handler that was registered before ours. SIG_DFL is not
    // delegated to because SIGPROF's default action terminates the process.
    if (previous_action.sa_flags & SA_SIGINFO) {
        if (previous_action.sa_sigaction != nullptr && previous_action.sa_sigaction != handle_sigprof) {
            previous_action.sa_sigaction(signo, info, ucontext);
        }
    } else if (previous_action.sa_handler != SIG_DFL && previous_action.sa_handler != SIG_IGN) {
        previous_action.sa_handler(signo);
    }
    errno = saved_errno;
}

bool install_handler() {
    if (installed) {
        return true;
    }
    struct sigaction action;
    std::memset(&action, 0, sizeof(action));
    action.sa_sigaction = handle_sigprof;
    action.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGPROF, &action, &previous_action) != 0) {
        return false;
    }
    installed = true;
    return true;
}

void uninstall_handler() {
    if (!installed) {
        return;
    }
    struct sigaction current;
    if (sigaction(SIGPROF, nullptr, &current) == 0 &&
        (current.sa_flags & SA_SIGINFO) && current.sa_sigaction == handle_sigprof) {
        sigaction(SIGPROF, &previous_action, nullptr);
        installed = false;
    }
}

}  // namespace

CpuTimer::CpuTimer(Callback on_tick) {
    callback.store(on_tick, std::memory_order_release);
}

CpuTimer::~CpuTimer() {
    if (active.load()) {
        stop();
    }
    uninstall_handler();
    callback.store(nullptr, std::memory_order_release);
}

uint64_t CpuTimer::ticks() {
    return tick_count.load(std::memory_order_relaxed);
}

#if defined(__linux__)

namespace {

// glibc's <signal.h> spells these only with _GNU_SOURCE, and older versions don't
// define the field name at all.
#ifndef SIGEV_THREAD_ID
#define SIGEV_THREAD_ID 4
#endif
#ifndef sigev_notify_thread_id
#define sigev_notify_thread_id _sigev_un._tid
#endif

// The CPU-time clock of thread `tid`, as glibc's pthread_getcpuclockid builds it
// (MAKE_THREAD_CPUCLOCK(tid, CPUCLOCK_SCHED)), but for any tid rather than a pthread_t.
clockid_t thread_cpu_clock(pid_t tid) {
    constexpr uint32_t CPUCLOCK_SCHED = 2;
    constexpr uint32_t CPUCLOCK_PERTHREAD_MASK = 4;
    return static_cast<clockid_t>((~static_cast<uint32_t>(tid) << 3) | CPUCLOCK_SCHED | CPUCLOCK_PERTHREAD_MASK);
}

pid_t current_tid() {
    return static_cast<pid_t>(syscall(SYS_gettid));
}

}  // namespace

bool CpuTimer::create_timer(pid_t tid) {
    struct sigevent event;
    std::memset(&event, 0, sizeof(event));
    event.sigev_notify = SIGEV_THREAD_ID;
    event.sigev_signo = SIGPROF;
    event.sigev_notify_thread_id = tid;
    timer_t timer;
    if (timer_create(thread_cpu_clock(tid), &event, &timer) != 0) {
        return false;
    }
    struct itimerspec spec;
    spec.it_interval.tv_sec = 0;
    spec.it_interval.tv_nsec = INTERVAL_NANOS;
    spec.it_value = spec.it_interval;
    if (timer_settime(timer, 0, &spec, nullptr) != 0) {
        int saved_errno = errno;
        timer_delete(timer);
        errno = saved_errno;
        return false;
    }
    auto inserted = timers_.emplace(tid, timer);
    if (!inserted.second) {
        // A thread that ended without thread_ended(), whose tid has been reused.
        timer_delete(inserted.first->second);
        inserted.first->second = timer;
    }
    return true;
}

bool CpuTimer::start() {
    if (!install_handler()) {
        return false;
    }
    std::lock_guard<std::mutex> guard(lock_);
    DIR* tasks = opendir("/proc/self/task");
    if (tasks == nullptr) {
        return false;
    }
    active.store(true);
    started_ = true;
    int last_error = 0;
    while (dirent* entry = readdir(tasks)) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        // A thread can exit between being listed and getting its timer: not an error.
        if (!create_timer(static_cast<pid_t>(std::strtol(entry->d_name, nullptr, 10)))) {
            last_error = errno;
        }
    }
    closedir(tasks);
    if (timers_.empty()) {
        active.store(false);
        started_ = false;
        errno = last_error != 0 ? last_error : ESRCH;
        return false;
    }
    return true;
}

bool CpuTimer::stop() {
    std::lock_guard<std::mutex> guard(lock_);
    active.store(false);
    started_ = false;
    bool deleted = true;
    for (auto& entry : timers_) {
        deleted &= timer_delete(entry.second) == 0;
    }
    timers_.clear();
    return deleted;
}

void CpuTimer::thread_started() {
    std::lock_guard<std::mutex> guard(lock_);
    if (started_) {
        create_timer(current_tid());
    }
}

void CpuTimer::thread_ended() {
    std::lock_guard<std::mutex> guard(lock_);
    auto timer = timers_.find(current_tid());
    if (timer != timers_.end()) {
        timer_delete(timer->second);
        timers_.erase(timer);
    }
}

#elif defined(__APPLE__)

namespace {
bool set_interval(long micros) {
    struct itimerval interval;
    interval.it_interval.tv_sec = 0;
    interval.it_interval.tv_usec = micros;
    interval.it_value = interval.it_interval;
    return setitimer(ITIMER_PROF, &interval, nullptr) == 0;
}
}  // namespace

bool CpuTimer::start() {
    if (!install_handler()) {
        return false;
    }
    active.store(true);
    if (!set_interval(INTERVAL_NANOS / 1000)) {
        active.store(false);
        return false;
    }
    return true;
}

bool CpuTimer::stop() {
    active.store(false);
    return set_interval(0);
}

// The macOS timer is process-wide.
void CpuTimer::thread_started() {}

void CpuTimer::thread_ended() {}

#else
#error "CpuTimer is only implemented for Linux and macOS"
#endif

}  // namespace instructive
