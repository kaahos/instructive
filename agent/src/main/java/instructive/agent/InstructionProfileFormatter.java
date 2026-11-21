package instructive.agent;

import instructive.parse.Disassembler;
import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.x86.X86Disassembler;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Prints an {@link InstructionProfile} as a table of its instructions and samples:
 *
 * <pre>
 * Example.loop(J)J
 *   tier 4 (C2), compile id 355, 632 bytes at 0x0000000137ad5540, 176 samples
 *
 *   pc                  bytes        instruction                count       %
 *   0x0000000137ad5610  ee 03 0b 2a  mov w14, w11                   5    2.84%
 *   0x0000000137ad56e4  6d 00 00 54  b.le 0x137ad5610 &lt;+0xd0&gt;      17    9.66%
 *   ...
 * </pre>
 *
 * <p>Each row is the PC (the method's address plus the count's offset), the
 * instruction's bytes, the instruction disassembled, the samples there and their share
 * of the method's {@link InstructionProfile#total() total}. A direct branch within the
 * method is annotated with its target's offset. PCs past the method's instructions, in
 * its stubs, are marked {@code (stub)}. By default only sampled instructions are
 * shown; {@link #allInstructions()} lists the whole method. The code comes from
 * {@link InstructionProfile#machineCode()}; if the method is no longer loaded the bytes
 * and instructions are shown as {@code ?}.
 *
 * <p>Instructions are decoded with a {@link Disassembler}, by default the one for this
 * JVM's architecture ({@link Aarch64Disassembler} or {@link X86Disassembler}). Without
 * one the instruction column is left out and the bytes column shows the bytes up to the
 * next sampled PC, at most 15 (the longest x86 instruction): it starts with the sampled
 * instruction but may run on into the ones after it.
 *
 * <p>Formatting a live flyweight from a consumer is allowed while the consumer runs,
 * but counts can change mid-table; format a {@link InstructionProfile#snapshot()} for a
 * consistent one.
 */
public final class InstructionProfileFormatter {

    private static final int MAX_UNDECODED_BYTES = 15;

    private final Disassembler disassembler;
    private final boolean allInstructions;

    /** A formatter that disassembles with the decoder for this JVM's architecture, if there is one. */
    public InstructionProfileFormatter() {
        this(forArchitecture(System.getProperty("os.arch")).orElse(null));
    }

    /** A formatter that disassembles with {@code disassembler}, or shows only bytes if it is null. */
    public InstructionProfileFormatter(Disassembler disassembler) {
        this(disassembler, false);
    }

    private InstructionProfileFormatter(Disassembler disassembler, boolean allInstructions) {
        this.disassembler = disassembler;
        this.allInstructions = allInstructions;
    }

    /** The disassembler for an {@code os.arch} value, if there is one. */
    public static Optional<Disassembler> forArchitecture(String arch) {
        return switch (arch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> Optional.of(new Aarch64Disassembler());
            case "amd64", "x86_64" -> Optional.of(new X86Disassembler());
            default -> Optional.empty();
        };
    }

    /**
     * A formatter like this one that lists every instruction in the method, not only the
     * sampled ones, so hot instructions can be read in context. Needs a disassembler;
     * without one, only sampled instructions are shown.
     */
    public InstructionProfileFormatter allInstructions() {
        return new InstructionProfileFormatter(disassembler, true);
    }

    public void format(InstructionProfile profile, PrintStream out) {
        long total = profile.total();
        int methodSize = profile.methodSize();
        byte[] code = profile.machineCode().orElse(null);

        out.println(profile.name());
        out.printf(Locale.ROOT, "  %s, compile id %d, %d bytes at 0x%016x, %d samples%n",
                tier(profile.compLevel()), profile.compileId(), methodSize, profile.address(), total);

        List<Row> rows = rows(profile, code);
        if (rows.isEmpty()) {
            out.println("  no samples at any instruction");
            return;
        }
        boolean disassembled = disassembler != null && code != null;
        int bytesWidth = "bytes".length();
        int instructionWidth = "instruction".length();
        for (Row row : rows) {
            bytesWidth = Math.max(bytesWidth, row.bytes.length());
            instructionWidth = Math.max(instructionWidth, row.instruction.length());
        }

        out.println();
        String instructionHeader = disassembled ? String.format(Locale.ROOT, "  %-" + instructionWidth + "s", "instruction") : "";
        out.printf(Locale.ROOT, "  %-18s  %-" + bytesWidth + "s%s  %7s  %7s%n", "pc", "bytes", instructionHeader, "count", "%");
        for (Row row : rows) {
            String instruction = disassembled ? String.format(Locale.ROOT, "  %-" + instructionWidth + "s", row.instruction) : "";
            String count = row.count == 0 ? "" : Long.toString(row.count);
            String percent = row.count == 0 ? "" : String.format(Locale.ROOT, "%6.2f%%", total == 0 ? 0 : 100.0 * row.count / total);
            String line = String.format(Locale.ROOT, "  0x%016x  %-" + bytesWidth + "s%s  %7s  %7s%s",
                    profile.address() + row.offset, row.bytes, instruction, count, percent,
                    row.offset >= methodSize ? "  (stub)" : "");
            out.println(line.stripTrailing());
        }
    }

    private record Row(int offset, String bytes, String instruction, long count) {}

    private List<Row> rows(InstructionProfile profile, byte[] code) {
        List<Row> rows = new ArrayList<>();
        long address = profile.address();
        if (allInstructions && disassembler != null && code != null) {
            for (Instruction instruction : disassembler.decodeAll(code, address)) {
                int offset = (int) (instruction.address() - address);
                rows.add(new Row(offset, hex(code, offset, instruction.length()), text(instruction, profile),
                        samples(profile, offset, instruction.length())));
            }
            return rows;
        }
        int[] sampled = sampledOffsets(profile);
        for (int i = 0; i < sampled.length; i++) {
            int offset = sampled[i];
            long count = profile.count(offset);
            if (code == null || offset >= code.length) {
                rows.add(new Row(offset, "?", "?", count));
            } else if (disassembler != null) {
                Instruction instruction = disassembler.decode(code, offset, address + offset);
                rows.add(new Row(offset, hex(code, offset, instruction.length()), text(instruction, profile), count));
            } else {
                int next = i + 1 < sampled.length ? Math.min(sampled[i + 1], code.length) : code.length;
                int length = Math.max(1, Math.min(MAX_UNDECODED_BYTES, next - offset));
                rows.add(new Row(offset, hex(code, offset, length), "", count));
            }
        }
        return rows;
    }

    // The instruction's text, with a direct branch within the method annotated with the
    // target's offset from the method's start.
    private static String text(Instruction instruction, InstructionProfile profile) {
        String text = instruction.text();
        if (instruction.branchTarget().isPresent()) {
            long target = instruction.branchTarget().getAsLong() - profile.address();
            if (target >= 0 && target < profile.profileLength()) {
                text += " <+0x" + Long.toHexString(target) + ">";
            }
        }
        return text;
    }

    // Samples on any byte of the instruction: sampled PCs are instruction starts, but
    // counting every byte doesn't depend on that.
    private static long samples(InstructionProfile profile, int offset, int length) {
        long count = 0;
        for (int i = offset, end = Math.min(offset + length, profile.profileLength()); i < end; i++) {
            count += profile.count(i);
        }
        return count;
    }

    private static int[] sampledOffsets(InstructionProfile profile) {
        int profileLength = profile.profileLength();
        int step = profile.bytesPerCount();
        int sampled = 0;
        for (int offset = 0; offset < profileLength; offset += step) {
            sampled += profile.count(offset) > 0 ? 1 : 0;
        }
        int[] offsets = new int[sampled];
        for (int offset = 0, i = 0; offset < profileLength && i < sampled; offset += step) {
            if (profile.count(offset) > 0) {
                offsets[i++] = offset;
            }
        }
        return offsets;
    }

    private static String hex(byte[] code, int offset, int length) {
        int end = Math.min(code.length, offset + length);
        StringBuilder hex = new StringBuilder(length * 3);
        for (int i = offset; i < end; i++) {
            if (i > offset) {
                hex.append(' ');
            }
            hex.append(Character.forDigit((code[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(code[i] & 0xF, 16));
        }
        return hex.toString();
    }

    private static String tier(int compLevel) {
        return switch (compLevel) {
            case 1 -> "tier 1 (C1)";
            case 2 -> "tier 2 (C1, limited profiling)";
            case 3 -> "tier 3 (C1, full profiling)";
            case 4 -> "tier 4 (C2)";
            default -> "tier unknown";
        };
    }
}
