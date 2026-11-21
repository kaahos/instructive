package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.Sampler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HottestMethodsTest {
    private static final String COMPILED = "instructive.agenttest.CodeCacheTest.compiledLoop(J)J";

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void hottestIsTheDominantMethod() {
        Sampler.resetCounts();
        sample(() -> CodeCacheTest.compiledLoop(500));
        List<InstructionProfile> hottest = hottest(1);
        assertEquals(1, hottest.size());
        assertEquals(COMPILED, hottest.get(0).name(), hottest::toString);
    }

    @Test
    void matchesAFullSortForEveryK() {
        Sampler.resetCounts();
        sample(HottestMethodsTest::mixedWork);
        // Stopped, so totals don't change between passes.
        List<Long> expected = new ArrayList<>();
        Sampler.forEachMethod(profile -> {
            if (profile.total() > 0) {
                expected.add(profile.total());
            }
        });
        expected.sort(Comparator.reverseOrder());
        // How many nmethods get samples depends on inlining; a few are enough to exercise
        // every k from none to more than there are.
        assertTrue(expected.size() >= 3, () -> "only " + expected.size() + " sampled methods");
        for (int k = 0; k <= expected.size() + 1; k++) {
            List<InstructionProfile> hottest = hottest(k);
            List<Long> totals = hottest.stream().map(InstructionProfile::total).toList();
            int kk = k;
            assertEquals(expected.subList(0, Math.min(k, expected.size())), totals, () -> "k=" + kk);
            assertTrue(hottest.stream().allMatch(InstructionProfile::isLive));
        }
    }

    @Test
    void worksWhileSampling() {
        assertTrue(Sampler.start());
        mixedWork();
        List<InstructionProfile> hottest = hottest(10);
        assertFalse(hottest.isEmpty());
        assertTrue(hottest.size() <= 10);
        for (int i = 1; i < hottest.size(); i++) {
            assertTrue(hottest.get(i - 1).total() >= hottest.get(i).total(), hottest::toString);
        }
        assertTrue(hottest.stream().allMatch(profile -> profile.total() > 0));
    }

    @Test
    void excludesMethodsWithoutSamples() {
        Sampler.resetCounts();
        assertEquals(List.of(), hottest(5));
        assertEquals(List.of(), hottest(0));
    }

    @Test
    void rejectsNegativeK() {
        assertThrows(IllegalArgumentException.class, () -> Sampler.forEachHottestMethod(-1, profile -> {}));
    }

    @Test
    void passesTheLiveFlyweightInDescendingOrder() {
        Sampler.resetCounts();
        sample(HottestMethodsTest::mixedWork);
        List<InstructionProfile> seen = new ArrayList<>();
        List<Long> totals = new ArrayList<>();
        Sampler.forEachHottestMethod(5, profile -> {
            // The same flyweight each time, read within the consumer's scope.
            seen.add(profile);
            totals.add(profile.total());
            assertTrue(profile.isLive() && profile.total() > 0, profile::toString);
        });
        assertFalse(totals.isEmpty());
        assertTrue(seen.stream().allMatch(profile -> profile == seen.get(0)), "one reused flyweight");
        for (int i = 1; i < totals.size(); i++) {
            assertTrue(totals.get(i - 1) >= totals.get(i), totals::toString);
        }
    }

    /** Snapshots of the k hottest methods, in the order the consumer sees them. */
    private static List<InstructionProfile> hottest(int k) {
        List<InstructionProfile> hottest = new ArrayList<>();
        Sampler.forEachHottestMethod(k, profile -> hottest.add(profile.snapshot()));
        return hottest;
    }

    private static void sample(Runnable work) {
        assertTrue(Sampler.start());
        try {
            work.run();
        } finally {
            Sampler.stop();
        }
    }

    // Spreads samples over many methods: arithmetic, sorting, hashing and strings.
    private static void mixedWork() {
        Random random = new Random(42);
        long deadline = System.nanoTime() + 800_000_000L;
        long sink = 0;
        while (System.nanoTime() < deadline) {
            sink += CodeCacheTest.compiledLoop(2);
            int[] values = random.ints(5_000).toArray();
            Arrays.sort(values);
            sink += values[0];
            Map<String, Integer> map = new HashMap<>();
            for (int i = 0; i < 2_000; i++) {
                map.merge("key" + (i % 300), i, Integer::sum);
            }
            sink += map.size();
            sink += String.join(",", map.keySet()).hashCode();
        }
        if (sink == 42) {
            System.out.println(sink);
        }
    }
}
