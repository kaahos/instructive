package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.RegisterModel;
import instructive.parse.RegisterUsage;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags hot loops that haven't been unrolled: ones that process a single element per
 * iteration, paying the loop's overhead (the counter, the compare and branch, often a
 * range check) once per element, and giving the CPU only one element's work to overlap
 * between branches. C2 unrolls counted loops, so a hot loop that isn't is usually one C2
 * couldn't treat as counted, for example because its condition calls a method that
 * isn't inlined.
 *
 * <p>For each innermost loop, finds its induction variables: recurrences made only of
 * adds and subtracts of constants and register moves, so the value changes by a fixed
 * step each iteration. Then works out what one step covers from how the variable is
 * used:
 * <ul>
 *   <li>as a scaled or extended index ({@code add x10, x11, w13, sxtw #2}, or
 *       {@code [x1, x2, lsl #2]}), or scaled by an instruction of its own
 *       ({@code sbfiz x10, x29, #2, #32}), it counts elements, so a step of one is one
 *       element;</li>
 *   <li>as a load or store's base register, it counts bytes, so a step of one
 *       register's width is one element: a load of a pair covers two.</li>
 * </ul>
 * A loop is reported if every induction variable classified this way covers one element
 * per iteration and the loop has at least {@code minimumShare} of the method's samples.
 * Without samples, every such loop is reported. The threshold also leaves out the scalar
 * pre- and post-loops C2 wraps around an unrolled loop, which step by one but run only a
 * few times. Loops whose induction variables aren't used either way aren't reported.
 *
 * <p>Findings come hottest first.
 */
public final class NotUnrolledLoopRule {

    /** The default share of the method's samples a loop needs before it is reported. */
    public static final double DEFAULT_MINIMUM_SHARE = 0.1;

    // A register operand extended or shifted, as an index is: "w13, sxtw #2", "x2, lsl #2".
    private static final Pattern INDEX = Pattern.compile("\\b[wx](\\d+), (?:sxtw|uxtw|sxtx|uxtx|lsl)\\b");
    // An instruction scaling or extending its source on its own, as an index:
    // "sbfiz x10, x29, #2, #32", "lsl x1, x2, #3", "sxtw x1, w2".
    private static final Pattern SCALE = Pattern.compile("[wx]\\d+, [wx](\\d+)(?:, #\\d+)*");
    private static final Set<String> SCALING = Set.of("sbfiz", "ubfiz", "lsl", "sxtw", "uxtw");
    private static final Pattern STEP = Pattern.compile("[wx](\\d+), [wx](\\d+), #(-?0x[0-9a-f]+|-?\\d+)(?:, lsl #12)?");
    private static final Pattern MOVE = Pattern.compile("[wx](\\d+), [wx](\\d+)");
    private static final Pattern BASE = Pattern.compile("\\[([wx]\\d+|sp)\\b");

    private final double minimumShare;

    public NotUnrolledLoopRule() {
        this(DEFAULT_MINIMUM_SHARE);
    }

    public NotUnrolledLoopRule(double minimumShare) {
        if (!(minimumShare >= 0 && minimumShare <= 1)) {
            throw new IllegalArgumentException("minimum share not in [0, 1]: " + minimumShare);
        }
        this.minimumShare = minimumShare;
    }

    /** Every loop in {@code instructions} that isn't unrolled, ignoring samples. */
    public List<NotUnrolledLoop> apply(List<Instruction> instructions, RegisterModel registers) {
        return apply(instructions, registers, instruction -> 0);
    }

    /**
     * The hot loops in {@code instructions}, which must be in address order, that aren't
     * unrolled.
     */
    public List<NotUnrolledLoop> apply(
            List<Instruction> instructions, RegisterModel registers, ToLongFunction<Instruction> sampleCount) {
        ControlFlowGraph controlFlow = ControlFlowGraph.of(instructions);
        DependencyGraph graph = DependencyGraph.of(controlFlow, registers);
        List<Instruction> code = controlFlow.instructions();
        long total = 0;
        for (Instruction instruction : code) {
            total += sampleCount.applyAsLong(instruction);
        }

        List<NotUnrolledLoop> findings = new ArrayList<>();
        for (int loop = 0; loop < controlFlow.loops().size(); loop++) {
            BitSet blocks = controlFlow.loops().get(loop).blocks();
            List<Integer> body = new ArrayList<>();
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
            body.sort(null);
            long samples = 0;
            for (int i : body) {
                samples += sampleCount.applyAsLong(code.get(i));
            }
            if (total > 0 && samples < minimumShare * total) {
                continue;
            }

            InductionVariable oneElement = null;
            boolean unrolled = false;
            for (InductionVariable variable : inductionVariables(graph, registers, loop)) {
                long elements = elementsPerStep(graph, registers, body, variable);
                if (elements > 1) {
                    unrolled = true;
                } else if (elements == 1 && oneElement == null) {
                    oneElement = variable;
                }
            }
            if (unrolled || oneElement == null) {
                continue;
            }
            List<Instruction> instructionsInLoop = new ArrayList<>();
            List<Instruction> calls = new ArrayList<>();
            for (int i : body) {
                Instruction instruction = code.get(i);
                instructionsInLoop.add(instruction);
                if (instruction.kind() == InstructionKind.CALL || instruction.kind() == InstructionKind.INDIRECT_CALL) {
                    calls.add(instruction);
                }
            }
            long header = code.get(controlFlow.blocks().get(controlFlow.loops().get(loop).header()).start()).address();
            findings.add(new NotUnrolledLoop(header, instructionsInLoop, samples, oneElement.register(),
                    oneElement.step(), oneElement.instructions(), oneElement.wide(), calls));
        }
        findings.sort(Comparator.comparingLong(NotUnrolledLoop::samples).reversed()
                .thenComparingLong(NotUnrolledLoop::header));
        return findings;
    }

    /**
     * A value stepped by a constant each iteration.
     *
     * @param register     the register the stepped value is read from, by canonical name:
     *                     the one the cycle's last instruction writes
     * @param registers    every register holding it on the way round
     * @param cycle        the cycle's instruction indexes
     */
    private record InductionVariable(
            String register, Set<String> registers, List<Integer> cycle, long step, boolean wide,
            List<Instruction> instructions) {}

    // The loop's recurrences made only of adds or subtracts of constants and moves.
    private static List<InductionVariable> inductionVariables(DependencyGraph graph, RegisterModel registers, int loop) {
        List<InductionVariable> variables = new ArrayList<>();
        Set<Set<Integer>> seen = new HashSet<>();
        for (DependencyGraph.Recurrence recurrence : graph.recurrences()) {
            if (recurrence.loop() != loop || !seen.add(new HashSet<>(recurrence.cycle()))) {
                continue;
            }
            long step = 0;
            boolean wide = false;
            boolean stepping = true;
            Set<String> held = new LinkedHashSet<>();
            List<Instruction> instructions = new ArrayList<>();
            for (int i : recurrence.cycle()) {
                Instruction instruction = graph.instruction(i);
                instructions.add(instruction);
                held.addAll(registers.usage(instruction).writes());
                Matcher constant = STEP.matcher(instruction.operands());
                if ((instruction.mnemonic().equals("add") || instruction.mnemonic().equals("sub")) && constant.matches()) {
                    long amount = Long.decode(constant.group(3));
                    if (instruction.operands().endsWith("lsl #12")) {
                        amount <<= 12;
                    }
                    step += instruction.mnemonic().equals("add") ? amount : -amount;
                    wide |= instruction.operands().startsWith("x");
                } else if (!(instruction.mnemonic().equals("mov") && MOVE.matcher(instruction.operands()).matches())) {
                    stepping = false;
                    break;
                }
            }
            if (stepping && step != 0) {
                Instruction last = graph.instruction(recurrence.cycle().get(recurrence.cycle().size() - 1));
                String register = registers.usage(last).writes().iterator().next();
                variables.add(new InductionVariable(register, held, recurrence.cycle(), step, wide, instructions));
            }
        }
        return variables;
    }

    // How many elements one step of `variable` covers: the most implied by any use as an
    // index or a base register, or 0 if it has neither use.
    private static long elementsPerStep(
            DependencyGraph graph, RegisterModel registers, List<Integer> body, InductionVariable variable) {
        Set<Integer> cycle = new HashSet<>(variable.cycle());
        long most = 0;
        for (int i : body) {
            if (cycle.contains(i)) {
                continue;
            }
            Instruction instruction = graph.instruction(i);
            Set<String> fromVariable = readsFrom(graph, registers, i, cycle, variable.registers());
            if (fromVariable.isEmpty()) {
                continue;
            }
            Matcher scale = SCALE.matcher(instruction.operands());
            if (SCALING.contains(instruction.mnemonic()) && scale.matches() && fromVariable.contains("x" + scale.group(1))) {
                most = Math.max(most, Math.abs(variable.step()));
            }
            Matcher index = INDEX.matcher(instruction.operands());
            while (index.find()) {
                if (fromVariable.contains("x" + index.group(1))) {
                    most = Math.max(most, Math.abs(variable.step()));
                }
            }
            if (instruction.kind() == InstructionKind.LOAD || instruction.kind() == InstructionKind.STORE) {
                Matcher base = BASE.matcher(instruction.operands());
                if (base.find() && fromVariable.contains(canonical(base.group(1)))) {
                    int size = elementSize(instruction);
                    if (size > 0 && variable.step() % size == 0) {
                        most = Math.max(most, Math.abs(variable.step()) / size);
                    }
                }
            }
        }
        return most;
    }

    // The registers instruction `i` reads whose value comes from the cycle.
    private static Set<String> readsFrom(
            DependencyGraph graph, RegisterModel registers, int i, Set<Integer> cycle, Set<String> held) {
        Set<String> reads = new HashSet<>(registers.usage(graph.instruction(i)).reads());
        reads.retainAll(held);
        Set<String> fromCycle = new HashSet<>();
        for (int[] writers : new int[][] {graph.dependencies(i), graph.loopCarriedDependencies(i)}) {
            for (int writer : writers) {
                if (cycle.contains(writer)) {
                    RegisterUsage usage = registers.usage(graph.instruction(writer));
                    for (String register : reads) {
                        if (usage.writes().contains(register)) {
                            fromCycle.add(register);
                        }
                    }
                }
            }
        }
        return fromCycle;
    }

    private static String canonical(String register) {
        return register.equals("sp") ? "sp" : "x" + register.substring(1);
    }

    // The size of one element a load or store moves (one register of a pair), or 0 if
    // unknown.
    static int elementSize(Instruction instruction) {
        String mnemonic = instruction.mnemonic();
        if (mnemonic.endsWith("sw")) {
            return 4;
        }
        if (mnemonic.endsWith("b")) {
            return 1;
        }
        if (mnemonic.endsWith("h")) {
            return 2;
        }
        String operands = instruction.operands();
        // A store-exclusive's first operand is its status register.
        if (mnemonic.matches("stl?xp?r?")) {
            operands = operands.substring(operands.indexOf(',') + 1).trim();
        }
        return switch (operands.isEmpty() ? ' ' : operands.charAt(0)) {
            case 'b' -> 1;
            case 'h' -> 2;
            case 'w', 's' -> 4;
            case 'x', 'd' -> 8;
            case 'q' -> 16;
            default -> 0;
        };
    }
}
