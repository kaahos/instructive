package instructive.parse.aarch64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Checks the decoder against text produced by {@code llvm-objdump}. The vectors were
 * drawn from code HotSpot compiled, hand-written probes of aliases and edge cases, and
 * random words, keeping up to eight examples of each instruction shape.
 */
class LlvmVectorsTest {
    private final Aarch64Disassembler disassembler = new Aarch64Disassembler();

    @Test
    void matchesLlvmText() throws IOException {
        List<String> lines = resource("llvm-vectors.tsv");
        List<String> failures = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.split("\t");
            long address = Long.parseUnsignedLong(fields[0], 16);
            int word = Integer.parseUnsignedInt(fields[1], 16);
            Instruction instruction = disassembler.decode(word, address);
            if (!fields[2].equals(instruction.text())) {
                failures.add(fields[1] + " at 0x" + fields[0] + ": expected [" + fields[2] + "] but was ["
                        + instruction.text() + "]");
            }
        }
        assertTrue(lines.size() > 5000, "vectors: " + lines.size());
        assertEquals(List.of(), failures.subList(0, Math.min(20, failures.size())), failures.size() + " mismatches");
    }

    @Test
    void doesNotDecodeWhatLlvmCannot() throws IOException {
        List<String> claimed = new ArrayList<>();
        for (String word : resource("undecodable.txt")) {
            Instruction instruction = disassembler.decode(Integer.parseUnsignedInt(word, 16), 0);
            if (instruction.isDecoded()) {
                claimed.add(word + " decoded as [" + instruction.text() + "]");
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
