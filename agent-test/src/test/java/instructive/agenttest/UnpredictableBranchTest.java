package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.analyse.UnpredictableBranchRule;
import instructive.analyse.UnpredictableBranches;
import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Finds the mispredicting switch, and if/else chain, in a loop over random data, and not
 * the same code over the same data sorted, nor a conditional select. Each element also goes through a hash, carried from one
 * to the next, which dominates the loop's cost when the branches predict well.
 */
class UnpredictableBranchTest {
    private static final int LENGTH = 1 << 16;

    @BeforeAll
    static void onlyOnAarch64() {
        assumeTrue(System.getProperty("os.arch").equals("aarch64"), "the register model is for aarch64");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void flagsASwitchOverRandomData() {
        InstructionProfile profile = profile("dispatchRandom", random(), UnpredictableBranchTest::dispatchRandom);
        List<UnpredictableBranches> loops = apply(profile);
        String report = describe(profile, loops);
        assertEquals(1, loops.size(), report);
        UnpredictableBranches loop = loops.get(0);
        assertTrue(loop.landingShare() >= UnpredictableBranchRule.DEFAULT_MINIMUM_LANDING_SHARE, report);
        // The switch's branches, all on the element loaded from the array.
        assertTrue(loop.branches().size() >= 2, report);
        assertTrue(loop.branches().stream().allMatch(b -> b.load().kind() == InstructionKind.LOAD), report);
        assertTrue(loop.samples() * 10 >= profile.sampled() * 9, report);
    }

    @Test
    void leavesTheSameSwitchOverSortedDataAlone() {
        int[] data = random();
        Integer[] boxed = Arrays.stream(data).boxed().toArray(Integer[]::new);
        Arrays.sort(boxed, Comparator.comparingInt((Integer value) -> value & 3));
        for (int i = 0; i < LENGTH; i++) {
            data[i] = boxed[i];
        }
        InstructionProfile profile = profile("dispatchSorted", data, UnpredictableBranchTest::dispatchSorted);
        List<UnpredictableBranches> loops = apply(profile);
        assertTrue(loops.isEmpty(), describe(profile, loops));
    }

    @Test
    void flagsAnIfElseChainOverRandomData() {
        InstructionProfile profile = profile("chainRandom", random(), UnpredictableBranchTest::chainRandom);
        List<UnpredictableBranches> loops = apply(profile);
        String report = describe(profile, loops);
        assertEquals(1, loops.size(), report);
        assertTrue(loops.get(0).branches().size() >= 3, report);
        assertTrue(loops.get(0).branches().stream().allMatch(b -> b.load().kind() == InstructionKind.LOAD), report);
    }

    @Test
    void leavesTheSameChainOverSortedDataAlone() {
        int[] data = random();
        Arrays.sort(data);
        InstructionProfile profile = profile("chainSorted", data, UnpredictableBranchTest::chainSorted);
        List<UnpredictableBranches> loops = apply(profile);
        assertTrue(loops.isEmpty(), describe(profile, loops));
    }

    @Test
    void leavesAConditionalSelectAlone() {
        InstructionProfile profile = profile("select", random(), UnpredictableBranchTest::select);
        List<UnpredictableBranches> loops = apply(profile);
        assertTrue(loops.isEmpty(), describe(profile, loops));
    }

    static long chainRandom(int[] data) {
        long a = 0, b = 1, c = 2, d = 3, h = 0;
        for (int i = 0; i < data.length; i++) {
            int x = data[i];
            h = (h ^ x) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 29;
            if (x < 1 << 18) {
                a += x * 3L;
            } else if (x < 2 << 18) {
                b ^= (long) x << 7;
            } else if (x < 3 << 18) {
                c -= x;
            } else {
                d = Long.rotateLeft(d, x);
            }
        }
        return a + b + c + d + h;
    }

    static long chainSorted(int[] data) {
        long a = 0, b = 1, c = 2, d = 3, h = 0;
        for (int i = 0; i < data.length; i++) {
            int x = data[i];
            h = (h ^ x) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 29;
            if (x < 1 << 18) {
                a += x * 3L;
            } else if (x < 2 << 18) {
                b ^= (long) x << 7;
            } else if (x < 3 << 18) {
                c -= x;
            } else {
                d = Long.rotateLeft(d, x);
            }
        }
        return a + b + c + d + h;
    }

    // Math.min is compiled to a conditional select on every JDK (a ternary only is on
    // JDK 25; on 17 and 21 it keeps the branch, which mispredicts): no branch to mispredict.
    static long select(int[] data) {
        long a = 0, h = 0;
        for (int i = 0; i < data.length; i++) {
            int x = data[i];
            h = (h ^ x) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 29;
            a += Math.min(x, 1 << 19);
        }
        return a + h;
    }

    static long dispatchRandom(int[] data) {
        long a = 0, b = 1, c = 2, d = 3, h = 0;
        for (int i = 0; i < data.length; i++) {
            int x = data[i];
            h = (h ^ x) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 29;
            switch (x & 3) {
                case 0 -> a += x * 3L;
                case 1 -> b ^= (long) x << 7;
                case 2 -> c -= x;
                default -> d = Long.rotateLeft(d, x);
            }
        }
        return a + b + c + d + h;
    }

    static long dispatchSorted(int[] data) {
        long a = 0, b = 1, c = 2, d = 3, h = 0;
        for (int i = 0; i < data.length; i++) {
            int x = data[i];
            h = (h ^ x) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 29;
            switch (x & 3) {
                case 0 -> a += x * 3L;
                case 1 -> b ^= (long) x << 7;
                case 2 -> c -= x;
                default -> d = Long.rotateLeft(d, x);
            }
        }
        return a + b + c + d + h;
    }

    private static int[] random() {
        int[] data = new int[LENGTH];
        Random random = new Random(1);
        for (int i = 0; i < LENGTH; i++) {
            data[i] = random.nextInt(1 << 20);
        }
        return data;
    }

    private static List<UnpredictableBranches> apply(InstructionProfile profile) {
        List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
        return new UnpredictableBranchRule().apply(code, new Aarch64RegisterModel(),
                instruction -> profile.count((int) (instruction.address() - profile.address())));
    }

    // Compiles `kernel` on `data`, profiles it for a second and returns the profile of
    // the method named `name`.
    private static InstructionProfile profile(String name, int[] data, ToLongFunction<int[]> kernel) {
        long sink = 0;
        for (int i = 0; i < 3000; i++) {
            sink += kernel.applyAsLong(data); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                sink += kernel.applyAsLong(data);
            }
        } finally {
            Sampler.stop();
        }
        String method = "UnpredictableBranchTest." + name + "(";
        InstructionProfile[] hottest = new InstructionProfile[1];
        Sampler.forEachHottestMethod(Integer.MAX_VALUE, profile -> {
            if (hottest[0] == null && profile.name().contains(method)) {
                hottest[0] = profile.snapshot();
            }
        });
        assertNotNull(hottest[0], "no samples of " + name + " (" + sink + ")");
        return hottest[0];
    }

    private static String describe(InstructionProfile profile, List<UnpredictableBranches> loops) {
        StringBuilder report = new StringBuilder(profile.name() + " L" + profile.compLevel() + ", " + profile.sampled() + " samples\n");
        for (UnpredictableBranches loop : loops) {
            report.append(String.format("  loop at +0x%x: %d of %d samples landing; branches %s%n", loop.loopHeader() - profile.address(),
                    loop.landingSamples(), loop.samples(), loop.branches().stream().map(b -> b.branch().text()).toList()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new InstructionProfileFormatter().allInstructions().format(profile, out);
        }
        return report + bytes.toString(StandardCharsets.UTF_8);
    }
}
