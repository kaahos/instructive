package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.RegisterModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Flags pointer chasing: loops in which a load's result becomes the next load's address,
 * from one iteration to the next. Walking a linked list ({@code node = node.next}) is the
 * classic example. Unlike an array walk, where every address is known in advance and the
 * loads overlap, each load here waits for the one before, so the loop runs at the
 * latency of its loads.
 *
 * <p>Built on {@link DependencyGraph#recurrences()}: a loop-carried recurrence whose cycle
 * contains a load. A load only reads registers to form its address, so a load on the
 * cycle has its address computed from the value the previous iteration carried in. The
 * cycle may also decode compressed references ({@code lsl x17, x1, #3}) or move the
 * pointer between registers.
 *
 * <p>Loops with less than {@code minimumShare} of the method's samples aren't reported
 * (without samples, all are). Where several recurrences in a loop share a load, only the
 * one with the most loads is reported. Findings come hottest first.
 */
public final class PointerChasingRule {

    /** The default share of the method's samples a loop needs before it is reported. */
    public static final double DEFAULT_MINIMUM_SHARE = 0.1;

    private final double minimumShare;

    public PointerChasingRule() {
        this(DEFAULT_MINIMUM_SHARE);
    }

    public PointerChasingRule(double minimumShare) {
        if (!(minimumShare >= 0 && minimumShare <= 1)) {
            throw new IllegalArgumentException("minimum share not in [0, 1]: " + minimumShare);
        }
        this.minimumShare = minimumShare;
    }

    /** Every pointer chase in {@code instructions}, ignoring samples. */
    public List<PointerChase> apply(List<Instruction> instructions, RegisterModel registers) {
        return apply(instructions, registers, instruction -> 0);
    }

    /** The pointer chases in hot loops of {@code instructions}, which must be in address order. */
    public List<PointerChase> apply(
            List<Instruction> instructions, RegisterModel registers, ToLongFunction<Instruction> sampleCount) {
        ControlFlowGraph controlFlow = ControlFlowGraph.of(instructions);
        DependencyGraph graph = DependencyGraph.of(controlFlow, registers);
        List<Instruction> code = controlFlow.instructions();
        long total = 0;
        for (Instruction instruction : code) {
            total += sampleCount.applyAsLong(instruction);
        }

        // Recurrences through loads, the most loads first, so that one covering another's
        // loads is the one kept.
        List<DependencyGraph.Recurrence> chases = new ArrayList<>();
        for (DependencyGraph.Recurrence recurrence : graph.recurrences()) {
            if (loads(graph, recurrence.cycle()) > 0) {
                chases.add(recurrence);
            }
        }
        chases.sort(Comparator.comparingInt((DependencyGraph.Recurrence r) -> loads(graph, r.cycle())).reversed()
                .thenComparing(Comparator.comparingInt(DependencyGraph.Recurrence::length).reversed()));

        List<PointerChase> findings = new ArrayList<>();
        Set<Integer> reported = new HashSet<>();
        Set<Set<Integer>> seen = new HashSet<>();
        for (DependencyGraph.Recurrence recurrence : chases) {
            if (!seen.add(new HashSet<>(recurrence.cycle()))) {
                continue;
            }
            List<Integer> cycleLoads = new ArrayList<>();
            for (int i : recurrence.cycle()) {
                if (graph.instruction(i).kind() == InstructionKind.LOAD) {
                    cycleLoads.add(i);
                }
            }
            if (cycleLoads.stream().anyMatch(reported::contains)) {
                continue;
            }
            ControlFlowGraph.Loop loop = controlFlow.loops().get(recurrence.loop());
            long samples = 0;
            BitSet blocks = loop.blocks();
            for (int block = blocks.nextSetBit(0); block >= 0; block = blocks.nextSetBit(block + 1)) {
                ControlFlowGraph.BasicBlock basicBlock = controlFlow.blocks().get(block);
                for (int i = basicBlock.start(); i < basicBlock.end(); i++) {
                    samples += sampleCount.applyAsLong(code.get(i));
                }
            }
            if (total > 0 && samples < minimumShare * total) {
                continue;
            }
            reported.addAll(cycleLoads);

            BitSet dependents = dependents(graph, controlFlow, loop, recurrence.cycle());
            Set<Integer> onCycle = new HashSet<>(recurrence.cycle());
            List<Integer> dereferences = new ArrayList<>();
            long chaseSamples = 0;
            for (int i = dependents.nextSetBit(0); i >= 0; i = dependents.nextSetBit(i + 1)) {
                chaseSamples += sampleCount.applyAsLong(code.get(i));
                if (!onCycle.contains(i) && code.get(i).kind() == InstructionKind.LOAD) {
                    dereferences.add(i);
                }
            }
            long header = code.get(controlFlow.blocks().get(loop.header()).start()).address();
            findings.add(new PointerChase(header, recurrence.register(), instructions(graph, recurrence.cycle()),
                    instructions(graph, cycleLoads), instructions(graph, dereferences), samples, chaseSamples));
        }
        findings.sort(Comparator.comparingLong(PointerChase::samples).reversed()
                .thenComparingLong(PointerChase::loopHeader));
        return findings;
    }

    // The cycle and every instruction in `loop` that depends on it: following uses forward,
    // within the iteration, from the cycle's instructions and from the instructions using
    // the pointer the cycle carried in.
    private static BitSet dependents(
            DependencyGraph graph, ControlFlowGraph controlFlow, ControlFlowGraph.Loop loop, List<Integer> cycle) {
        BitSet reached = new BitSet();
        cycle.forEach(reached::set);
        Deque<Integer> work = new ArrayDeque<>(cycle);
        Set<Integer> onCycle = new HashSet<>(cycle);
        for (int i = 0; i < graph.size(); i++) {
            if (!reached.get(i) && loop.contains(controlFlow.blockOf(i))) {
                for (int writer : graph.loopCarriedDependencies(i)) {
                    if (onCycle.contains(writer)) {
                        reached.set(i);
                        work.push(i);
                        break;
                    }
                }
            }
        }
        // Uses: the instructions in the loop each instruction's result reaches.
        List<List<Integer>> uses = new ArrayList<>();
        for (int i = 0; i < graph.size(); i++) {
            uses.add(new ArrayList<>());
        }
        for (int i = 0; i < graph.size(); i++) {
            if (loop.contains(controlFlow.blockOf(i))) {
                for (int writer : graph.dependencies(i)) {
                    uses.get(writer).add(i);
                }
            }
        }
        while (!work.isEmpty()) {
            for (int user : uses.get(work.pop())) {
                if (!reached.get(user)) {
                    reached.set(user);
                    work.push(user);
                }
            }
        }
        return reached;
    }

    private static int loads(DependencyGraph graph, List<Integer> cycle) {
        int loads = 0;
        for (int i : cycle) {
            if (graph.instruction(i).kind() == InstructionKind.LOAD) {
                loads++;
            }
        }
        return loads;
    }

    private static List<Instruction> instructions(DependencyGraph graph, List<Integer> indexes) {
        List<Instruction> instructions = new ArrayList<>(indexes.size());
        for (int i : indexes) {
            instructions.add(graph.instruction(i));
        }
        return instructions;
    }
}
