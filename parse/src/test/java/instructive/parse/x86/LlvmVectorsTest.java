package instructive.parse.x86;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Checks the decoder against text produced by LLVM's disassembler (Intel syntax, hex
 * immediates, absolute branch targets, a leading {@code lock} merged into the instruction
 * it applies to). The vectors were drawn from code HotSpot compiled, an exhaustive sweep
 * of the one-byte and 0F opcode maps under common prefixes, random encodings, and random
 * encodings of every entry of the opcode table, keeping one example of each instruction
 * shape (several for HotSpot code).
 */
class LlvmVectorsTest {
    private final X86Disassembler disassembler = new X86Disassembler();

    @Test
    void matchesLlvmText() throws IOException {
        List<String> lines = resource("llvm-vectors.tsv");
        List<String> failures = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.split("\t");
            long address = Long.parseUnsignedLong(fields[0], 16);
            byte[] bytes = HexFormat.of().parseHex(fields[1]);
            Instruction instruction = disassembler.decode(bytes, 0, address);
            if (!fields[2].equals(instruction.text()) || instruction.length() != bytes.length) {
                failures.add(fields[1] + " at 0x" + fields[0] + ": expected [" + fields[2] + "] but was ["
                        + instruction.text() + "], length " + instruction.length());
            }
        }
        assertTrue(lines.size() > 30000, "vectors: " + lines.size());
        assertEquals(List.of(), failures.subList(0, Math.min(20, failures.size())), failures.size() + " mismatches");
    }

    @Test
    void doesNotDecodeWhatLlvmCannot() throws IOException {
        List<String> claimed = new ArrayList<>();
        for (String hex : resource("undecodable.txt")) {
            Instruction instruction = disassembler.decode(HexFormat.of().parseHex(hex), 0, 0);
            if (instruction.isDecoded()) {
                claimed.add(hex + " decoded as [" + instruction.text() + "]");
            }
        }
        assertEquals(List.of(), claimed);
    }

    private static List<String> resource(String name) throws IOException {
        try (var in = Objects.requireNonNull(LlvmVectorsTest.class.getResourceAsStream(name), name);
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).toList();
        }
    }
}
