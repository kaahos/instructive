// Tests for CpuTimer's SIGPROF handling alongside another profiler's handler, built and
// run by the :agent:nativeTest task. Exits non-zero on failure.

#include "cpu_timer.h"

#include <atomic>
#include <cstdio>
#include <cstring>
#include <pthread.h>
#include <signal.h>
#include <time.h>

using instructive::CpuTimer;

namespace {

int failures = 0;

#define CHECK(condition)                                                                    \
    do {                                                                                    \
        if (!(condition)) {                                                                 \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
            ++failures;                                                                     \
        }                                                                                   \
    } while (0)

// Another profiler's handler, registered before ours: it counts what it gets, and checks
// it runs with its own mask (SIGUSR2) blocked.
std::atomic<int> foreign_signals{0};
std::atomic<int> foreign_unmasked{0};
std::atomic<int> foreign_saw_ours{0};
#if defined(__linux__)
// Labels the other profiler's timer signals, as our timers' tag labels ours.
char foreign_tag;
#endif
std::atomic<int> our_ticks{0};
std::atomic<int> ours_saw_foreign{0};

void foreign_handler(int, siginfo_t* info, void*) {
    foreign_signals.fetch_add(1);
    sigset_t mask;
    pthread_sigmask(SIG_BLOCK, nullptr, &mask);
    if (!sigismember(&mask, SIGUSR2)) {
        foreign_unmasked.fetch_add(1);
    }
#if defined(__linux__)
    if (info != nullptr && info->si_code == SI_TIMER && info->si_value.sival_ptr != &foreign_tag) {
        foreign_saw_ours.fetch_add(1);
    }
#else
    (void) info;
#endif
}

void on_tick(siginfo_t* info, void*) {
    our_ticks.fetch_add(1);
#if defined(__linux__)
    if (info != nullptr && info->si_value.sival_ptr == &foreign_tag) {
        ours_saw_foreign.fetch_add(1);
    }
#else
    (void) info;
#endif
}

void install_foreign_handler() {
    struct sigaction action;
    std::memset(&action, 0, sizeof(action));
    action.sa_sigaction = foreign_handler;
    action.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&action.sa_mask);
    sigaddset(&action.sa_mask, SIGUSR2);
    sigaction(SIGPROF, &action, nullptr);
}

bool current_handler_is(void (*handler)(int, siginfo_t*, void*)) {
    struct sigaction current;
    sigaction(SIGPROF, nullptr, &current);
    return (current.sa_flags & SA_SIGINFO) != 0 && current.sa_sigaction == handler;
}

double cpu_seconds() {
    struct timespec now;
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &now);
    return now.tv_sec + now.tv_nsec / 1e9;
}

void burn(double seconds) {
    volatile unsigned long sink = 0;
    double end = cpu_seconds() + seconds;
    while (cpu_seconds() < end) {
        for (int i = 0; i < 10000; ++i) {
            sink = sink + i;
        }
    }
}

void shares_sigprof_with_another_handler() {
    install_foreign_handler();
    {
        CpuTimer timer(&on_tick);
        CHECK(timer.start());
#if defined(__linux__)
        // Another profiler's timer, labelled with its own tag.
        struct sigevent event;
        std::memset(&event, 0, sizeof(event));
        event.sigev_notify = SIGEV_SIGNAL;
        event.sigev_signo = SIGPROF;
        event.sigev_value.sival_ptr = &foreign_tag;
        timer_t foreign;
        CHECK(timer_create(CLOCK_PROCESS_CPUTIME_ID, &event, &foreign) == 0);
        struct itimerspec spec;
        spec.it_interval.tv_sec = 0;
        spec.it_interval.tv_nsec = 7'000'000;
        spec.it_value = spec.it_interval;
        CHECK(timer_settime(foreign, 0, &spec, nullptr) == 0);
        burn(1.0);
        timer_delete(foreign);
        // Sent rather than from a timer: not ours either.
        int before = foreign_signals.load();
        raise(SIGPROF);
        CHECK(foreign_signals.load() == before + 1);
#else
        burn(1.0);
#endif
        CHECK(timer.stop());
        // Stopped: on macOS, where signals can't be told apart, every SIGPROF is passed on.
        int before_stopped = foreign_signals.load();
        raise(SIGPROF);
        CHECK(foreign_signals.load() == before_stopped + 1);
        CHECK(our_ticks.load() > 20);
        CHECK(foreign_signals.load() > 0);
        CHECK(foreign_unmasked.load() == 0);
        CHECK(foreign_saw_ours.load() == 0);
        CHECK(ours_saw_foreign.load() == 0);
    }
    // Destroyed: the other handler is back.
    CHECK(current_handler_is(foreign_handler));
    std::printf("  ours %d ticks, the other handler %d signals\n", our_ticks.load(), foreign_signals.load());
}

// With no handler before ours (SIG_DFL, which would terminate the process), a SIGPROF
// that isn't ours is dropped rather than re-raised.
void drops_what_it_cannot_pass_on() {
    signal(SIGPROF, SIG_DFL);
    {
        CpuTimer timer(&on_tick);
        CHECK(timer.start());
        CHECK(timer.stop());
        raise(SIGPROF);  // still alive
    }
    struct sigaction current;
    sigaction(SIGPROF, nullptr, &current);
    CHECK((current.sa_flags & SA_SIGINFO) == 0 && current.sa_handler == SIG_DFL);
}

}  // namespace

int main() {
    shares_sigprof_with_another_handler();
    drops_what_it_cannot_pass_on();
    if (failures != 0) {
        std::fprintf(stderr, "%d checks failed\n", failures);
        return 1;
    }
    std::printf("cpu_timer_test: all checks passed\n");
    return 0;
}
