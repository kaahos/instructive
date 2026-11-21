package instructive.parse.aarch64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.RegisterUsage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class Aarch64RegisterModelTest {
    private static final Pattern CANONICAL = Pattern.compile("x([0-9]|[12][0-9]|30)|sp|v([0-9]|[12][0-9]|3[01])");

    private final Aarch64Disassembler disassembler = new Aarch64Disassembler();
    private final Aarch64RegisterModel registers = new Aarch64RegisterModel();

    @Test
    void dataProcessingWritesTheFirstOperand() {
        assertUsage(0x8b0e014a, "add x10, x10, x14", Set.of("x10", "x14"), Set.of("x10"));
        // w and x views are the same register.
        assertUsage(0x4b04016b, "sub w11, w11, w4", Set.of("x11", "x4"), Set.of("x11"));
        assertUsage(0x531b688b, "lsl w11, w4, #5", Set.of("x4"), Set.of("x11"));
        assertUsage(0x9b0e358d, "madd x13, x12, x14, x13", Set.of("x12", "x14", "x13"), Set.of("x13"));
        // The zero register carries no data.
        assertUsage(0x9a8203e2, "csel x2, xzr, x2, eq", Set.of("x2"), Set.of("x2"));
        assertUsage(0x910003fd, "mov x29, sp", Set.of("sp"), Set.of("x29"));
        assertUsage(0x1e600864, "fmul d4, d3, d0", Set.of("v3", "v0"), Set.of("v4"));
        assertUsage(0x9e660020, "fmov x0, d1", Set.of("v1"), Set.of("x0"));
        assertUsage(0x9a9f07e0, "cset x0, ne", Set.of(), Set.of("x0"));
    }

    @Test
    void partialWritesAlsoReadTheDestination() {
        assertUsage(0xf2a28c48, "movk x8, #0x1462, lsl #16", Set.of("x8"), Set.of("x8"));
        assertUsage(0xb3780c20, "bfi x0, x1, #8, #4", Set.of("x0", "x1"), Set.of("x0"));
    }

    @Test
    void instructionsStartingWithBAreNotAllBranches() {
        assertUsage(0x0a24006c, "bic w12, w3, w4", Set.of("x3", "x4"), Set.of("x12"));
        assertUsage(0x33031c20, "bfxil w0, w1, #3, #5", Set.of("x0", "x1"), Set.of("x0"));
        assertUsage(0x14000002, "b 0x8", Set.of(), Set.of());
    }

    @Test
    void comparesAndBranchesOnlyRead() {
        assertUsage(0x6b0a011f, "cmp w8, w10", Set.of("x8", "x10"), Set.of());
        assertUsage(0xf240041f, "tst x0, #0x3", Set.of("x0"), Set.of());
        assertUsage(0xb4000780, "cbz x0, 0xf0", Set.of("x0"), Set.of());
        assertUsage(0x36000088, "tbz w8, #0x0, 0x10", Set.of("x8"), Set.of());
        assertUsage(0x54000040, "b.eq 0x8", Set.of(), Set.of());
        assertUsage(0xd61f0100, "br x8", Set.of("x8"), Set.of());
    }

    @Test
    void callsUseTheLinkRegister() {
        assertUsage(0x94000002, "bl 0x8", Set.of(), Set.of("x30"));
        assertUsage(0xd63f0100, "blr x8", Set.of("x8"), Set.of("x30"));
        assertUsage(0xd65f03c0, "ret", Set.of("x30"), Set.of());
        assertUsage(0xd65f0020, "ret x1", Set.of("x1"), Set.of());
    }

    @Test
    void loadsWriteDestinationsAndReadAddresses() {
        assertUsage(0xb9400828, "ldr w8, [x1, #0x8]", Set.of("x1"), Set.of("x8"));
        assertUsage(0xf8627820, "ldr x0, [x1, x2, lsl #3]", Set.of("x1", "x2"), Set.of("x0"));
        assertUsage(0xa9410fe2, "ldp x2, x3, [sp, #0x10]", Set.of("sp"), Set.of("x2", "x3"));
        assertUsage(0x18000128, "ldr w8, 0x24", Set.of(), Set.of("x8"));
        // The safepoint poll loads into the zero register.
        assertUsage(0xb940017f, "ldr wzr, [x11]", Set.of("x11"), Set.of());
        assertUsage(0x3dc00820, "ldr q0, [x1, #0x20]", Set.of("x1"), Set.of("v0"));
    }

    @Test
    void storesOnlyReadUnlessTheyWriteBack() {
        assertUsage(0xf90007e2, "str x2, [sp, #0x8]", Set.of("x2", "sp"), Set.of());
        assertUsage(0xa9010fe2, "stp x2, x3, [sp, #0x10]", Set.of("x2", "x3", "sp"), Set.of());
        // Pre- and post-indexed addressing updates the base.
        assertUsage(0xa9bf7bfd, "stp x29, x30, [sp, #-0x10]!", Set.of("x29", "x30", "sp"), Set.of("sp"));
        assertUsage(0xa8c133e8, "ldp x8, x12, [sp], #0x10", Set.of("sp"), Set.of("x8", "x12", "sp"));
        assertUsage(0xf8408c20, "ldr x0, [x1, #0x8]!", Set.of("x1"), Set.of("x0", "x1"));
    }

    @Test
    void storesDistinguishTheirDataFromTheirAddress() {
        assertStored(0xb9002d6e, "str w14, [x11, #0x2c]", Set.of("x14"));
        assertStored(0xa9010fe2, "stp x2, x3, [sp, #0x10]", Set.of("x2", "x3"));
        assertStored(0xa9bf7bfd, "stp x29, x30, [sp, #-0x10]!", Set.of("x29", "x30"));
        assertStored(0x889ffd28, "stlr w8, [x9]", Set.of("x8"));
        assertStored(0xc8027c20, "stxr w2, x0, [x1]", Set.of("x0"));
        assertStored(0xb820005f, "stadd w0, [x2]", Set.of("x0"));
        assertStored(0x88e8fd4d, "casal w8, w13, [x10]", Set.of("x13"));
        assertStored(0xf8e08041, "swpal x0, x1, [x2]", Set.of("x0"));
        assertStored(0xf8408c20, "ldr x0, [x1, #0x8]!", Set.of());
        assertStored(0x0b0c01cc, "add w12, w14, w12", Set.of());
    }

    @Test
    void atomicsReadAndWriteTheRightOperands() {
        assertUsage(0x88e8fd4d, "casal w8, w13, [x10]", Set.of("x8", "x13", "x10"), Set.of("x8"));
        assertUsage(0xf8e00062, "ldaddal x0, x2, [x3]", Set.of("x0", "x3"), Set.of("x2"));
        assertUsage(0xf8e08041, "swpal x0, x1, [x2]", Set.of("x0", "x2"), Set.of("x1"));
        assertUsage(0xb820005f, "stadd w0, [x2]", Set.of("x0", "x2"), Set.of());
        assertUsage(0xc8027c20, "stxr w2, x0, [x1]", Set.of("x0", "x1"), Set.of("x2"));
        assertUsage(0xc85f7c20, "ldxr x0, [x1]", Set.of("x1"), Set.of("x0"));
        assertUsage(0x889ffd28, "stlr w8, [x9]", Set.of("x8", "x9"), Set.of());
    }

    @Test
    void systemInstructions() {
        assertUsage(0xd53bd040, "mrs x0, TPIDR_EL0", Set.of(), Set.of("x0"));
        assertUsage(0xd51b4402, "msr FPCR, x2", Set.of("x2"), Set.of());
        assertUsage(0xd50b7420, "dc zva, x0", Set.of("x0"), Set.of());
        assertUsage(0xd503201f, "nop", Set.of(), Set.of());
        assertUsage(0xd5033bbf, "dmb ish", Set.of(), Set.of());
    }

    @Test
    void undecodedInstructionsAreUnknown() {
        Instruction simd = disassembler.decode(0x0e205a31, 0);
        RegisterUsage usage = registers.usage(simd);
        assertFalse(usage.known());
        assertEquals(RegisterUsage.UNKNOWN, usage);
    }

    @Test
    void namesEveryRegisterCanonicallyAcrossTheVectors() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String line : resource("llvm-vectors.tsv")) {
            String[] fields = line.split("\t");
            Instruction instruction =
                    disassembler.decode(Integer.parseUnsignedInt(fields[1], 16), Long.parseUnsignedLong(fields[0], 16));
            RegisterUsage usage;
            try {
                usage = registers.usage(instruction);
            } catch (RuntimeException e) {
                problems.add(instruction.text() + ": " + e);
                continue;
            }
            checked++;
            for (String register : union(usage.reads(), usage.writes())) {
                if (!CANONICAL.matcher(register).matches()) {
                    problems.add(instruction.text() + ": " + register);
                }
            }
        }
        assertTrue(checked > 5000);
        assertEquals(List.of(), problems.subList(0, Math.min(20, problems.size())), problems.size() + " problems");
    }

    private void assertUsage(int word, String text, Set<String> reads, Set<String> writes) {
        Instruction instruction = disassembler.decode(word, 0);
        assertEquals(text, instruction.text());
        RegisterUsage usage = registers.usage(instruction);
        assertTrue(usage.known(), text);
        assertEquals(reads, usage.reads(), text + " reads");
        assertEquals(writes, usage.writes(), text + " writes");
    }

    private void assertStored(int word, String text, Set<String> stored) {
        Instruction instruction = disassembler.decode(word, 0);
        assertEquals(text, instruction.text());
        RegisterUsage usage = registers.usage(instruction);
        assertEquals(stored, usage.stored(), text + " stores");
        assertTrue(usage.reads().containsAll(stored), text);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> union = new java.util.HashSet<>(a);
        union.addAll(b);
        return union;
    }

    private static List<String> resource(String name) throws IOException {
        try (var in = Objects.requireNonNull(Aarch64RegisterModelTest.class.getResourceAsStream(name), name);
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).toList();
        }
    }
}
