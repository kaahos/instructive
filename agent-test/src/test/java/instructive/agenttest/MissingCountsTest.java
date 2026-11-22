package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.agent.Sampler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * With counter storage too small for every sampled method (the smallCounterStorageTest
 * task runs this with -Dinstructive.counterCapacity=1024), the methods that couldn't
 * claim counters say so, and still count their samples in their totals.
 */
@Tag("small-counter-storage")
class MissingCountsTest {

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void flagsMethodsWhoseCountsAreMissing() {
        Sampler.resetCounts();
        sample();
        List<String> missing = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        int[] counted = {0};
        Sampler.forEachMethod(profile -> {
            if (profile.hasMissingCounts()) {
                missing.add(profile.name());
                // Its samples are in its total, none in its counts.
                if (profile.total() == 0 || profile.sampled() != 0) {
                    wrong.add("missing " + profile.name() + " total " + profile.total() + " sampled " + profile.sampled());
                }
            } else if (profile.total() > 0) {
                counted[0]++;
                if (profile.methodSize() < 65535 && profile.sampled() != profile.total()) {
                    wrong.add(profile.name() + " total " + profile.total() + " sampled " + profile.sampled());
                }
            }
        });
        // 1024 bytes hold the counters of only the first few sampled methods.
        assertTrue(!missing.isEmpty(), () -> "nothing missing; " + counted[0] + " methods with counts");
        assertTrue(wrong.isEmpty(), wrong::toString);

        // A new window releases the storage and clears the flags.
        Sampler.resetCounts();
        List<String> stillMissing = new ArrayList<>();
        Sampler.forEachMethod(profile -> {
            if (profile.hasMissingCounts()) {
                stillMissing.add(profile.name());
            }
        });
        assertTrue(stillMissing.isEmpty(), stillMissing::toString);
    }

    // Enough different compiled code, sampled, to need more than 1024 bytes of counters.
    private static void sample() {
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 2_000_000_000L;
            long sink = 0;
            java.util.regex.Pattern words = java.util.regex.Pattern.compile("[a-z]+(\\d*)");
            while (System.nanoTime() < deadline) {
                List<Integer> list = new ArrayList<>();
                for (int i = 0; i < 1000; i++) {
                    list.add(i ^ (int) sink);
                }
                list.sort(null);
                java.util.Map<String, Integer> map = new java.util.HashMap<>();
                for (int i = 0; i < 200; i++) {
                    String key = String.format("key%d-%x", i, sink + i);
                    map.merge(key, i, Integer::sum);
                    if (words.matcher(key).find()) {
                        sink++;
                    }
                }
                sink += list.get(0) + map.size()
                        + java.math.BigInteger.valueOf(sink | 1).pow(20).mod(java.math.BigInteger.valueOf(1_000_003)).intValue();
            }
            assertTrue(sink != 42);
        } finally {
            Sampler.stop();
        }
    }
}
