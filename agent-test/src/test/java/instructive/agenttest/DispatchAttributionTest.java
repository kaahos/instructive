package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.CodeKind;
import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.parse.Disassembler;
import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Samples taken in a vtable or itable stub, the code HotSpot makes a call through when a
 * call site has seen too many receiver types to inline them, are attributed to the call
 * that reached the stub: the cost of the dispatch shows up at the call site in the
 * caller, not in a stub nobody can trace back to their code.
 */
class DispatchAttributionTest {

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

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void attributesDispatchToTheCallSite() {
        Disassembler disassembler = InstructionProfileFormatter.forArchitecture(System.getProperty("os.arch")).orElse(null);
        assumeTrue(disassembler != null, "no decoder for " + System.getProperty("os.arch"));
        // Four receiver types at random: too many to inline, so every call goes through
        // an itable stub.
        Shape[] kinds = {new Square(), new Circle(), new Triangle(), new Hexagon()};
        Shape[] shapes = new Shape[1 << 14];
        Random random = new Random(1);
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = kinds[random.nextInt(kinds.length)];
        }
        long sink = 0;
        for (int i = 0; i < 5000; i++) {
            sink += sumAreas(shapes); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                sink += sumAreas(shapes);
            }
        } finally {
            Sampler.stop();
        }
        Map<CodeKind, Long> kindCounts = Sampler.kindCounts();
        InstructionProfile caller = hottest("DispatchAttributionTest.sumAreas(", sink);
        String report = describe(caller, kindCounts);

        // The caller's hottest instruction is the call, holding the stub's samples.
        int hottestOffset = -1;
        for (int offset = 0; offset < caller.profileLength(); offset += caller.bytesPerCount()) {
            if (hottestOffset < 0 || caller.count(offset) > caller.count(hottestOffset)) {
                hottestOffset = offset;
            }
        }
        Instruction call = disassembler.decode(caller.machineCode().orElseThrow(), hottestOffset, caller.address() + hottestOffset);
        assertEquals(InstructionKind.CALL, call.kind(), () -> call.text() + "\n" + report);
        long atCall = caller.count(hottestOffset);
        assertTrue(atCall * 4 >= caller.sampled(), report);
        // Hardly anything is left counted as a stub.
        assertTrue(kindCounts.get(CodeKind.STUB) * 4 <= atCall, report);
    }

    static long sumAreas(Shape[] shapes) {
        long sum = 0;
        for (int i = 0; i < shapes.length; i++) {
            sum += shapes[i].area(i);
        }
        return sum;
    }

    private static InstructionProfile hottest(String method, long sink) {
        InstructionProfile[] hottest = new InstructionProfile[1];
        Sampler.forEachHottestMethod(Integer.MAX_VALUE, profile -> {
            if (hottest[0] == null && profile.name().contains(method)) {
                hottest[0] = profile.snapshot();
            }
        });
        assertNotNull(hottest[0], "no samples of " + method + " (" + sink + ")");
        return hottest[0];
    }

    private static String describe(InstructionProfile profile, Map<CodeKind, Long> kindCounts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new InstructionProfileFormatter().allInstructions().format(profile, out);
        }
        return "kinds " + kindCounts + "\n" + bytes.toString(StandardCharsets.UTF_8);
    }
}
