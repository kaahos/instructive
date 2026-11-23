package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.analyse.MissedVectorisation;
import instructive.analyse.MissedVectorisationRule;
import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Finds DAXPY loops C2 unrolled but couldn't vectorise, because arrays indexed at
 * offsets it can't compare might be the same array, and leaves alone the one it did.
 */
class MissedVectorisationTest {

    private static final int LENGTH = 1 << 12;

    @BeforeAll
    static void onlyOnAarch64() {
        assumeTrue(System.getProperty("os.arch").equals("aarch64"), "the register model is for aarch64");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void leavesAVectorisedLoopAlone() {
        InstructionProfile profile = profile("daxpy", (a, x, y) -> daxpy(a, x, y));
        List<MissedVectorisation> findings = apply(profile);
        String report = describe(profile, findings);
        assertTrue(findings.isEmpty(), report);
        assertTrue(simd(instructions(profile)) > 0, report);
    }

    @Test
    void findsALoopWithOffsetsThatWasNotVectorised() {
        InstructionProfile profile = profile("daxpyWithOffsets", (a, x, y) -> daxpyWithOffsets(a, x, 1, y, 0, LENGTH - 1));
        List<MissedVectorisation> findings = apply(profile);
        String report = describe(profile, findings);
        assertEquals(1, findings.size(), report);
        MissedVectorisation found = findings.get(0);
        assertTrue(found.lanes() >= 2, report);
        assertEquals(8, found.elementSize(), report);
        assertEquals(List.of("fmul", "fadd"), found.operations().stream().map(Instruction::mnemonic).toList(), report);
        assertEquals(0, simd(found.instructions()), report);
        assertTrue(found.samples() * 4 >= profile.sampled() * 3, report);
    }

    @Test
    void leavesAVectorisedIntersectionAlone() {
        InstructionProfile profile = profileIntersect();
        List<MissedVectorisation> findings = apply(profile);
        String report = describe(profile, findings);
        assertTrue(findings.isEmpty(), report);
        assertTrue(simd(instructions(profile)) > 0, report);
    }

    @Test
    void leavesARecurrenceAlone() {
        InstructionProfile profile = profile("daxpyRecurrence", (a, x, y) -> daxpyRecurrence(a, x, y));
        List<MissedVectorisation> findings = apply(profile);
        assertTrue(findings.isEmpty(), describe(profile, findings));
    }

    static void daxpy(double a, double[] x, double[] y) {
        for (int i = 0; i < y.length; i++) {
            y[i] += a * x[i];
        }
    }

    static void daxpyWithOffsets(double a, double[] x, int xOffset, double[] y, int yOffset, int n) {
        for (int i = 0; i < n; i++) {
            y[yOffset + i] += a * x[xOffset + i];
        }
    }

    // Each element depends on the one before, so C2 unrolls it but can't vectorise it.
    static void daxpyRecurrence(double a, double[] x, double[] y) {
        for (int i = 1; i < y.length; i++) {
            y[i] = a * y[i - 1] + x[i];
        }
    }

    // The intersection of two bitmaps: every word of the result is one `and`.
    static void intersect(long[] a, long[] b, long[] c) {
        for (int i = 0; i < c.length; i++) {
            c[i] = a[i] & b[i];
        }
    }

    // Profiles intersecting two random bitmaps of 4,096 words.
    static InstructionProfile profileIntersect() {
        SplittableRandom random = new SplittableRandom(42);
        long[] a = new long[LENGTH];
        long[] b = new long[LENGTH];
        long[] c = new long[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            a[i] = random.nextLong();
            b[i] = random.nextLong();
        }
        return profile("intersect", () -> intersect(a, b, c), () -> c[1]);
    }

    private interface Kernel {
        void run(double a, double[] x, double[] y);
    }

    // Profiles `kernel` for a second and returns the profile of the method named `name`.
    private static InstructionProfile profile(String name, Kernel kernel) {
        double[] x = new double[LENGTH];
        double[] y = new double[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            x[i] = i;
            y[i] = i * 0.5;
        }
        return profile(name, () -> kernel.run(1e-9, x, y), () -> y[1]);
    }

    // Profiles `kernel` for a second and returns the profile of the method named `name`;
    // `result` reads something the kernel wrote, so its work isn't dead.
    private static InstructionProfile profile(String name, Runnable kernel, Supplier<Object> result) {
        for (int i = 0; i < 20_000; i++) {
            kernel.run(); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                kernel.run();
            }
        } finally {
            Sampler.stop();
        }
        String method = "MissedVectorisationTest." + name + "(";
        InstructionProfile[] hottest = new InstructionProfile[1];
        Sampler.forEachHottestMethod(Integer.MAX_VALUE, profile -> {
            if (hottest[0] == null && profile.name().contains(method)) {
                hottest[0] = profile.snapshot();
            }
        });
        assertNotNull(hottest[0], "no samples of " + name + " (" + result.get() + ")");
        return hottest[0];
    }

    static long simd(List<Instruction> instructions) {
        return instructions.stream().filter(instruction -> instruction.kind() == InstructionKind.SIMD).count();
    }

    static List<Instruction> instructions(InstructionProfile profile) {
        return new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
    }

    static List<MissedVectorisation> apply(InstructionProfile profile) {
        return new MissedVectorisationRule().apply(instructions(profile), new Aarch64RegisterModel(),
                instruction -> profile.count((int) (instruction.address() - profile.address())));
    }

    static String describe(InstructionProfile profile, List<MissedVectorisation> findings) {
        StringBuilder report = new StringBuilder(profile.name() + " L" + profile.compLevel() + ", " + profile.sampled() + " samples\n");
        for (MissedVectorisation found : findings) {
            report.append(String.format("  loop at +0x%x: %d lanes of %d bytes, %d samples, operations %s%n",
                    found.header() - profile.address(), found.lanes(), found.elementSize(), found.samples(),
                    found.operations()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new InstructionProfileFormatter().allInstructions().format(profile, out);
        }
        return report + bytes.toString(StandardCharsets.UTF_8);
    }
}
