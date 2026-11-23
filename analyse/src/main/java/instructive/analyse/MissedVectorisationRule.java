package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.RegisterModel;
import instructive.parse.RegisterUsage;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags hot loops that C2 unrolled but didn't vectorise: the unrolled copies do the
 * same scalar operations on adjacent elements, operations that have vector equivalents.
 * C2's superword pass packs such copies into vector instructions when it can prove them
 * independent. When it can't, for example because two arrays accessed at different
 * offsets might be the same array, it leaves the copies scalar.
 *
 * <p>Copies are found the way a superword pass seeds its packs, from the stores. For
 * each plain store in an innermost loop, it builds the expression the stored value is
 * computed by within the iteration: operations with vector equivalents (floating-point
 * arithmetic and conversions; integer add, subtract, logic, shifts and extensions, and
 * 32-bit multiplies), down to loads and to values defined outside the loop. Stores whose
 * expressions are identical, operation by operation, immediates included, are copies
 * when:
 * <ul>
 *   <li>they store adjacent elements, as do the loads in each position of their
 *       expressions: their immediate offsets step by the element's size;</li>
 *   <li>they share no instructions, and none uses a value another computed, in this
 *       iteration or the one before, so the copies don't depend on each other;</li>
 *   <li>each loads at least one element.</li>
 * </ul>
 * A store whose expression reaches anything else (a value carried round the loop, a
 * value with more than one definition, an instruction without a vector equivalent such
 * as a 64-bit multiply or a division by an integer) has no copies. Loops containing SIMD
 * instructions are already vectorised and are left out, as are loops with less than
 * {@code minimumShare} of the method's samples (without samples, every loop is
 * considered), which also leaves out the scalar pre- and post-loops around a vectorised
 * loop. Operand matching is AArch64 text.
 *
 * <p>Reductions, which carry the result round the loop instead of storing it, aren't
 * covered: C2 can't vectorise a floating-point sum without changing its rounding.
 *
 * <p>Findings come hottest first.
 */
public final class MissedVectorisationRule {

    /** The default share of the method's samples a loop needs before it is reported. */
    public static final double DEFAULT_MINIMUM_SHARE = 0.1;

    // Scalar operations with a vector equivalent, on any register width.
    private static final Set<String> VECTORISABLE = Set.of(
            "fadd", "fsub", "fmul", "fdiv", "fmadd", "fmsub", "fnmadd", "fnmsub", "fnmul", "fneg", "fabs",
            "fsqrt", "fmax", "fmin", "fmaxnm", "fminnm", "fcvt", "scvtf", "ucvtf", "fcvtzs", "fcvtzu",
            "frintn", "frintm", "frintp", "frintz", "frinta", "frinti", "frintx",
            "add", "sub", "neg", "and", "orr", "eor", "bic", "orn", "mvn", "lsl", "lsr", "asr",
            "sxtb", "sxth", "sxtw", "uxtb", "uxth");
    // Multiplies, which Advanced SIMD has for 32-bit lanes and narrower but not 64-bit ones.
    private static final Set<String> NARROW_ONLY = Set.of("mul", "madd", "msub", "mneg");
    private static final Set<String> MOVES = Set.of("mov", "fmov");
    private static final Set<String> LOADS = Set.of(
            "ldr", "ldur", "ldrb", "ldurb", "ldrh", "ldurh", "ldrsb", "ldursb", "ldrsh", "ldursh", "ldrsw", "ldursw");
    private static final Set<String> STORES = Set.of("str", "stur", "strb", "sturb", "strh", "sturh");

    // A register operand: a general purpose register by width, or a floating-point one.
    private static final Pattern REGISTER = Pattern.compile("\\b(?:[wxbhsdq]\\d+|[wx]zr|sp)\\b");
    // A memory operand with an immediate offset, or none: "[x11, #0x28]", "[x11]".
    private static final Pattern ADDRESS = Pattern.compile("\\[([wx]\\d+), #(-?0x[0-9a-f]+|-?\\d+)\\]$|\\[([wx]\\d+)\\]$");

    private final double minimumShare;

    public MissedVectorisationRule() {
        this(DEFAULT_MINIMUM_SHARE);
    }

    public MissedVectorisationRule(double minimumShare) {
        if (!(minimumShare >= 0 && minimumShare <= 1)) {
            throw new IllegalArgumentException("minimum share not in [0, 1]: " + minimumShare);
        }
        this.minimumShare = minimumShare;
    }

    /** Every loop in {@code instructions} with copies that weren't vectorised, ignoring samples. */
    public List<MissedVectorisation> apply(List<Instruction> instructions, RegisterModel registers) {
        return apply(instructions, registers, instruction -> 0);
    }

