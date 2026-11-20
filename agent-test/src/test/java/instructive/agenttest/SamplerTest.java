package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.agent.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SamplerTest {
    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void startsAndStops() {
        assertFalse(Sampler.isRunning());
        assertTrue(Sampler.start());
        assertTrue(Sampler.isRunning());
        assertTrue(Sampler.stop());
        assertFalse(Sampler.isRunning());
    }

    @Test
    void cpuTimerTicksOnlyWhileRunning() {
        long before = Sampler.cpuTicks();
        assertTrue(Sampler.start());
        burnCpu(200);
        Sampler.stop();
        long afterRunning = Sampler.cpuTicks();
        assertTrue(afterRunning > before, "expected CPU timer ticks while running");
        burnCpu(100);
        assertEquals(afterRunning, Sampler.cpuTicks());
    }

    private static void burnCpu(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000;
        long x = 0;
        while (System.nanoTime() < deadline) {
            x += x * 31 + 7;
        }
        if (x == 42) {
            System.out.println(x);
        }
    }

    @Test
    void startAndStopAreIdempotent() {
        assertTrue(Sampler.start());
        assertFalse(Sampler.start());
        assertTrue(Sampler.stop());
        assertFalse(Sampler.stop());
    }
}
