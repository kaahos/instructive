package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.RegisterModel;
import instructive.parse.RegisterUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data dependencies between a method's instructions, following its control flow.
 *
 * <p>An instruction depends on every instruction whose write to a register it reads can
 * reach it along some path through the {@link ControlFlowGraph}: its reaching
 * definitions. Following dependencies back gives chains of instructions that must
 * complete one after another.
 *
 * <p>Dependencies come in two kinds. Reaching definitions are computed twice, once on
 * the whole graph and once without its retreating edges (loop back-edges). A write that
 * reaches an instruction only around a back-edge is <em>loop-carried</em>: the
 * instruction uses a value computed on a previous iteration. The rest are within one
 * pass through the code.
 *
 * <ul>
 *   <li><b>Straight-line chains</b> ({@link #chainLength(int)}) follow within-iteration
 *       dependencies between instructions in the same innermost loop (or both outside any
 *       loop), so a chain describes one iteration and doesn't run in from code before the
 *       loop or out into code after it.</li>
 *   <li><b>Recurrences</b> ({@link #recurrences()}) are cycles closed by a loop-carried
 *       dependency: the longest within-iteration path from the instruction using the
 *       carried value back to the instruction producing it, inside their loop. A
 *       recurrence's length is how many dependent instructions every iteration must run
 *       in sequence, however many iterations the loop is unrolled into.</li>
 * </ul>
 *
 * <p>An instruction whose register usage is unknown (one that couldn't be decoded) may
 * have written any register, so no definition reaches past it.
 */
public final class DependencyGraph {

    /**
     * A cycle of dependent instructions closed by a loop-carried dependency.
     *
     * @param loop     the index of the loop in {@link ControlFlowGraph#loops()}
     * @param register the register carrying the value from one iteration to the next
     * @param cycle    instruction indexes, from the one using the carried value to the one
     *                 producing it
     */
    public record Recurrence(int loop, String register, List<Integer> cycle) {
        public Recurrence {
            cycle = List.copyOf(cycle);
        }

        public int length() {
            return cycle.size();
        }
    }

    private record CarriedDependency(int writer, String register) {}

    private final ControlFlowGraph controlFlow;
    private final int[][] dependencies;
    // The within-iteration writers of the registers each instruction stores to memory.
    private final int[][] storedDependencies;
    private final List<List<CarriedDependency>> carried;
    // Instruction indexes in topological order for within-iteration dependencies:
    // blocks in reverse postorder, instructions in order within each.
    private final int[] order;
    private final int[] positionOf;
    private final int[] chainLength;
    private final int[] longest;
    private final boolean[] extended;
    private final List<Recurrence> recurrences;

    private DependencyGraph(ControlFlowGraph controlFlow, RegisterModel registers) {
        this.controlFlow = controlFlow;
        List<Instruction> instructions = controlFlow.instructions();
        int size = instructions.size();
        RegisterUsage[] usage = new RegisterUsage[size];
        for (int i = 0; i < size; i++) {
            usage[i] = registers.usage(instructions.get(i));
        }

        List<List<int[]>> allReaching = reachingDefinitions(usage, false);
        List<List<int[]>> forwardReaching = reachingDefinitions(usage, true);
        this.dependencies = new int[size][];
        this.storedDependencies = new int[size][];
        this.carried = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BitSet forward = new BitSet();
            for (int[] definitions : forwardReaching.get(i)) {
                for (int definition : definitions) {
                    forward.set(definition);
                }
            }
            dependencies[i] = forward.stream().toArray();
            List<CarriedDependency> carriedHere = new ArrayList<>();
            List<String> reads = new ArrayList<>(usage[i].reads());
            Collections.sort(reads);
            List<int[]> all = allReaching.get(i);
            BitSet stored = new BitSet();
            for (int r = 0; r < reads.size(); r++) {
                if (usage[i].stored().contains(reads.get(r))) {
                    for (int definition : forwardReaching.get(i).get(r)) {
                        stored.set(definition);
                    }
                }
            }
            storedDependencies[i] = stored.stream().toArray();
            for (int r = 0; r < reads.size(); r++) {
                BitSet forwardForRegister = new BitSet();
                for (int definition : forwardReaching.get(i).get(r)) {
                    forwardForRegister.set(definition);
                }
                for (int definition : all.get(r)) {
                    if (!forwardForRegister.get(definition)) {
                        carriedHere.add(new CarriedDependency(definition, reads.get(r)));
                    }
                }
            }
            carried.add(carriedHere);
        }

        this.order = new int[size];
        this.positionOf = new int[size];
        int position = 0;
        for (int block : controlFlow.reversePostorder()) {
            ControlFlowGraph.BasicBlock basicBlock = controlFlow.blocks().get(block);
            for (int i = basicBlock.start(); i < basicBlock.end(); i++) {
                order[position] = i;
                positionOf[i] = position++;
            }
        }

        this.chainLength = new int[size];
        this.longest = new int[size];
        for (int i : order) {
            int best = -1;
            int loop = controlFlow.innermostLoop(controlFlow.blockOf(i));
            for (int dependency : dependencies[i]) {
                if (controlFlow.innermostLoop(controlFlow.blockOf(dependency)) != loop) {
                    continue;
                }
                if (best < 0 || chainLength[dependency] > chainLength[best]
                        || (chainLength[dependency] == chainLength[best] && positionOf[dependency] > positionOf[best])) {
                    best = dependency;
                }
            }
            chainLength[i] = best < 0 ? 1 : chainLength[best] + 1;
            longest[i] = best;
        }
        this.extended = new boolean[size];
        for (int predecessor : longest) {
            if (predecessor >= 0) {
                extended[predecessor] = true;
            }
        }
        this.recurrences = findRecurrences();
    }

    /** Builds the graph for {@code instructions}, which must be in address order. */
    public static DependencyGraph of(List<Instruction> instructions, RegisterModel registers) {
        return new DependencyGraph(ControlFlowGraph.of(instructions), registers);
    }

    /** Builds the graph for the instructions of {@code controlFlow}. */
    public static DependencyGraph of(ControlFlowGraph controlFlow, RegisterModel registers) {
        return new DependencyGraph(controlFlow, registers);
    }

    // For each instruction, for each register it reads (sorted by name), the definitions
    // reaching it. Without retreating edges when `forwardOnly`.
    private List<List<int[]>> reachingDefinitions(RegisterUsage[] usage, boolean forwardOnly) {
        List<ControlFlowGraph.BasicBlock> blocks = controlFlow.blocks();
        int count = blocks.size();
        List<Map<String, BitSet>> in = new ArrayList<>(count);
        List<Map<String, BitSet>> out = new ArrayList<>(count);
        for (int b = 0; b < count; b++) {
            in.add(new HashMap<>());
            out.add(new HashMap<>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int block : controlFlow.reversePostorder()) {
                Map<String, BitSet> entry = new HashMap<>();
                for (int predecessor : blocks.get(block).predecessors()) {
                    if (forwardOnly && controlFlow.isRetreating(predecessor, block)) {
                        continue;
                    }
                    merge(entry, out.get(predecessor));
                }
                in.set(block, entry);
                Map<String, BitSet> exit = transfer(copy(entry), blocks.get(block), usage);
                if (!exit.equals(out.get(block))) {
                    out.set(block, exit);
                    changed = true;
                }
            }
        }
        List<List<int[]>> reaching = new ArrayList<>(usage.length);
        for (int i = 0; i < usage.length; i++) {
            reaching.add(null);
        }
        for (ControlFlowGraph.BasicBlock block : blocks) {
            Map<String, BitSet> state = copy(in.get(block.index()));
            for (int i = block.start(); i < block.end(); i++) {
                List<String> reads = new ArrayList<>(usage[i].reads());
                Collections.sort(reads);
                List<int[]> definitions = new ArrayList<>(reads.size());
                for (String register : reads) {
                    BitSet writers = state.get(register);
                    definitions.add(writers == null ? new int[0] : writers.stream().toArray());
                }
                reaching.set(i, definitions);
                apply(state, i, usage[i]);
            }
        }
        return reaching;
    }

    private static Map<String, BitSet> transfer(Map<String, BitSet> state, ControlFlowGraph.BasicBlock block, RegisterUsage[] usage) {
        for (int i = block.start(); i < block.end(); i++) {
            apply(state, i, usage[i]);
        }
        return state;
    }

    private static void apply(Map<String, BitSet> state, int instruction, RegisterUsage usage) {
        if (!usage.known()) {
            state.clear();
            return;
        }
        for (String register : usage.writes()) {
            BitSet only = new BitSet();
            only.set(instruction);
            state.put(register, only);
        }
    }

    private static void merge(Map<String, BitSet> into, Map<String, BitSet> from) {
        for (Map.Entry<String, BitSet> entry : from.entrySet()) {
            into.computeIfAbsent(entry.getKey(), register -> new BitSet()).or(entry.getValue());
        }
    }

    private static Map<String, BitSet> copy(Map<String, BitSet> state) {
        Map<String, BitSet> copy = new HashMap<>();
        state.forEach((register, writers) -> copy.put(register, (BitSet) writers.clone()));
        return copy;
    }

    // The smallest loop containing both blocks, or -1.
    private int commonLoop(int a, int b) {
        int best = -1;
        List<ControlFlowGraph.Loop> loops = controlFlow.loops();
        for (int loop = 0; loop < loops.size(); loop++) {
            if (loops.get(loop).contains(a) && loops.get(loop).contains(b)
                    && (best < 0 || loops.get(loop).size() < loops.get(best).size())) {
                best = loop;
            }
        }
        return best;
    }

    private List<Recurrence> findRecurrences() {
        List<Recurrence> found = new ArrayList<>();
        int size = order.length;
        for (int user = 0; user < size; user++) {
            for (CarriedDependency dependency : carried.get(user)) {
                int loop = commonLoop(controlFlow.blockOf(user), controlFlow.blockOf(dependency.writer()));
                if (loop < 0) {
                    continue;
                }
                ControlFlowGraph.Loop body = controlFlow.loops().get(loop);
                // Longest within-iteration path from the user to the writer, inside the loop.
                int[] distance = new int[size];
                int[] parent = new int[size];
                distance[user] = 1;
                parent[user] = -1;
                for (int p = positionOf[user] + 1; p < size; p++) {
                    int i = order[p];
                    if (!body.contains(controlFlow.blockOf(i))) {
                        continue;
                    }
                    for (int previous : dependencies[i]) {
                        if (distance[previous] > 0 && distance[previous] + 1 > distance[i]) {
                            distance[i] = distance[previous] + 1;
                            parent[i] = previous;
                        }
                    }
                }
                if (distance[dependency.writer()] == 0) {
                    continue;
                }
                List<Integer> cycle = new ArrayList<>();
                for (int i = dependency.writer(); i >= 0; i = parent[i]) {
                    cycle.add(i);
                }
                Collections.reverse(cycle);
                found.add(new Recurrence(loop, dependency.register(), cycle));
            }
        }
        return List.copyOf(found);
    }

    public ControlFlowGraph controlFlow() {
        return controlFlow;
    }

    public int size() {
        return order.length;
    }

    public Instruction instruction(int index) {
        return controlFlow.instructions().get(index);
    }

    /** The writers whose values reach instruction {@code index} within one pass through the code. */
    public int[] dependencies(int index) {
        return dependencies[index].clone();
    }

    /** The writers whose values reach instruction {@code index} only from a previous loop iteration. */
    public int[] loopCarriedDependencies(int index) {
        return carried.get(index).stream().mapToInt(CarriedDependency::writer).distinct().sorted().toArray();
    }

    /** The length, in instructions, of the longest straight-line chain ending at {@code index}. */
    public int chainLength(int index) {
        return chainLength[index];
    }

    /** Whether some later instruction's longest straight-line chain runs through {@code index}. */
    boolean isExtended(int index) {
        return extended[index];
    }

    /** The indexes on the longest straight-line chain ending at {@code index}, first to last. */
    public List<Integer> longestChainEndingAt(int index) {
        List<Integer> chain = new ArrayList<>(chainLength[index]);
        for (int i = index; i >= 0; i = longest[i]) {
            chain.add(i);
        }
        Collections.reverse(chain);
        return chain;
    }

    /** Every loop-carried cycle, one per loop-carried dependency that closes one. */
    public List<Recurrence> recurrences() {
        return recurrences;
    }

    /**
     * Everything instruction {@code index}'s result depends on within one iteration of
     * {@code loop}: starting from {@code index}, follow the writers of every register each
     * instruction reads, through loads and address arithmetic as well as the arithmetic,
     * without leaving the loop or following loop-carried dependencies. In address order,
     * {@code index} included.
     */
    public List<Integer> dependenciesWithin(int index, int loop) {
        ControlFlowGraph.Loop body = controlFlow.loops().get(loop);
        BitSet seen = new BitSet();
        ArrayDeque<Integer> work = new ArrayDeque<>();
        seen.set(index);
        work.push(index);
        while (!work.isEmpty()) {
            for (int writer : dependencies[work.pop()]) {
                if (!seen.get(writer) && body.contains(controlFlow.blockOf(writer))) {
                    seen.set(writer);
                    work.push(writer);
                }
            }
        }
        return seen.stream().boxed().toList();
    }

    /**
     * The stores in {@code loop} that write out a value produced by one of {@code chain}'s
     * instructions in the same iteration: those whose stored register, not just an
     * address register, was written by the chain. In address order.
     */
    public List<Integer> storesOf(Collection<Integer> chain, int loop) {
        ControlFlowGraph.Loop body = controlFlow.loops().get(loop);
        BitSet members = new BitSet();
        chain.forEach(members::set);
        List<Integer> stores = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            if (members.get(i) || !body.contains(controlFlow.blockOf(i))) {
                continue;
            }
            for (int writer : storedDependencies[i]) {
                if (members.get(writer)) {
                    stores.add(i);
                    break;
                }
            }
        }
        return stores;
    }
}
