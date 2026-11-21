package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64Disassembler;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A method's code and samples, from a test resource listing (bytes, samples, then the
 * disassembly for the reader, tab-separated; {@code #} starts a comment), decoded at
 * {@link #BASE}.
 */
record Listing(List<Instruction> code, Map<Long, Long> samples) {
    static final long BASE = 0x1000;

    static Listing load(String resource) {
        List<String> words = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        try (InputStream in = Listing.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no resource " + resource);
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t");
                words.add(columns[0]);
                counts.add(Long.parseLong(columns[1]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Instruction> code = decode(words.toArray(String[]::new));
        Map<Long, Long> samples = new HashMap<>();
        for (int i = 0; i < code.size(); i++) {
            samples.put(code.get(i).address(), counts.get(i));
        }
        return new Listing(code, samples);
    }

    /** Decodes space-separated bytes, in memory order, at {@link #BASE}. */
    static List<Instruction> decode(String... words) {
        byte[] code = HexFormat.ofDelimiter(" ").parseHex(String.join(" ", words));
        return new Aarch64Disassembler().decodeAll(code, BASE);
    }

    long count(Instruction instruction) {
        return samples.getOrDefault(instruction.address(), 0L);
    }

    long total() {
        return samples.values().stream().mapToLong(Long::longValue).sum();
    }

    /** The instruction at {@code offset} from {@link #BASE}. */
    Instruction at(int offset) {
        return code.stream().filter(i -> i.address() == BASE + offset).findFirst().orElseThrow();
    }
}
