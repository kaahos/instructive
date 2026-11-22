package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.analyse.MegamorphicCall;
import instructive.analyse.MegamorphicCallRule;
import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Finds the megamorphic call in a loop over four receiver types, whose dispatch stub's
 * samples the agent attributes to the call, and none in a loop over one.
 */
class MegamorphicCallTest {

    interface Shape {
        long area(long x);
    }

    static final class Square implements Shape {
        public long area(long x) {
            return x * 3;
        }
    }

    static final class Circle implements Shape {
        public long area(long x) {
            return x ^ 0x5555;
        }
    }

    static final class Triangle implements Shape {
        public long area(long x) {
            return x + 17;
        }
    }

    static final class Hexagon implements Shape {
        public long area(long x) {
            return Long.rotateLeft(x, 5);
        }
    }

    @BeforeAll
    static void onlyOnAarch64() {
        assumeTrue(System.getProperty("os.arch").equals("aarch64"), "the rule matches aarch64 code");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void findsTheCallOverFourReceiverTypes() {
        Shape[] kinds = {new Square(), new Circle(), new Triangle(), new Hexagon()};
        InstructionProfile profile = profile("sumMixed", kinds, MegamorphicCallTest::sumMixed);
        List<MegamorphicCall> calls = apply(profile);
        String report = describe(profile, calls);
        assertEquals(1, calls.size(), report);
        MegamorphicCall call = calls.get(0);
        assertTrue(call.inLoop(), report);
        // The dispatch is a good share of the loop's time: most of it on some runs, but
        // with ~13 samples a quarter has been seen (the rest on the reload after the call).
        assertTrue(call.samples() * 4 >= profile.sampled(), report);
    }

    @Test
    void leavesTheCallOverOneReceiverTypeAlone() {
        // Square.area is kept out of line (dontinline in the build): a class check, then
        // a direct call.
        InstructionProfile profile = profile("sumSquares", new Shape[] {new Square()}, MegamorphicCallTest::sumSquares);
        List<MegamorphicCall> calls = apply(profile);
        assertTrue(calls.isEmpty(), describe(profile, calls));
    }

    static long sumMixed(Shape[] shapes) {
        long sum = 0;
        for (int i = 0; i < shapes.length; i++) {
            sum += shapes[i].area(i);
        }
        return sum;
    }

    static long sumSquares(Shape[] shapes) {
        long sum = 0;
        for (int i = 0; i < shapes.length; i++) {
            sum += shapes[i].area(i);
        }
        return sum;
    }

    private static List<MegamorphicCall> apply(InstructionProfile profile) {
        List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
        return new MegamorphicCallRule().apply(code, new Aarch64RegisterModel(),
                instruction -> profile.count((int) (instruction.address() - profile.address())));
    }

    // Profiles `sum` over 16,384 shapes drawn at random from `kinds` for a second, and
    // returns the profile of the method named `name`. Each has its own method, so each
    // call site sees only its own receiver types.
    private static InstructionProfile profile(String name, Shape[] kinds, ToLongFunction<Shape[]> sum) {
        Shape[] shapes = new Shape[1 << 14];
        Random random = new Random(1);
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = kinds[random.nextInt(kinds.length)];
        }
        long sink = 0;
        for (int i = 0; i < 5000; i++) {
            sink += sum.applyAsLong(shapes); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                sink += sum.applyAsLong(shapes);
            }
        } finally {
            Sampler.stop();
        }
        String method = "MegamorphicCallTest." + name + "(";
        InstructionProfile[] hottest = new InstructionProfile[1];
        Sampler.forEachHottestMethod(Integer.MAX_VALUE, profile -> {
            if (hottest[0] == null && profile.name().contains(method)) {
                hottest[0] = profile.snapshot();
            }
        });
        assertNotNull(hottest[0], "no samples of " + name + " (" + sink + ")");
        return hottest[0];
    }

    private static String describe(InstructionProfile profile, List<MegamorphicCall> calls) {
        StringBuilder report = new StringBuilder(profile.name() + " L" + profile.compLevel() + ", " + profile.sampled() + " samples\n");
        for (MegamorphicCall call : calls) {
            report.append(String.format("  %s at +0x%x: %d samples of %d in its loop%n", call.call().text(),
                    call.call().address() - profile.address(), call.samples(), call.loopSamples()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new InstructionProfileFormatter().allInstructions().format(profile, out);
        }
        return report + bytes.toString(StandardCharsets.UTF_8);
    }
}
