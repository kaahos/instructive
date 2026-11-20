package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InstructionProfileFormatterTest {
    private static final String COMPILED = "instructive.agenttest.CodeCacheTest.compiledLoop(J)J";
    private static final Pattern ROW =
            Pattern.compile("  0x([0-9a-f]{16})  ([0-9a-f ?]+?)\\s+(\\d+)\\s+(\\d+\\.\\d{2})%(  \\(stub\\))?");

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void printsHeaderAndOneRowPerSampledPc() {
        InstructionProfile profile = hottestCompiledLoop();
        byte[] code = profile.machineCode().orElseThrow();
        List<String> lines = format(new InstructionProfileFormatter(), profile);

        assertEquals(COMPILED, lines.get(0));
        assertTrue(lines.get(1).contains("tier " + profile.compLevel()), lines.get(1));
        assertTrue(lines.get(1).contains("compile id " + profile.compileId() + ","), lines.get(1));
        assertTrue(lines.get(1).contains(profile.total() + " samples"), lines.get(1));

        List<Integer> sampled = sampledOffsets(profile);
        List<Matcher> rows = rows(lines);
        assertEquals(sampled.size(), rows.size(), () -> String.join("\n", lines));
        long sum = 0;
        for (int i = 0; i < rows.size(); i++) {
            Matcher row = rows.get(i);
            int offset = sampled.get(i);
            // The PC is recovered from the base address and the count's position.
            assertEquals(profile.address() + offset, Long.parseUnsignedLong(row.group(1), 16));
            String bytes = row.group(2).trim();
            byte[] shown = HexFormat.of().parseHex(bytes.replace(" ", ""));
            byte[] expected = Arrays.copyOfRange(code, offset, offset + shown.length);
            assertEquals(HexFormat.of().formatHex(expected), HexFormat.of().formatHex(shown), "bytes at +" + offset);
            if (System.getProperty("os.arch").equals("aarch64")) {
                assertEquals(4, shown.length, "aarch64 instructions are 4 bytes");
            }
            int count = Integer.parseInt(row.group(3));
            assertEquals(profile.count(offset), count);
            assertEquals(100.0 * count / profile.total(), Double.parseDouble(row.group(4)), 0.005);
            sum += count;
        }
        assertEquals(profile.sampled(), sum);
    }

    @Test
    void variableLengthFallbackStopsAtTheNextSample() {
        InstructionProfile profile = hottestCompiledLoop();
        List<String> lines = format(new InstructionProfileFormatter(InstructionProfileFormatter.UP_TO_NEXT_SAMPLE), profile);
        List<Integer> sampled = sampledOffsets(profile);
        List<Matcher> rows = rows(lines);
        for (int i = 0; i < rows.size(); i++) {
            int length = rows.get(i).group(2).trim().split(" ").length;
            int gap = i + 1 < sampled.size() ? sampled.get(i + 1) - sampled.get(i) : Integer.MAX_VALUE;
            assertTrue(length >= 1 && length <= 15 && length <= gap, () -> String.join("\n", lines));
        }
    }

    private static List<String> format(InstructionProfileFormatter formatter, InstructionProfile profile) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            formatter.format(profile, out);
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static List<Matcher> rows(List<String> lines) {
        List<Matcher> rows = new ArrayList<>();
        for (String line : lines) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                rows.add(row);
            }
        }
        return rows;
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
