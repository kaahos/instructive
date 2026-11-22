package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class NotUnrolledLoopRuleTest {
    private static final long BASE = Listing.BASE;

    // A loop with a 64-bit counter, used as a scaled index: one element per iteration.
    private static final String[] WIDE_INDEX = {
        "03 78 62 b8", // ldr w3, [x0, x2, lsl #2]
        "84 00 03 0b", // add w4, w4, w3
        "42 04 00 91", // add x2, x2, #0x1
        "5f 00 01 eb", // cmp x2, x1
        "8b ff ff 54", // b.lt (to the loop start)
        "c0 03 5f d6", // ret
    };

    // A pointer stepped by the size of the 8-byte element it loads.
    private static final String[] POINTER = {
        "03 00 40 f9", // ldr x3, [x0]
        "84 00 03 8b", // add x4, x4, x3
        "00 20 00 91", // add x0, x0, #0x8
        "1f 00 01 eb", // cmp x0, x1
        "83 ff ff 54", // b.lo (to the loop start)
        "c0 03 5f d6", // ret
    };

    // A pointer stepped past the pair of 8-byte elements it loads: unrolled by 2.
    private static final String[] POINTER_PAIR = {
        "03 14 40 a9", // ldp x3, x5, [x0]
        "84 00 03 8b", // add x4, x4, x3
        "84 00 05 8b", // add x4, x4, x5
        "00 40 00 91", // add x0, x0, #0x10
        "1f 00 01 eb", // cmp x0, x1
        "63 ff ff 54", // b.lo (to the loop start)
        "c0 03 5f d6", // ret
    };

    private final Aarch64RegisterModel registers = new Aarch64RegisterModel();

    @Test
    void findsTheLoopWhoseConditionCallsAMethod() {
        Listing method = Listing.load("sum-with-call-in-condition.tsv");
        List<NotUnrolledLoop> loops = new NotUnrolledLoopRule().apply(method.code(), registers, method::count);
        assertEquals(1, loops.size(), () -> describe(loops));
        NotUnrolledLoop loop = loops.get(0);
        // Entered by a branch to +0x068, the loop's header; the padding before it isn't
        // part of the loop.
        assertEquals(BASE + 0x68, loop.header());
        assertEquals(18, loop.length(), () -> describe(loops));
        assertTrue(loop.instructions().stream().noneMatch(i -> i.mnemonic().equals("nop") && i.address() < loop.header()));
        // i, in w13: i + 1 is computed into w29, which survives the call, and moved back.
        assertEquals("x13", loop.inductionVariable());
        assertEquals(1, loop.step());
        assertEquals(List.of("add w29, w13, #0x1", "mov w13, w29"),
                loop.stepInstructions().stream().map(Instruction::text).toList());
        assertFalse(loop.wide());
        // The call to limit(), each iteration.
        assertEquals(1, loop.calls().size());
        assertEquals(BASE + 0x8c, loop.calls().get(0).address());
        // All the method's samples.
        assertEquals(method.total(), loop.samples());
    }

    @Test
    void leavesUnrolledLoopsAndTheirColdTailsAlone() {
        // Unrolled 16 times (and vectorised); its post-loop steps by one, but is cold.
        Listing method = Listing.load("sum.tsv");
        assertTrue(new NotUnrolledLoopRule().apply(method.code(), registers, method::count).isEmpty());
        // Without samples, the post-loop is reported.
        List<NotUnrolledLoop> unsampled = new NotUnrolledLoopRule().apply(method.code(), registers);
        assertEquals(1, unsampled.size(), () -> describe(unsampled));
        assertEquals(BASE + 0x124, unsampled.get(0).header());
        assertEquals(List.of("add w16, w16, #0x1"),
                unsampled.get(0).stepInstructions().stream().map(Instruction::text).toList());
    }

    @Test
    void findsAnIndexScaledByAnInstructionOfItsOwn() {
        // c[i] = a[i] + b[i] with limit(a) in the condition: i, in w29, is stepped by one
        // and scaled by sbfiz x10, x29, #2, #32 before it is added to each array.
        Listing method = Listing.load("add-with-call-in-condition.tsv");
        List<NotUnrolledLoop> loops = new NotUnrolledLoopRule().apply(method.code(), registers, method::count);
        assertEquals(1, loops.size(), () -> describe(loops));
        NotUnrolledLoop loop = loops.get(0);
        assertEquals(BASE + 0x80, loop.header());
        assertEquals("x29", loop.inductionVariable());
        assertEquals(1, loop.step());
        assertEquals(List.of("add w29, w29, #0x1"), loop.stepInstructions().stream().map(Instruction::text).toList());
        assertEquals(1, loop.calls().size());
        assertTrue(loop.instructions().stream().noneMatch(i -> i.kind() == InstructionKind.SIMD));
    }

    @Test
    void leavesAVectorisedLoopAlone() {
        // The same loop with a.length in the condition: 8 copies of 4-lane vector adds,
        // 32 elements per iteration, scaled by sbfiz just the same.
        Listing method = Listing.load("add.tsv");
        assertTrue(new NotUnrolledLoopRule().apply(method.code(), registers, method::count).isEmpty());
        assertTrue(method.code().stream().filter(i -> i.kind() == InstructionKind.SIMD).count() >= 8);
    }

    @Test
    void leavesALoopVectorisedWithSveAlone() {
        // The same loop on a CPU with SVE (from a CI runner): 4 copies of an SVE add per
        // iteration, 16 elements, after a ptrue in the prologue.
        Listing method = Listing.load("add-sve.tsv");
        assertTrue(method.code().stream().filter(i -> i.kind() == InstructionKind.SIMD).count() >= 16);
        assertFalse(ControlFlowGraph.of(method.code()).loops().isEmpty());
        assertTrue(new NotUnrolledLoopRule().apply(method.code(), registers, method::count).isEmpty());
        // Without the samples, the pre-loop and post-loop, one element per iteration, are found.
        assertEquals(2, new NotUnrolledLoopRule().apply(method.code(), registers).size());
    }

    @Test
    void leavesUnrolledRecurrencesAlone() {
        assertTrue(new NotUnrolledLoopRule().apply(Listing.decode(LongDependencyChainRuleTest.HASH_LOOP), registers).isEmpty());
        assertTrue(new NotUnrolledLoopRule().apply(Listing.decode(LongDependencyChainRuleTest.DELTA_LOOP), registers).isEmpty());
    }

    @Test
    void flagsAWideCounter() {
        List<NotUnrolledLoop> loops = new NotUnrolledLoopRule().apply(Listing.decode(WIDE_INDEX), registers);
        assertEquals(1, loops.size());
        assertEquals("x2", loops.get(0).inductionVariable());
        assertEquals(1, loops.get(0).step());
        assertTrue(loops.get(0).wide());
        assertTrue(loops.get(0).calls().isEmpty());
    }

    @Test
    void countsAPointersStepInElements() {
        List<NotUnrolledLoop> loops = new NotUnrolledLoopRule().apply(Listing.decode(POINTER), registers);
        assertEquals(1, loops.size());
        assertEquals("x0", loops.get(0).inductionVariable());
        assertEquals(8, loops.get(0).step());
        assertTrue(new NotUnrolledLoopRule().apply(Listing.decode(POINTER_PAIR), registers).isEmpty());
    }

    @Test
    void elementSizes() {
        assertEquals(4, NotUnrolledLoopRule.elementSize(Listing.decode("6e 2d 40 b9").get(0))); // ldr w14, [x11, #0x2c]
        assertEquals(8, NotUnrolledLoopRule.elementSize(Listing.decode("03 14 40 a9").get(0))); // ldp x3, x5, [x0]
        assertEquals(8, NotUnrolledLoopRule.elementSize(Listing.decode("e2 07 00 f9").get(0))); // str x2, [sp, #0x8]
        assertEquals(1, NotUnrolledLoopRule.elementSize(Listing.decode("20 00 40 39").get(0))); // ldrb w0, [x1]
        assertEquals(2, NotUnrolledLoopRule.elementSize(Listing.decode("20 00 40 79").get(0))); // ldrh w0, [x1]
        assertEquals(4, NotUnrolledLoopRule.elementSize(Listing.decode("20 00 80 b9").get(0))); // ldrsw x0, [x1]
        assertEquals(8, NotUnrolledLoopRule.elementSize(Listing.decode("20 7c 02 c8").get(0))); // stxr w2, x0, [x1]
    }

    private static String describe(List<NotUnrolledLoop> loops) {
        return loops.stream()
                .map(loop -> String.format("+0x%x, %d instructions, %s by %d: %s", loop.header() - BASE, loop.length(),
                        loop.inductionVariable(), loop.step(),
                        loop.instructions().stream().map(Instruction::text).collect(Collectors.joining("; "))))
                .collect(Collectors.joining("\n"));
    }
}
