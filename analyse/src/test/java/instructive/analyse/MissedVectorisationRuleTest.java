package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissedVectorisationRuleTest {
    private static final long BASE = Listing.BASE;

    // Two copies of x[i] * a, storing adjacent doubles: unrolled twice, not vectorised.
    private static final String[] TWO_COPIES = {
        "30 08 40 fd", // ldr d16, [x1, #0x10]
        "10 0a 60 1e", // fmul d16, d16, d0
        "50 08 00 fd", // str d16, [x2, #0x10]
        "31 0c 40 fd", // ldr d17, [x1, #0x18]
        "31 0a 60 1e", // fmul d17, d17, d0
        "51 0c 00 fd", // str d17, [x2, #0x18]
        "63 08 00 11", // add w3, w3, #0x2
        "7f 00 04 6b", // cmp w3, w4
        "0b ff ff 54", // b.lt (to the loop start)
        "c0 03 5f d6", // ret
    };

    // The same, but the second copy stores two elements further on.
    private static final String[] NOT_ADJACENT = {
        "30 08 40 fd", // ldr d16, [x1, #0x10]
        "10 0a 60 1e", // fmul d16, d16, d0
        "50 08 00 fd", // str d16, [x2, #0x10]
        "31 0c 40 fd", // ldr d17, [x1, #0x18]
        "31 0a 60 1e", // fmul d17, d17, d0
        "51 10 00 fd", // str d17, [x2, #0x20]
        "63 08 00 11", // add w3, w3, #0x2
        "7f 00 04 6b", // cmp w3, w4
        "0b ff ff 54", // b.lt (to the loop start)
        "c0 03 5f d6", // ret
    };

    // Two copies of x[i] / a on ints: there is no vector integer division.
    private static final String[] DIVIDE = {
        "25 10 40 b9", // ldr w5, [x1, #0x10]
        "a5 0c c0 1a", // sdiv w5, w5, w0
        "45 10 00 b9", // str w5, [x2, #0x10]
        "26 14 40 b9", // ldr w6, [x1, #0x14]
        "c6 0c c0 1a", // sdiv w6, w6, w0
        "46 14 00 b9", // str w6, [x2, #0x14]
        "63 08 00 11", // add w3, w3, #0x2
        "7f 00 04 6b", // cmp w3, w4
        "0b ff ff 54", // b.lt (to the loop start)
        "c0 03 5f d6", // ret
    };

    // Two copies of x[i] * a on ints: a 32-bit multiply has a vector equivalent.
    private static final String[] MULTIPLY = {
        "25 10 40 b9", // ldr w5, [x1, #0x10]
        "a5 7c 00 1b", // mul w5, w5, w0
        "45 10 00 b9", // str w5, [x2, #0x10]
        "26 14 40 b9", // ldr w6, [x1, #0x14]
        "c6 7c 00 1b", // mul w6, w6, w0
        "46 14 00 b9", // str w6, [x2, #0x14]
        "63 08 00 11", // add w3, w3, #0x2
        "7f 00 04 6b", // cmp w3, w4
        "0b ff ff 54", // b.lt (to the loop start)
        "c0 03 5f d6", // ret
    };

    private final Aarch64RegisterModel registers = new Aarch64RegisterModel();

    @Test
    void findsDaxpyWithOffsetsUnrolledButNotVectorised() {
        // y[yOffset + i] += a * x[xOffset + i]: x and y might be the same array, so C2
        // unrolls four times but keeps each copy scalar.
        Listing method = Listing.load("daxpy-offsets.tsv");
        List<MissedVectorisation> findings = new MissedVectorisationRule().apply(method.code(), registers, method::count);
        assertEquals(1, findings.size(), () -> describe(findings));
        MissedVectorisation found = findings.get(0);
        assertEquals(BASE + 0xe0, found.header());
        assertEquals(4, found.lanes());
        assertEquals(8, found.elementSize());
        assertEquals(List.of("fmul d16, d16, d0", "fadd d16, d16, d17"),
                found.operations().stream().map(Instruction::text).toList());
        // Each copy loads x and y, multiplies, adds and stores the next element of y. The
        // last one addresses its elements from registers of its own.
        assertEquals(List.of(0x104, 0x118, 0x12c, 0x154), stores(found));
        for (List<Instruction> copy : found.copies()) {
            assertEquals(List.of("ldr", "ldr", "fmul", "fadd", "str"), copy.stream().map(Instruction::mnemonic).toList());
        }
        assertEquals(98, found.samples());
        assertEquals(99, method.total());
    }

    @Test
    void findsAnIntKernelUnrolledButNotVectorised() {
        // y[yOffset + i] += 3 * x[xOffset + i] on ints: the multiply by 3 is an add of a shift.
        Listing method = Listing.load("add-int-offsets.tsv");
        List<MissedVectorisation> findings = new MissedVectorisationRule().apply(method.code(), registers, method::count);
        assertEquals(1, findings.size(), () -> describe(findings));
        MissedVectorisation found = findings.get(0);
        assertEquals(BASE + 0xe0, found.header());
        assertEquals(4, found.lanes());
        assertEquals(4, found.elementSize());
        assertEquals(List.of("add", "add"), found.operations().stream().map(Instruction::mnemonic).toList());
    }

    @Test
    void findsAConstantOffsetJdk17CouldNotVectorise() {
        // y[i] += a * x[i + 1]: before JDK 25, superword wouldn't pack loads misaligned
        // with the stores.
        Listing method = Listing.load("daxpy-constant-offset-jdk17.tsv");
        List<MissedVectorisation> findings = new MissedVectorisationRule().apply(method.code(), registers, method::count);
        assertEquals(1, findings.size(), () -> describe(findings));
        MissedVectorisation found = findings.get(0);
        assertEquals(BASE + 0xb0, found.header());
        assertEquals(4, found.lanes());
        assertEquals(List.of(0xcc, 0xe0, 0xf4, 0x10c), stores(found));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // vectorised: the loop has SIMD instructions
        "daxpy.tsv",
        // a floating-point sum: nothing is stored in the loop
        "daxpy-sum.tsv",
        // y[i] = a * y[i - 1] + x[i]: unrolled eight times, each copy using the one before
        "daxpy-recurrence.tsv",
        // y[i] *= x[i] on longs: Advanced SIMD has no 64-bit multiply
        "mul-long-offsets.tsv",
    })
    void leavesLoopsThatCannotBeVectorisedAlone(String resource) {
        Listing method = Listing.load(resource);
        List<MissedVectorisation> sampled = new MissedVectorisationRule().apply(method.code(), registers, method::count);
        assertEquals(List.of(), sampled, () -> describe(sampled));
        List<MissedVectorisation> unsampled = new MissedVectorisationRule().apply(method.code(), registers);
        assertEquals(List.of(), unsampled, () -> describe(unsampled));
    }

    @Test
    void findsTwoAdjacentCopies() {
        List<MissedVectorisation> findings = new MissedVectorisationRule().apply(Listing.decode(TWO_COPIES), registers);
        assertEquals(1, findings.size(), () -> describe(findings));
        assertEquals(List.of(0x08, 0x14), stores(findings.get(0)));
        assertEquals(List.of("fmul d16, d16, d0"), findings.get(0).operations().stream().map(Instruction::text).toList());
    }

    @Test
    void findsCopiesOfAnIntMultiply() {
        assertEquals(1, new MissedVectorisationRule().apply(Listing.decode(MULTIPLY), registers).size());
    }

    @Test
    void leavesCopiesOfNonAdjacentElementsAlone() {
        assertEquals(List.of(), new MissedVectorisationRule().apply(Listing.decode(NOT_ADJACENT), registers));
    }

    @Test
    void leavesOperationsWithoutAVectorEquivalentAlone() {
        assertEquals(List.of(), new MissedVectorisationRule().apply(Listing.decode(DIVIDE), registers));
    }

    @Test
    void leavesLoopsWithoutCopiesAlone() {
        assertTrue(new MissedVectorisationRule().apply(Listing.decode(LongDependencyChainRuleTest.HASH_LOOP), registers).isEmpty());
        assertTrue(new MissedVectorisationRule().apply(Listing.decode(LongDependencyChainRuleTest.DELTA_LOOP), registers).isEmpty());
        assertTrue(new MissedVectorisationRule().apply(Listing.load("add-with-call-in-condition.tsv").code(), registers).isEmpty());
    }

    private static List<Integer> stores(MissedVectorisation found) {
        return found.copies().stream().map(copy -> (int) (copy.get(copy.size() - 1).address() - BASE)).toList();
    }

    private static String describe(List<MissedVectorisation> findings) {
        return findings.stream()
                .map(found -> String.format("loop at +0x%x, %d lanes of %d bytes, %d samples: %s", found.header() - BASE,
                        found.lanes(), found.elementSize(), found.samples(), found.copies()))
                .collect(Collectors.joining("\n"));
    }
}
