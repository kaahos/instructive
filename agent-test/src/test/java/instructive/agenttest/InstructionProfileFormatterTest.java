package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.parse.Disassembler;
import instructive.parse.Instruction;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InstructionProfileFormatterTest {
    private static final String COMPILED = "instructive.agenttest.CodeCacheTest.compiledLoop(J)J";

    /** One table row, split at the header's column positions. */
    record Row(long pc, String bytes, String instruction, long count, String percent, boolean stub) {}

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void printsHeaderAndDisassemblesEachSampledPc() {
        Disassembler disassembler = assumeDisassembler();
        InstructionProfile profile = hottestCompiledLoop();
        byte[] code = profile.machineCode().orElseThrow();
        List<String> lines = format(new InstructionProfileFormatter(), profile);

        assertEquals(COMPILED, lines.get(0));
        assertTrue(lines.get(1).contains("tier " + profile.compLevel()), lines.get(1));
        assertTrue(lines.get(1).contains("compile id " + profile.compileId() + ","), lines.get(1));
        assertTrue(lines.get(1).contains(profile.total() + " samples"), lines.get(1));

        List<Integer> sampled = sampledOffsets(profile);
        List<Row> rows = rows(lines);
        assertEquals(sampled.size(), rows.size(), () -> String.join("\n", lines));
        long sum = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int offset = sampled.get(i);
            // The PC is recovered from the base address and the count's position.
            assertEquals(profile.address() + offset, row.pc());
            Instruction expected = disassembler.decode(code, offset, row.pc());
            assertEquals(hex(Arrays.copyOfRange(code, offset, offset + expected.length())), row.bytes(), "bytes at +" + offset);
            assertTrue(row.instruction().startsWith(expected.text()), () -> row + " vs " + expected);
            assertEquals(profile.count(offset), row.count());
            assertEquals(100.0 * row.count() / profile.total(), Double.parseDouble(row.percent().replace("%", "")), 0.005);
            sum += row.count();
        }
        assertEquals(profile.sampled(), sum);
    }

    @Test
    void listsEveryInstructionInContext() {
        Disassembler disassembler = assumeDisassembler();
        InstructionProfile profile = hottestCompiledLoop();
        byte[] code = profile.machineCode().orElseThrow();
        List<String> lines = format(new InstructionProfileFormatter().allInstructions(), profile);
        List<Row> rows = rows(lines);
        // Every instruction once, in order, sampled or not.
        List<Instruction> instructions = disassembler.decodeAll(code, profile.address());
        assertEquals(instructions.size(), rows.size(), () -> String.join("\n", lines));
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(instructions.get(i).address(), rows.get(i).pc());
        }
        assertEquals(profile.sampled(), rows.stream().mapToLong(Row::count).sum());
        assertTrue(rows.stream().anyMatch(row -> row.count() == 0 && row.percent().isEmpty()), "cold rows are blank");
        // The loop's back-edge points into the method, so it is annotated with an offset.
        assertTrue(rows.stream().anyMatch(row -> row.instruction().matches("(b\\.|j)\\w+ 0x[0-9a-f]+ <\\+0x[0-9a-f]+>")),
                () -> String.join("\n", lines));
    }

    @Test
    void withoutADisassemblerShowsBytesUpToTheNextSample() {
        InstructionProfile profile = hottestCompiledLoop();
        List<String> lines = format(new InstructionProfileFormatter(null), profile);
        assertFalse(lines.stream().anyMatch(line -> line.contains("instruction")), "no instruction column");
        List<Integer> sampled = sampledOffsets(profile);
        List<Row> rows = rows(lines);
        assertEquals(sampled.size(), rows.size());
        for (int i = 0; i < rows.size(); i++) {
            int length = rows.get(i).bytes().split(" ").length;
            int gap = i + 1 < sampled.size() ? sampled.get(i + 1) - sampled.get(i) : Integer.MAX_VALUE;
            assertTrue(length >= 1 && length <= 15 && length <= gap, () -> String.join("\n", lines));
        }
    }

    private static Disassembler assumeDisassembler() {
        var disassembler = InstructionProfileFormatter.forArchitecture(System.getProperty("os.arch"));
        assumeTrue(disassembler.isPresent(), "no decoder for " + System.getProperty("os.arch"));
        return disassembler.get();
    }

    // Splits rows using the header: "pc", "bytes" and "instruction" start their columns;
    // "count" and "%" end theirs, being right-aligned in seven characters.
    private static List<Row> rows(List<String> lines) {
        String header = lines.stream().filter(line -> line.trim().startsWith("pc ")).findFirst().orElseThrow();
        int bytes = header.indexOf("bytes");
        int instruction = header.indexOf("instruction");
        int countEnd = header.indexOf("count") + "count".length();
        int percentEnd = header.lastIndexOf('%') + 1;
        int bytesEnd = instruction >= 0 ? instruction : countEnd - 7;
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("  0x")) {
                continue;
            }
            String padded = line.length() < percentEnd ? line + " ".repeat(percentEnd - line.length()) : line;
            String count = padded.substring(countEnd - 7, countEnd).trim();
            rows.add(new Row(
                    Long.parseUnsignedLong(padded.substring(4, 20), 16),
                    padded.substring(bytes, bytesEnd).trim(),
                    instruction >= 0 ? padded.substring(instruction, countEnd - 7).trim() : "",
                    count.isEmpty() ? 0 : Long.parseLong(count),
                    padded.substring(percentEnd - 7, percentEnd).trim(),
                    padded.substring(percentEnd).contains("(stub)")));
        }
        return rows;
    }

    private static List<String> format(InstructionProfileFormatter formatter, InstructionProfile profile) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            formatter.format(profile, out);
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static String hex(byte[] bytes) {
        List<String> pairs = new ArrayList<>();
        for (byte b : bytes) {
            pairs.add(HexFormat.of().toHexDigits(b));
        }
        return String.join(" ", pairs);
    }

    private static List<Integer> sampledOffsets(InstructionProfile profile) {
        List<Integer> offsets = new ArrayList<>();
        for (int offset = 0; offset < profile.profileLength(); offset++) {
            if (profile.count(offset) > 0) {
                offsets.add(offset);
            }
        }
        return offsets;
    }

    private static InstructionProfile hottestCompiledLoop() {
        assertTrue(Sampler.start());
        try {
            CodeCacheTest.compiledLoop(400);
        } finally {
            Sampler.stop();
        }
        InstructionProfile[] hottest = new InstructionProfile[1];
        Sampler.forEachHottestMethod(Integer.MAX_VALUE, profile -> {
            if (hottest[0] == null && COMPILED.equals(profile.name())) {
                hottest[0] = profile.snapshot();
            }
        });
        assertNotNull(hottest[0], "no samples of " + COMPILED);
        return hottest[0];
    }
}
