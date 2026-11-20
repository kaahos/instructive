package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.agent.CodeKind;
import instructive.agent.InstructionProfile;
import instructive.agent.Sampler;
import java.lang.reflect.Method;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CodeCacheTest {
    private static final String CLASS = "instructive.agenttest.CodeCacheTest.";
    // Excluded from JIT compilation in agent-test/build.gradle.kts.
    private static final String INTERPRETED = CLASS + "interpretedLoop(J)J";
    private static final String COMPILED = CLASS + "compiledLoop(J)J";
    private static final String LONG_SIGNATURE = CLASS + "longSignature(";

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void codeCacheLayoutIsReadable() {
        assertTrue(Sampler.codeCacheAvailable());
    }

    @Test
    void tracksCompiledMethods() {
        assertTrue(Sampler.methodTrackingAvailable());
        // Methods compiled before the library loaded are replayed by GenerateEvents.
        List<InstructionProfile> methods = liveMethods();
        assertFalse(methods.isEmpty());
        assertTrue(methods.stream().allMatch(method -> !method.name().isEmpty() && method.address() != 0),
                methods::toString);
    }

    @Test
    void visitsOnlyLiveMethods() {
        int[] visited = {0};
        Sampler.forEachMethod(profile -> {
            visited[0]++;
            assertTrue(profile.isLive(), profile::toString);
            assertTrue(profile.address() != 0 && profile.profileLength() >= profile.methodSize(), profile::toString);
            assertFalse(profile.name().isEmpty(), profile::toString);
        });
        assertTrue(visited[0] > 0);
    }

    @Test
    void countsSamplesPerCompiledMethod() {
        long before = Sampler.kindCounts().get(CodeKind.COMPILED);
        sample(() -> compiledLoop(500));
        List<InstructionProfile> compiled = methodsNamed(COMPILED);
        assertFalse(compiled.isEmpty(), () -> "no profile for " + COMPILED);
        // Every loaded compilation has a profile, including tiers that were never sampled.
        assertTrue(compiled.stream().anyMatch(method -> method.total() > 0), compiled::toString);
        for (InstructionProfile method : compiled) {
            assertTrue(method.compLevel() >= 1 && method.compLevel() <= 4, method::toString);
            assertTrue(method.compileId() > 0 && method.methodSize() > 0 && method.address() != 0, method::toString);
        }
        long total = compiled.stream().mapToLong(InstructionProfile::total).sum();
        long compiledSamples = Sampler.kindCounts().get(CodeKind.COMPILED) - before;
        assertTrue(total * 2 > compiledSamples, () -> total + " of " + compiledSamples + " compiled samples");
    }

    @Test
    void profilesInstructions() {
        sample(() -> compiledLoop(500));
        InstructionProfile hottest = hottest(COMPILED);
        assertTrue(hottest.total() > 0 && hottest.profileLength() >= hottest.methodSize(), hottest::toString);
        assertTrue(hottest.isLive() && hottest.compLevel() >= 1 && hottest.compileId() > 0, hottest::toString);
        assertEquals(COMPILED, hottest.name());
        // Stopped, so nothing changed while copying, and executable PCs all lie in the profile.
        assertEquals(hottest.total(), hottest.sampled());
        CharBuffer counts = hottest.counts();
        assertEquals(hottest.profileLength(), counts.remaining());
        long sampledOffsets = 0;
        for (int offset = 0; offset < counts.limit(); offset++) {
            assertEquals(hottest.count(offset), counts.get(offset));
            sampledOffsets += counts.get(offset) > 0 ? 1 : 0;
        }
        assertEquals(hottest.sampled(), sum(counts));
        // The loop dominates, so samples concentrate on a few instructions.
        long offsets = sampledOffsets;
        assertTrue(offsets > 0 && offsets < hottest.methodSize() / 2, () -> offsets + " sampled offsets");
    }

    @Test
    void snapshotsAreIndependentCopies() {
        sample(() -> compiledLoop(300));
        List<InstructionProfile> snapshots = methodsNamed(COMPILED);
        InstructionProfile snapshot = hottest(COMPILED);
        assertSame(snapshot, snapshot.snapshot());
        long total = snapshot.total();
        long sampled = snapshot.sampled();
        CharBuffer counts = snapshot.counts();
        long snapshotTotal = snapshots.stream().mapToLong(InstructionProfile::total).sum();
        // More samples land in the live profiles, though not necessarily in the same
        // compilation (an OSR or higher tier version may take over), but not in the copies.
        sample(() -> compiledLoop(300));
        assertEquals(total, snapshot.total());
        assertEquals(sampled, snapshot.sampled());
        assertEquals(sampled, sum(counts));
        assertEquals(snapshotTotal, snapshots.stream().mapToLong(InstructionProfile::total).sum());
        long liveTotal = methodsNamed(COMPILED).stream().mapToLong(InstructionProfile::total).sum();
        assertTrue(liveTotal > snapshotTotal, () -> liveTotal + " <= " + snapshotTotal);
    }

    @Test
    void countsViewIsLiveWithinConsumer() {
        sample(() -> compiledLoop(300));
        InstructionProfile hottest = hottest(COMPILED);
        long[] sums = new long[2];
        Sampler.forEachMethod(profile -> {
            if (profile.address() == hottest.address() && profile.compileId() == hottest.compileId()) {
                // The flyweight and its view belong to the consumer until it returns and show
                // the live counts, so samples taken now show up through them.
                CharBuffer counts = profile.counts();
                sums[0] = sum(counts);
                sample(() -> compiledLoop(300));
                sums[1] = sum(counts);
            }
        });
        assertTrue(sums[0] > 0 && sums[1] > sums[0], () -> sums[0] + " -> " + sums[1]);
    }

    @Test
    void resetsCounts() {
        sample(() -> compiledLoop(300));
        assertTrue(liveMethods().stream().anyMatch(method -> method.total() > 0));
        Sampler.resetCounts();
        assertTrue(Sampler.kindCounts().values().stream().allMatch(count -> count == 0), Sampler.kindCounts()::toString);
        assertTrue(liveMethods().stream().allMatch(method -> method.total() == 0 && method.sampled() == 0));
        // Counting resumes after a reset.
        sample(() -> compiledLoop(300));
        InstructionProfile hottest = hottest(COMPILED);
        assertTrue(hottest.total() > 0 && hottest.total() == hottest.sampled(), hottest::toString);
    }

    @Test
    void enumeratesWhileRunning() {
        assertTrue(Sampler.start());
        compiledLoop(300);
        assertFalse(methodsNamed(COMPILED).isEmpty());
    }

    @Test
    void keepsLongNamesWhole() {
        ConcurrentHashMap<?, ?> m = null;
        sample(() -> longSignature(m, m, m, m, m, m, m, m, 500));
        String name = LONG_SIGNATURE + "Ljava/util/concurrent/ConcurrentHashMap;".repeat(8) + "J)J";
        assertTrue(methodsNamed(name).stream().anyMatch(method -> method.total() > 0),
                () -> "no samples of " + name + " in " + sampledInThisClass());
    }

    @Test
    void decodesTwoByteCharacters() {
        sample(() -> éééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééé(500));
        // Take the identifier from the class rather than counting its characters here.
        String identifier = Arrays.stream(CodeCacheTest.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(candidate -> candidate.startsWith("é"))
                .findFirst()
                .orElseThrow();
        assertTrue(identifier.chars().allMatch(c -> c == 'é') && identifier.length() > 100, identifier);
        String name = CLASS + identifier + "(J)J";
        assertTrue(methodsNamed(name).stream().anyMatch(method -> method.total() > 0),
                () -> "no samples of " + name + " in " + sampledInThisClass());
    }

    @Test
    void decodesSupplementaryCharacters() {
        // Outside the BMP: modified UTF-8 stores each half of the surrogate pair as three bytes.
        sample(() -> 𝒜𝒜𝒜(500));
        String name = CLASS + "𝒜𝒜𝒜(J)J";
        assertTrue(methodsNamed(name).stream().anyMatch(method -> method.total() > 0),
                () -> "no samples of " + name + " in " + sampledInThisClass());
    }

    @Test
    void detectsInterpretedCode() {
        long before = Sampler.kindCounts().get(CodeKind.INTERPRETED);
        sample(() -> interpretedLoop(300));
        Map<CodeKind, Long> counts = Sampler.kindCounts();
        assertTrue(counts.get(CodeKind.INTERPRETED) > before, counts::toString);
        assertTrue(methodsNamed(INTERPRETED).isEmpty(), () -> INTERPRETED + " should not have been compiled");
    }

    /** Snapshots of every loaded compiled method. */
    private static List<InstructionProfile> liveMethods() {
        List<InstructionProfile> methods = new ArrayList<>();
        Sampler.forEachMethod(profile -> methods.add(profile.snapshot()));
        return methods;
    }

    private static List<String> sampledInThisClass() {
        return liveMethods().stream()
                .filter(method -> method.name().startsWith(CLASS) && method.total() > 0)
                .map(method -> method.name() + "=" + method.total())
                .toList();
    }

    private static List<InstructionProfile> methodsNamed(String name) {
        return liveMethods().stream().filter(method -> name.equals(method.name())).toList();
    }

    private static InstructionProfile hottest(String name) {
        return methodsNamed(name).stream().max(Comparator.comparingLong(InstructionProfile::total)).orElseThrow();
    }

    private static long sum(CharBuffer counts) {
        long sum = 0;
        for (int offset = 0; offset < counts.limit(); offset++) {
            sum += counts.get(offset);
        }
        return sum;
    }

    private static void sample(Runnable work) {
        assertTrue(Sampler.start());
        try {
            work.run();
        } finally {
            Sampler.stop();
        }
    }

    static long compiledLoop(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000;
        long x = 0;
        while (true) {
            for (int i = 0; i < 100_000; i++) {
                x = x * 31 + i;
            }
            if (System.nanoTime() >= deadline) {
                return x;
            }
        }
    }

    static long longSignature(
            ConcurrentHashMap<?, ?> a, ConcurrentHashMap<?, ?> b, ConcurrentHashMap<?, ?> c, ConcurrentHashMap<?, ?> d,
            ConcurrentHashMap<?, ?> e, ConcurrentHashMap<?, ?> f, ConcurrentHashMap<?, ?> g, ConcurrentHashMap<?, ?> h,
            long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000;
        long x = 0;
        while (true) {
            for (int i = 0; i < 100_000; i++) {
                x = x * 31 + i;
            }
            if (System.nanoTime() >= deadline) {
                return x;
            }
        }
    }

    // Over 100 two-byte characters.
    static long éééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééé(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000;
        long x = 0;
        while (true) {
            for (int i = 0; i < 100_000; i++) {
                x = x * 31 + i;
            }
            if (System.nanoTime() >= deadline) {
                return x;
            }
        }
    }

    // U+1D49C MATHEMATICAL SCRIPT CAPITAL A, a supplementary Java identifier character.
    static long 𝒜𝒜𝒜(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000;
        long x = 0;
        while (true) {
            for (int i = 0; i < 100_000; i++) {
                x = x * 31 + i;
            }
            if (System.nanoTime() >= deadline) {
                return x;
            }
        }
    }

    static long interpretedLoop(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000;
        long x = 0;
        while (true) {
            for (int i = 0; i < 10_000; i++) {
                x = x * 31 + i;
            }
            if (System.nanoTime() >= deadline) {
                return x;
            }
        }
    }
}