    /**
     * The hot loops in {@code instructions}, which must be in address order, whose
     * unrolled copies weren't vectorised: one finding for each set of copies.
     */
    public List<MissedVectorisation> apply(
            List<Instruction> instructions, RegisterModel registers, ToLongFunction<Instruction> sampleCount) {
        ControlFlowGraph controlFlow = ControlFlowGraph.of(instructions);
        DependencyGraph graph = DependencyGraph.of(controlFlow, registers);
        List<Instruction> code = controlFlow.instructions();
        long total = 0;
        for (Instruction instruction : code) {
            total += sampleCount.applyAsLong(instruction);
        }

        List<MissedVectorisation> findings = new ArrayList<>();
        for (int loop = 0; loop < controlFlow.loops().size(); loop++) {
            BitSet blocks = controlFlow.loops().get(loop).blocks();
            Set<Integer> body = new TreeSet<>();
            boolean innermost = true;
            for (int block = blocks.nextSetBit(0); block >= 0; block = blocks.nextSetBit(block + 1)) {
                innermost &= controlFlow.innermostLoop(block) == loop;
                ControlFlowGraph.BasicBlock basicBlock = controlFlow.blocks().get(block);
                for (int i = basicBlock.start(); i < basicBlock.end(); i++) {
                    body.add(i);
                }
            }
            if (!innermost) {
                continue;
            }
            long samples = 0;
            boolean vectorised = false;
            for (int i : body) {
                samples += sampleCount.applyAsLong(code.get(i));
                vectorised |= code.get(i).kind() == InstructionKind.SIMD;
            }
            if (vectorised || (total > 0 && samples < minimumShare * total)) {
                continue;
            }

            Map<String, List<Expression>> byShape = new LinkedHashMap<>();
            for (int i : body) {
                Expression expression = expression(graph, registers, body, i);
                if (expression != null) {
                    byShape.computeIfAbsent(expression.shape, shape -> new ArrayList<>()).add(expression);
                }
            }
            long header = code.get(controlFlow.blocks().get(controlFlow.loops().get(loop).header()).start()).address();
            for (List<Expression> candidates : byShape.values()) {
                List<Expression> copies = copies(graph, candidates);
                if (copies.isEmpty()) {
                    continue;
                }
                List<Instruction> instructionsInLoop = new ArrayList<>();
                for (int i : body) {
                    instructionsInLoop.add(code.get(i));
                }
                List<List<Instruction>> copyInstructions = new ArrayList<>();
                for (Expression copy : copies) {
                    copyInstructions.add(copy.nodes.stream().sorted().map(code::get).toList());
                }
                List<Instruction> operations = copies.get(0).operations.stream().sorted().map(code::get).toList();
                findings.add(new MissedVectorisation(header, instructionsInLoop, samples, copyInstructions, operations,
                        NotUnrolledLoopRule.elementSize(code.get(copies.get(0).store))));
            }
        }
        findings.sort(Comparator.comparingLong(MissedVectorisation::samples).reversed()
                .thenComparingLong(MissedVectorisation::header)
                .thenComparingLong(finding -> finding.copies().get(0).get(0).address()));
        return findings;
    }

    /**
     * A store and the expression computing the value it stores within one iteration.
     *
     * @param shape      the expression as text, with registers replaced by what computes
     *                   them, so copies have the same shape
     * @param nodes      the store, its loads and operations, and the moves between them
     * @param loads      the loads, in the order they appear in {@code shape}
     * @param operations the operations
     */
    private record Expression(int store, String shape, Set<Integer> nodes, List<Integer> loads, Set<Integer> operations) {}

    // The expression stored by instruction `i`, or null if it isn't a plain store of a
    // value computed in the loop from loads by vectorisable operations.
    private static Expression expression(DependencyGraph graph, RegisterModel registers, Set<Integer> body, int i) {
        Instruction store = graph.instruction(i);
        if (!STORES.contains(store.mnemonic()) || offset(store) == null) {
            return null;
        }
        Set<String> stored = registers.usage(store).stored();
        if (stored.size() != 1) {
            return null;
        }
        Builder builder = new Builder(graph, registers, body);
        builder.nodes.add(i);
        String value = builder.shape(i, stored.iterator().next());
        if (builder.failed || builder.loads.isEmpty()) {
            return null;
        }
        String shape = store.mnemonic() + " " + width(store.operands()) + " (" + value + ")";
        return new Expression(i, shape, builder.nodes, builder.loads, builder.operations);
    }

    private static final class Builder {
        final DependencyGraph graph;
        final RegisterModel registers;
        final Set<Integer> body;
        final Set<Integer> nodes = new HashSet<>();
        final List<Integer> loads = new ArrayList<>();
        final Set<Integer> operations = new HashSet<>();
        boolean failed;

        Builder(DependencyGraph graph, RegisterModel registers, Set<Integer> body) {
            this.graph = graph;
            this.registers = registers;
            this.body = body;
        }

