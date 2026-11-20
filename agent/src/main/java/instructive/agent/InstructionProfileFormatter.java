package instructive.agent;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Prints an {@link InstructionProfile} as a table of its sampled instructions:
 *
 * <pre>
 * Example.loop(J)J
 *   tier 4 (C2), compile id 355, 632 bytes at 0x0000000137ad5540, 176 samples
 *
 *   pc                  bytes          count       %
 *   0x0000000137ad5610  e0 03 0e 2a        5    2.84%
 *   ...
 * </pre>
 *
 * <p>One row per program counter with samples, in address order: the PC (the method's
 * address plus the count's offset), the instruction bytes at the PC, the samples there,
 * and their share of the method's {@link InstructionProfile#total() total}. PCs past the
 * method's instructions, in its stubs, are marked {@code (stub)}. The bytes come from
 * {@link InstructionProfile#machineCode()}; if the method is no longer loaded they are
 * shown as {@code ?}.
 *
 * <p>Sampled PCs are always instruction starts, but how many bytes the instruction has
 * depends on the architecture, which an {@link InstructionLength} decides. On aarch64
 * every instruction is 4 bytes. Without a decoder for variable-length instructions, as
 * on x86-64, the default shows the bytes up to the next sampled PC, at most 15 (the
 * x86 maximum), so the column starts with the sampled instruction but may run on into
 * the ones after it.
 *
 * <p>Formatting a live flyweight from a consumer is allowed while the consumer runs,
 * but counts can change mid-table; format a {@link InstructionProfile#snapshot()} for a
 * consistent one.
 */
public final class InstructionProfileFormatter {

    /** Decides how many bytes of an instruction to show. */
    @FunctionalInterface
    public interface InstructionLength {
        /**
         * The length of the instruction at {@code offset} in {@code code}, given that
         * {@code nextSampled} is the next offset with samples (or {@code code.length}).
         * The result is clamped to the code that remains.
         */
        int at(byte[] code, int offset, int nextSampled);
    }

    /** Fixed 4-byte instructions, as on aarch64. */
    public static final InstructionLength FIXED_4 = (code, offset, nextSampled) -> 4;

    /** Up to the next sampled PC, at most 15 bytes: for x86 when no decoder is available. */
    public static final InstructionLength UP_TO_NEXT_SAMPLE = (code, offset, nextSampled) ->
            Math.min(15, nextSampled - offset);

    private final InstructionLength lengths;

    /** A formatter for the architecture of this JVM. */
    public InstructionProfileFormatter() {
        this(forArchitecture(System.getProperty("os.arch")));
    }

    public InstructionProfileFormatter(InstructionLength lengths) {
        this.lengths = lengths;
    }

    /** The default {@link InstructionLength} for an {@code os.arch} value. */
    public static InstructionLength forArchitecture(String arch) {
        return switch (arch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> FIXED_4;
            default -> UP_TO_NEXT_SAMPLE;
        };
    }

    public void format(InstructionProfile profile, PrintStream out) {
        long total = profile.total();
        int methodSize = profile.methodSize();
        int profileLength = profile.profileLength();
        byte[] code = profile.machineCode().orElse(null);

        out.println(profile.name());
        out.printf(Locale.ROOT, "  %s, compile id %d, %d bytes at 0x%016x, %d samples%n",
                tier(profile.compLevel()), profile.compileId(), methodSize, profile.address(), total);

        int[] offsets = sampledOffsets(profile, profileLength);
        if (offsets.length == 0) {
            out.println("  no samples at any instruction");
            return;
        }
        String[] bytes = new String[offsets.length];
        int bytesWidth = "bytes".length();
        for (int i = 0; i < offsets.length; i++) {
            int next = i + 1 < offsets.length ? offsets[i + 1] : Integer.MAX_VALUE;
            bytes[i] = instructionBytes(code, offsets[i], next);
            bytesWidth = Math.max(bytesWidth, bytes[i].length());
        }

        out.println();
        out.printf(Locale.ROOT, "  %-18s  %-" + bytesWidth + "s  %7s  %7s%n", "pc", "bytes", "count", "%");
        for (int i = 0; i < offsets.length; i++) {
            int offset = offsets[i];
            int count = profile.count(offset);
            double percent = total == 0 ? 0 : 100.0 * count / total;
            out.printf(Locale.ROOT, "  0x%016x  %-" + bytesWidth + "s  %7d  %6.2f%%%s%n",
                    profile.address() + offset, bytes[i], count, percent, offset >= methodSize ? "  (stub)" : "");
        }
    }

    private static int[] sampledOffsets(InstructionProfile profile, int profileLength) {
        int sampled = 0;
        for (int offset = 0; offset < profileLength; offset++) {
            sampled += profile.count(offset) > 0 ? 1 : 0;
        }
        int[] offsets = new int[sampled];
        for (int offset = 0, i = 0; offset < profileLength && i < sampled; offset++) {
            if (profile.count(offset) > 0) {
                offsets[i++] = offset;
            }
        }
        return offsets;
    }

    private String instructionBytes(byte[] code, int offset, int nextSampled) {
        if (code == null || offset >= code.length) {
            return "?";
        }
        int remaining = code.length - offset;
        int length = Math.max(1, Math.min(remaining, lengths.at(code, offset, Math.min(nextSampled, code.length))));
        StringBuilder hex = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                hex.append(' ');
            }
            hex.append(Character.forDigit((code[offset + i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(code[offset + i] & 0xF, 16));
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
