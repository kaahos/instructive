package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LongDependencyChainRuleTest {
    // C2's 8x unrolled loop for ArraysSupport.hashCode(int[]), h = 31 * h + a[i], on
    // AArch64, as bytes in memory order. Each element is lsl #5, sub, add on h.
    private static final String[] HASH_LOOP = {
        "41 c9 22 8b", // add x1, x10, w2, sxtw #2
        "8b 68 1b 53", // lsl w11, w4, #5
        "2c 10 40 b9", // ldr w12, [x1, #0x10]
        "6b 01 04 4b", // sub w11, w11, w4
        "6c 01 0c 0b", // add w12, w11, w12
        "8e 69 1b 53", // lsl w14, w12, #5
        "2b 14 40 b9", // ldr w11, [x1, #0x14]
        "cc 01 0c 4b", // sub w12, w14, w12
        "8c 01 0b 0b", // add w12, w12, w11
        "8e 69 1b 53", // lsl w14, w12, #5
        "2b 18 40 b9", // ldr w11, [x1, #0x18]
        "cc 01 0c 4b", // sub w12, w14, w12
        "8c 01 0b 0b", // add w12, w12, w11
        "8b 69 1b 53", // lsl w11, w12, #5
        "2e 1c 40 b9", // ldr w14, [x1, #0x1c]
        "6c 01 0c 4b", // sub w12, w11, w12
        "8b 01 0e 0b", // add w11, w12, w14
        "6f 69 1b 53", // lsl w15, w11, #5
        "2c 20 40 b9", // ldr w12, [x1, #0x20]
        "eb 01 0b 4b", // sub w11, w15, w11
        "6c 01 0c 0b", // add w12, w11, w12
        "8f 69 1b 53", // lsl w15, w12, #5
        "2b 24 40 b9", // ldr w11, [x1, #0x24]
        "ec 01 0c 4b", // sub w12, w15, w12
        "8c 01 0b 0b", // add w12, w12, w11
        "8f 69 1b 53", // lsl w15, w12, #5
        "2b 28 40 b9", // ldr w11, [x1, #0x28]
        "ec 01 0c 4b", // sub w12, w15, w12
        "8c 01 0b 0b", // add w12, w12, w11
        "8f 69 1b 53", // lsl w15, w12, #5
        "2b 2c 40 b9", // ldr w11, [x1, #0x2c]
        "ec 01 0c 4b", // sub w12, w15, w12
        "42 20 00 11", // add w2, w2, #0x8
        "84 01 0b 0b", // add w4, w12, w11
        "5f 00 00 6b", // cmp w2, w0
        "ab fb ff 54", // b.lt (back to the first instruction)
    };

    private final Aarch64Disassembler disassembler = new Aarch64Disassembler();
    private final Aarch64RegisterModel registers = new Aarch64RegisterModel();

    @Test
    void findsTheHashLoopsSerialChain() {
        List<Instruction> loop = decode(HASH_LOOP);
        List<DependencyChain> chains = new LongDependencyChainRule().apply(loop, registers);
        assertEquals(1, chains.size(), () -> describe(chains));
        DependencyChain chain = chains.get(0);
        // lsl, sub, add for each of the 8 elements: from the h read at the top to the h
        // carried into the next iteration.
        assertEquals(24, chain.length(), () -> describe(chains));
        assertEquals("lsl w11, w4, #5", chain.first().text());
        assertEquals("add w4, w12, w11", chain.last().text());
        List<String> mnemonics = chain.instructions().stream().map(Instruction::mnemonic).collect(Collectors.toList());
        for (int i = 0; i < mnemonics.size(); i += 3) {
            assertEquals(List.of("lsl", "sub", "add"), mnemonics.subList(i, i + 3), mnemonics::toString);
        }
    }

    @Test
    void linksEachReadToTheClosestWriter() {
        List<Instruction> instructions = decode(
                "20 04 00 91", // add x0, x1, #0x1
                "40 04 00 91", // add x0, x2, #0x1
                "00 04 00 91"); // add x0, x0, #0x1
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        // The last add depends on the second, which overwrote x0, not the first.
        assertArrayEquals(new int[] {1}, graph.predecessors(2));
        assertEquals(1, graph.chainLength(0));
        assertEquals(1, graph.chainLength(1));
        assertEquals(2, graph.chainLength(2));
        assertEquals(List.of(1, 2), graph.longestChainEndingAt(2));
    }

    @Test
    void independentInstructionsDoNotChain() {
        List<Instruction> instructions = decode(
                "20 04 00 91", // add x0, x1, #0x1
                "62 04 00 91", // add x2, x3, #0x1
                "a4 04 00 91", // add x4, x5, #0x1
                "e6 04 00 91"); // add x6, x7, #0x1
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        for (int i = 0; i < graph.size(); i++) {
            assertEquals(1, graph.chainLength(i));
        }
        assertEquals(List.of(), new LongDependencyChainRule(2).apply(instructions, registers));
    }

    @Test
    void followsBaseRegisterWriteback() {
        // ldr x0, [x1], #0x8 four times: each depends on the previous update of x1.
        List<Instruction> instructions = decode("20 84 40 f8", "20 84 40 f8", "20 84 40 f8", "20 84 40 f8");
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        assertEquals(4, graph.chainLength(3));
    }

    @Test
    void undecodedInstructionsBreakChains() {
        List<Instruction> instructions = decode(
                "00 04 00 91", // add x0, x0, #0x1
                "00 04 00 91", // add x0, x0, #0x1
                "31 5a 20 0e", // cnt.8b: vector SIMD, not decoded
                "00 04 00 91", // add x0, x0, #0x1
                "00 04 00 91"); // add x0, x0, #0x1
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        assertEquals(2, graph.chainLength(1));
        assertEquals(2, graph.chainLength(4));
    }

    @Test
    void ordersHottestFirst() {
        List<String> code = new ArrayList<>();
        code.addAll(List.of(repeat("00 04 00 91", 10))); // add x0, x0, #0x1: a chain of 10
        code.addAll(List.of(repeat("21 04 00 91", 12))); // add x1, x1, #0x1: a chain of 12
        List<Instruction> instructions = decode(code.toArray(new String[0]));
        // Put more samples on the shorter chain: it comes first.
        Map<Boolean, Long> weight = Map.of(true, 5L, false, 1L);
        List<DependencyChain> chains = new LongDependencyChainRule(8)
                .apply(instructions, registers, instruction -> weight.get(instruction.operands().startsWith("x0")));
        assertEquals(2, chains.size());
        assertEquals(10, chains.get(0).length());
        assertEquals(50, chains.get(0).samples());
        assertEquals(50, chains.get(0).spanSamples());
        assertEquals(12, chains.get(1).length());
        assertEquals(12, chains.get(1).samples());
    }

    @Test
    void countsSamplesSkiddedOntoNearbyInstructions() {
        // A chain through x0, interleaved with independent instructions on x5.
        List<Instruction> instructions = decode(
                "00 04 00 91", // add x0, x0, #0x1
                "a5 04 00 91", // add x5, x5, #0x1
                "00 04 00 91", // add x0, x0, #0x1
                "a5 04 00 91", // add x5, x5, #0x1
                "00 04 00 91"); // add x0, x0, #0x1
        // All the samples land on the instructions between the chain's.
        List<DependencyChain> chains = new LongDependencyChainRule(3).apply(
                instructions, registers, instruction -> instruction.operands().startsWith("x5") ? 7 : 0);
        DependencyChain chain = chains.stream().filter(c -> c.first().operands().startsWith("x0")).findFirst().orElseThrow();
        assertEquals(3, chain.length());
        assertEquals(0, chain.samples());
        assertEquals(14, chain.spanSamples());
    }

    @Test
    void rejectsChainsShorterThanTwo() {
        assertThrows(IllegalArgumentException.class, () -> new LongDependencyChainRule(1));
    }

    private static String[] repeat(String bytes, int times) {
        String[] repeated = new String[times];
        Arrays.fill(repeated, bytes);
        return repeated;
    }

    // Instructions from their bytes in memory order, laid out consecutively from 0x1000.
    private List<Instruction> decode(String... instructions) {
        byte[] code = new byte[instructions.length * 4];
        for (int i = 0; i < instructions.length; i++) {
            String[] bytes = instructions[i].split(" ");
            for (int b = 0; b < 4; b++) {
                code[i * 4 + b] = (byte) Integer.parseInt(bytes[b], 16);
            }
        }
        return disassembler.decodeAll(code, 0x1000);
    }

    private static String describe(List<DependencyChain> chains) {
        return chains.stream()
                .map(chain -> chain.length() + ": " + chain.instructions().stream().map(Instruction::text).collect(Collectors.joining("; ")))
                .collect(Collectors.joining("\n"));
    }
}
