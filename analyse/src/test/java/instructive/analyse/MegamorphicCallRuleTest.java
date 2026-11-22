package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MegamorphicCallRuleTest {
    private static final long BASE = Listing.BASE;

    private final Aarch64RegisterModel registers = new Aarch64RegisterModel();

    @ParameterizedTest
    @CsvSource({
        // listing, the call, the loop header (C2 rotates the loop and enters it in the
        // middle, after spilling the sum), samples on the call, in the loop
        "megamorphic-call-jdk17.tsv, 0x074, 0x054, 11, 13",
        "megamorphic-call-jdk21.tsv, 0x084, 0x064, 7, 10",
        "megamorphic-call-jdk25.tsv, 0x084, 0x064, 15, 18",
    })
    void findsTheCallThroughADispatchStub(String resource, int call, int loopHeader, long samples, long loopSamples) {
        // An interface call over four receiver types: the itable stub's samples are on
        // the call, which has most of the method's.
        Listing method = Listing.load(resource);
        List<MegamorphicCall> calls = new MegamorphicCallRule().apply(method.code(), registers, method::count);
        assertEquals(1, calls.size(), () -> describe(calls));
        MegamorphicCall found = calls.get(0);
        assertEquals(method.at(call), found.call());
        assertEquals("bl", found.call().mnemonic());
        assertEquals(List.of("mov", "movk", "movk"), found.setup().stream().map(Instruction::mnemonic).toList());
        assertTrue(found.setup().stream().allMatch(i -> i.text().contains(" x9, ")), () -> describe(calls));
        assertEquals(BASE + loopHeader, found.loopHeader());
        assertTrue(found.inLoop());
        assertEquals(samples, found.samples());
        assertEquals(loopSamples, found.loopSamples());
        assertEquals(loopSamples, method.total());
    }

    @Test
    void leavesAMonomorphicCallAlone() {
        // One receiver type, kept out of line: a class check, then a direct call.
        Listing method = Listing.load("monomorphic-call.tsv");
        assertEquals(List.of(), new MegamorphicCallRule().apply(method.code(), registers, method::count));
        assertEquals(List.of(), new MegamorphicCallRule().apply(method.code(), registers));
    }

    @Test
    void leavesAStaticCallAlone() {
        Listing method = Listing.load("static-call.tsv");
        assertEquals(List.of(), new MegamorphicCallRule().apply(method.code(), registers, method::count));
        assertEquals(List.of(), new MegamorphicCallRule().apply(method.code(), registers));
    }

    @Test
    void leavesAnInlineCacheCallWithoutSamplesAlone() {
        // The same call, as if it went straight to one callee: no samples of its own, so
        // nothing says it dispatches through a stub.
        Listing method = Listing.load("megamorphic-call-jdk25.tsv");
        Instruction call = method.at(0x084);
        List<MegamorphicCall> calls = new MegamorphicCallRule().apply(method.code(), registers,
                instruction -> instruction.equals(call) ? 0 : method.count(instruction) + 1);
        assertEquals(List.of(), calls);
    }

    @Test
    void reportsEveryInlineCacheCallWithoutSamples() {
        Listing method = Listing.load("megamorphic-call-jdk25.tsv");
        List<MegamorphicCall> calls = new MegamorphicCallRule().apply(method.code(), registers);
        assertEquals(List.of(method.at(0x084)), calls.stream().map(MegamorphicCall::call).toList());
        assertEquals(0, calls.get(0).samples());
    }

    @Test
    void reportsACallOutsideALoop() {
        // mov x9, #0x2590; movk x9, #0x3409, lsl #16; movk x9, #0xffff, lsl #32; bl; ret
        List<Instruction> code = Listing.decode("09 b2 84 d2", "29 81 a6 f2", "e9 ff df f2", "5b cd ed 97", "c0 03 5f d6");
        List<MegamorphicCall> calls = new MegamorphicCallRule().apply(code, registers, i -> i.address() == BASE + 0xc ? 5 : 1);
        assertEquals(1, calls.size());
        assertEquals(-1, calls.get(0).loopHeader());
        assertEquals(5, calls.get(0).samples());
        assertEquals(9, calls.get(0).loopSamples());
    }

    @Test
    void needsTheWholeSetupToWriteTheInlineCacheRegister() {
        // mov x10, #0x2590; bl: not an inline cache call.
        List<Instruction> code = Listing.decode("0a b2 84 d2", "5b cd ed 97", "c0 03 5f d6");
        assertEquals(List.of(), new MegamorphicCallRule().apply(code, registers));
    }

    private static String describe(List<MegamorphicCall> calls) {
        return calls.stream()
                .map(c -> String.format("%s at 0x%x (loop 0x%x): %d of %d, setup %s", c.call().text(), c.call().address(),
                        c.loopHeader(), c.samples(), c.loopSamples(), c.setup().stream().map(Instruction::text).toList()))
                .collect(Collectors.joining("\n"));
    }
}
