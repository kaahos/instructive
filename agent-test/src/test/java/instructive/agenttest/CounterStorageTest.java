package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.Sampler;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Per-instruction counts are only kept for sampled methods, claimed on their first sample
 * and released when the counts are reset.
 */
class CounterStorageTest {
    private static final String COMPILED = "instructive.agenttest.CodeCacheTest.compiledLoop(J)J";

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void unsampledMethodsHaveNoCounts() {
        Sampler.resetCounts();
        List<String> wrong = new ArrayList<>();
        int[] methods = {0};
        Sampler.forEachMethod(profile -> {
            methods[0]++;
            CharBuffer counts = profile.counts();
            boolean zero = true;
            for (int i = 0; i < counts.limit(); i++) {
                zero &= counts.get(i) == 0;
            }
            if (profile.total() != 0 || profile.sampled() != 0 || !zero || profile.hasMissingCounts()
                    || counts.limit() != profile.profileLength() / profile.bytesPerCount()) {
                wrong.add(profile.name());
            }
        });
        assertTrue(methods[0] > 100);
        assertTrue(wrong.isEmpty(), wrong::toString);
    }

    @Test
    void totalsAndCountsAgree() {
        Sampler.resetCounts();
        sample(() -> CodeCacheTest.compiledLoop(300));
        List<String> wrong = new ArrayList<>();
        int[] sampled = {0};
        Sampler.forEachMethod(profile -> {
            if (profile.total() == 0) {
                return;
            }
            sampled[0]++;
            // Every sample in a method is in its counts, unless it lands past the 65535
            // counts a profile covers.
            boolean covered = profile.methodSize() < 65535;
            if (profile.hasMissingCounts() || (covered && profile.sampled() != profile.total())) {
                wrong.add(profile.name() + " total " + profile.total() + " sampled " + profile.sampled()
                        + (profile.hasMissingCounts() ? " missing" : ""));
            }
        });
        assertTrue(sampled[0] > 0);
        assertTrue(wrong.isEmpty(), wrong::toString);
    }

    @Test
    void resettingReleasesCountsUntilTheNextSample() {
        Sampler.resetCounts();
        sample(() -> CodeCacheTest.compiledLoop(300));
        InstructionProfile before = compiledLoop();
        assertTrue(before.total() > 0 && before.sampled() == before.total(), before::toString);

        Sampler.resetCounts();
        InstructionProfile after = compiledLoop();
        assertEquals(0, after.total());
        assertEquals(0, after.sampled());
        assertFalse(after.hasMissingCounts());
        // The snapshot taken before keeps its counts.
        assertTrue(before.sampled() > 0);

        sample(() -> CodeCacheTest.compiledLoop(300));
        InstructionProfile again = compiledLoop();
        assertTrue(again.total() > 0 && again.sampled() == again.total(), again::toString);
    }

    private static InstructionProfile compiledLoop() {
        InstructionProfile[] found = new InstructionProfile[1];
        Sampler.forEachMethod(profile -> {
            if (COMPILED.equals(profile.name()) && (found[0] == null || profile.total() > found[0].total())) {
                found[0] = profile.snapshot();
            }
        });
        assertNotNull(found[0], "no compiled " + COMPILED);
        return found[0];
    }

    private static void sample(Runnable work) {
        assertTrue(Sampler.start());
        try {
            work.run();
        } finally {
            Sampler.stop();
        }
    }
}
