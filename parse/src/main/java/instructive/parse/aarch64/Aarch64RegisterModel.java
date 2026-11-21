package instructive.parse.aarch64;

import instructive.parse.Instruction;
import instructive.parse.RegisterModel;
import instructive.parse.RegisterUsage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Register usage of instructions decoded by {@link Aarch64Disassembler}, worked out from
 * their mnemonic and operands.
 *
 * <p>Canonical names: {@code x0}-{@code x30} for general registers (whether written
 * {@code w} or {@code x}), {@code sp} for the stack pointer, and {@code v0}-{@code v31}
 * for floating point and SIMD registers (whether written {@code b}, {@code h},
 * {@code s}, {@code d}, {@code q} or {@code v}). The zero register is left out.
 *
 * <p>Generally the first operand is written and the rest are read. The exceptions:
 * <ul>
 *   <li>compares, tests, conditional branches on a register, indirect branches, stores,
 *       prefetches and system register writes only read;</li>
 *   <li>loads write their destination(s) and read the address registers;</li>
 *   <li>pre- and post-indexed memory operands also write their base register;</li>
 *   <li>{@code movk}, {@code bfi} and {@code bfxil} read the destination too, since they
 *       only replace some of its bits;</li>
 *   <li>atomics: {@code ldadd}/{@code swp} and friends read the operand register and write
 *       the loaded value, compare-and-swap reads both and writes back the comparison
 *       register, and store-exclusive writes its status register;</li>
 *   <li>{@code bl} and {@code blr} write the link register, {@code x30}, and {@code ret}
 *       reads it.</li>
 * </ul>
 * Condition flags are not modelled.
 */
public final class Aarch64RegisterModel implements RegisterModel {

    private static final Pattern REGISTER = Pattern.compile("\\b(?:([wx])(\\d{1,2})|[wx]zr|w?sp|([bhsdqv])(\\d{1,2}))\\b");

    private static final Set<String> READ_ONLY = Set.of(
            "cmp", "cmn", "tst", "ccmp", "ccmn", "fcmp", "fcmpe", "cbz", "cbnz", "tbz", "tbnz", "br", "prfm",
            "prfum", "msr", "dc", "ic", "sys");
    private static final Set<String> READ_MODIFY_WRITE = Set.of("movk", "bfi", "bfxil");
    private static final Pattern ATOMIC_LOAD = Pattern.compile("ld(add|clr|eor|set|smax|smin|umax|umin)(a|l|al)?[bh]?");
    private static final Pattern ATOMIC_STORE = Pattern.compile("st(add|clr|eor|set|smax|smin|umax|umin)l?[bh]?");
    private static final Pattern SWAP = Pattern.compile("swp(a|l|al)?[bh]?");
    private static final Pattern COMPARE_AND_SWAP = Pattern.compile("cas(a|l|al)?[bh]?");
    private static final Pattern STORE_EXCLUSIVE = Pattern.compile("stl?xr[bh]?");
    private static final Set<String> LOAD_PAIRS = Set.of("ldp", "ldnp", "ldpsw");

    @Override
    public RegisterUsage usage(Instruction instruction) {
        if (!instruction.isDecoded()) {
            return RegisterUsage.UNKNOWN;
        }
        String mnemonic = instruction.mnemonic();
        List<String> operands = split(instruction.operands());
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        int memory = memoryOperand(operands);

        switch (mnemonic) {
            case "bl" -> writes.add("x30");
            case "blr" -> {
                registers(operands.get(0), reads);
                writes.add("x30");
            }
            case "ret" -> {
                if (operands.isEmpty()) {
                    reads.add("x30");
                } else {
                    registers(operands.get(0), reads);
                }
            }
            case "mrs" -> registers(operands.get(0), writes);
            default -> {
                if (READ_ONLY.contains(mnemonic) || ATOMIC_STORE.matcher(mnemonic).matches()) {
                    operands.forEach(operand -> registers(operand, reads));
                } else if (ATOMIC_LOAD.matcher(mnemonic).matches() || SWAP.matcher(mnemonic).matches()) {
                    registers(operands.get(0), reads);
                    registers(operands.get(2), reads);
                    registers(operands.get(1), writes);
                } else if (COMPARE_AND_SWAP.matcher(mnemonic).matches()) {
                    operands.forEach(operand -> registers(operand, reads));
                    registers(operands.get(0), writes);
                } else if (STORE_EXCLUSIVE.matcher(mnemonic).matches()) {
                    registers(operands.get(0), writes);
                    for (int i = 1; i < operands.size(); i++) {
                        registers(operands.get(i), reads);
                    }
                } else if (mnemonic.startsWith("ld")) {
                    int destinations = LOAD_PAIRS.contains(mnemonic) ? 2 : 1;
                    for (int i = 0; i < operands.size(); i++) {
                        registers(operands.get(i), i < destinations ? writes : reads);
                    }
                } else if (mnemonic.startsWith("st")) {
                    operands.forEach(operand -> registers(operand, reads));
                } else if (!mnemonic.equals("b") && !mnemonic.startsWith("b.") && !operands.isEmpty()) {
                    // b, b.cond, and anything without operands (hints, barriers) use none.
                    registers(operands.get(0), writes);
                    if (READ_MODIFY_WRITE.contains(mnemonic)) {
                        registers(operands.get(0), reads);
                    }
                    for (int i = 1; i < operands.size(); i++) {
                        registers(operands.get(i), reads);
                    }
                }
            }
        }
        if (memory >= 0 && writesBack(operands, memory)) {
            registers(baseRegister(operands.get(memory)), writes);
        }
        return new RegisterUsage(reads, writes, true);
    }

    // Pre-indexed ([xn, #imm]!) or post-indexed ([xn], #imm) addressing updates the base.
    private static boolean writesBack(List<String> operands, int memory) {
        return operands.get(memory).endsWith("!") || (memory + 1 < operands.size() && operands.get(memory + 1).startsWith("#"));
    }

    private static int memoryOperand(List<String> operands) {
        for (int i = 0; i < operands.size(); i++) {
            if (operands.get(i).startsWith("[")) {
                return i;
            }
        }
        return -1;
    }

    private static String baseRegister(String memory) {
        int end = memory.indexOf(',');
        return memory.substring(1, end >= 0 ? end : memory.indexOf(']'));
    }

    // Splits at commas outside brackets: "x0, [x1, #0x8]!" is two operands.
    static List<String> split(String operands) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < operands.length(); i++) {
            char c = operands.charAt(i);
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(operands.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < operands.length()) {
            result.add(operands.substring(start).trim());
        }
        return result;
    }

    // Adds the canonical names of the registers in `operand` to `into`.
    private static void registers(String operand, Set<String> into) {
        Matcher matcher = REGISTER.matcher(operand);
        while (matcher.find()) {
            String name = canonical(matcher);
            if (name != null) {
                into.add(name);
            }
        }
    }

    private static String canonical(Matcher matcher) {
        if (matcher.group(2) != null) {
            int number = Integer.parseInt(matcher.group(2));
            return number <= 30 ? "x" + number : null;
        }
        if (matcher.group(4) != null) {
            int number = Integer.parseInt(matcher.group(4));
            return number <= 31 ? "v" + number : null;
        }
        String text = matcher.group();
        return text.endsWith("sp") ? "sp" : null;
    }
}