        // The shape of the value of `register` as instruction `user` reads it.
        String shape(int user, String register) {
            int writer = writer(user, register);
            if (writer == -1) {
                return "invariant " + register;
            }
            if (writer < 0) {
                failed = true;
                return "?";
            }
            nodes.add(writer);
            Instruction instruction = graph.instruction(writer);
            String mnemonic = instruction.mnemonic();
            if (instruction.kind() == InstructionKind.LOAD) {
                if (!LOADS.contains(mnemonic) || offset(instruction) == null) {
                    failed = true;
                    return "?";
                }
                loads.add(writer);
                return mnemonic + " " + width(instruction.operands());
            }
            List<String> operands = registerOperands(instruction.operands());
            if (MOVES.contains(mnemonic) && operands.size() == 2 && !operands.get(1).endsWith("zr")) {
                return shape(writer, canonical(operands.get(1)));
            }
            boolean vectorisable = VECTORISABLE.contains(mnemonic)
                    || (NARROW_ONLY.contains(mnemonic) && instruction.operands().startsWith("w"));
            if (!vectorisable || operands.isEmpty()) {
                failed = true;
                return "?";
            }
            operations.add(writer);
            // The text with the destination replaced by its width and every source by its shape.
            StringBuilder shape = new StringBuilder(mnemonic).append(' ');
            Matcher matcher = REGISTER.matcher(instruction.operands());
            boolean destination = true;
            while (matcher.find()) {
                String token = matcher.group();
                String replacement;
                if (destination) {
                    replacement = token.substring(0, 1);
                    destination = false;
                } else if (token.endsWith("zr")) {
                    replacement = token;
                } else {
                    replacement = "(" + shape(writer, canonical(token)) + ")";
                }
                matcher.appendReplacement(shape, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(shape);
            return shape.toString();
        }

        // The one instruction in the loop that `register`'s value at `user` comes from
        // within the iteration; -1 if it comes from outside the loop, -2 if from more than
        // one instruction or from the previous iteration.
        int writer(int user, String register) {
            int found = -1;
            for (int writer : graph.dependencies(user)) {
                if (body.contains(writer) && registers.usage(graph.instruction(writer)).writes().contains(register)) {
                    if (found != -1) {
                        return -2;
                    }
                    found = writer;
                }
            }
            for (int writer : graph.loopCarriedDependencies(user)) {
                if (body.contains(writer) && registers.usage(graph.instruction(writer)).writes().contains(register)) {
                    return -2;
                }
            }
            return found;
        }
    }

    // The largest set of `candidates`, all of one shape, that are copies of each other,
    // in the order of the elements they store; empty if there are fewer than two.
    private static List<Expression> copies(DependencyGraph graph, List<Expression> candidates) {
        if (candidates.size() < 2) {
            return List.of();
        }
        List<Expression> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingLong(expression -> offset(graph.instruction(expression.store))));
        // Adjacent elements: each access steps by its element's size from the copy before.
        for (int k = 1; k < sorted.size(); k++) {
            if (!adjacent(graph.instruction(sorted.get(k - 1).store), graph.instruction(sorted.get(k).store))) {
                return List.of();
            }
            for (int load = 0; load < sorted.get(k).loads.size(); load++) {
                if (!adjacent(graph.instruction(sorted.get(k - 1).loads.get(load)),
                        graph.instruction(sorted.get(k).loads.get(load)))) {
                    return List.of();
                }
            }
        }
        // Independent: no shared instructions, and none reads a value another copy computed.
        Map<Integer, Expression> owner = new LinkedHashMap<>();
        for (Expression expression : sorted) {
            for (int node : expression.nodes) {
                if (owner.put(node, expression) != null) {
                    return List.of();
                }
            }
        }
        for (Expression expression : sorted) {
            for (int node : expression.nodes) {
                for (int[] writers : new int[][] {graph.dependencies(node), graph.loopCarriedDependencies(node)}) {
                    for (int writer : writers) {
                        Expression other = owner.get(writer);
                        if (other != null && other != expression) {
                            return List.of();
                        }
                    }
                }
            }
        }
        return sorted;
    }

    // Whether `next` accesses the element after the one `previous` does, judging by their
    // immediate offsets.
    private static boolean adjacent(Instruction previous, Instruction next) {
        int size = NotUnrolledLoopRule.elementSize(next);
        return size > 0 && size == NotUnrolledLoopRule.elementSize(previous)
                && offset(next) - offset(previous) == size;
    }

    // The immediate offset of a load or store addressed by a register and an immediate,
    // or null if it is addressed any other way.
    private static Long offset(Instruction instruction) {
        Matcher address = ADDRESS.matcher(instruction.operands());
        if (!address.find()) {
            return null;
        }
        return address.group(2) == null ? 0 : Long.decode(address.group(2));
    }

    private static List<String> registerOperands(String operands) {
        List<String> found = new ArrayList<>();
        Matcher matcher = REGISTER.matcher(operands);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    // The width letter of an instruction's first register operand: "d" for "d16, ...".
    private static String width(String operands) {
        return operands.isEmpty() ? "" : operands.substring(0, 1);
    }

    // The canonical name of a register, as RegisterUsage gives it.
    private static String canonical(String register) {
        return switch (register.charAt(0)) {
            case 'w', 'x' -> "x" + register.substring(1);
            case 'b', 'h', 's', 'd', 'q' -> "v" + register.substring(1);
            default -> register;
        };
    }
}
