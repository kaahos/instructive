package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.RegisterModel;
import instructive.parse.RegisterUsage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data dependencies between the instructions of a stream, in address order: each
 * instruction is linked to the closest preceding instruction that wrote each register it
 * reads. Following those links back gives dependency chains, and the length of the
 * longest chain ending at an instruction is how many instructions, at most, must complete
 * one after another to produce its inputs.
 *
 * <p>Links follow the stream as laid out, not control flow, so in a loop body they run
 * from one unrolled iteration to the next. An instruction whose register usage is unknown
 * (one that couldn't be decoded) may have written any register, so it breaks every chain.
 */
public final class DependencyGraph {

    private final List<Instruction> instructions;
    private final List<int[]> predecessors;
    private final int[] chainLength;
    // The predecessor on the longest chain ending at each instruction, or -1.
    private final int[] longest;
    // Whether the instruction is the predecessor on some later instruction's longest chain.
    private final boolean[] extended;

    private DependencyGraph(List<Instruction> instructions, List<int[]> predecessors, int[] chainLength, int[] longest) {
        this.instructions = instructions;
        this.predecessors = predecessors;
        this.chainLength = chainLength;
        this.longest = longest;
        this.extended = new boolean[longest.length];
        for (int predecessor : longest) {
            if (predecessor >= 0) {
                extended[predecessor] = true;
            }
        }
    }

    /** Builds the graph for {@code instructions}, in address order. */
    public static DependencyGraph of(List<Instruction> instructions, RegisterModel registers) {
        List<Instruction> stream = List.copyOf(instructions);
        int size = stream.size();
        List<int[]> predecessors = new ArrayList<>(size);
        int[] chainLength = new int[size];
        int[] longest = new int[size];
        Map<String, Integer> lastWriter = new HashMap<>();
        for (int i = 0; i < size; i++) {
            RegisterUsage usage = registers.usage(stream.get(i));
            if (!usage.known()) {
                lastWriter.clear();
                predecessors.add(new int[0]);
                chainLength[i] = 1;
                longest[i] = -1;
                continue;
            }
            int[] writers = usage.reads().stream()
                    .map(lastWriter::get)
                    .filter(writer -> writer != null)
                    .mapToInt(Integer::intValue)
                    .distinct()
                    .sorted()
                    .toArray();
            int best = -1;
            for (int writer : writers) {
                if (best < 0 || chainLength[writer] > chainLength[best]
                        || (chainLength[writer] == chainLength[best] && writer > best)) {
                    best = writer;
                }
            }
            predecessors.add(writers);
            chainLength[i] = best < 0 ? 1 : chainLength[best] + 1;
            longest[i] = best;
            for (String register : usage.writes()) {
                lastWriter.put(register, i);
            }
        }
        return new DependencyGraph(stream, predecessors, chainLength, longest);
    }

    public int size() {
        return instructions.size();
    }

    public Instruction instruction(int index) {
        return instructions.get(index);
    }

    /** The indexes of the closest preceding writers of the registers instruction {@code index} reads. */
    public int[] predecessors(int index) {
        return predecessors.get(index).clone();
    }

    /** The length, in instructions, of the longest dependency chain ending at {@code index}. */
    public int chainLength(int index) {
        return chainLength[index];
    }

    /** Whether some later instruction's longest chain runs through {@code index}. */
    boolean isExtended(int index) {
        return extended[index];
    }

    /** The indexes on the longest dependency chain ending at {@code index}, first to last. */
    public List<Integer> longestChainEndingAt(int index) {
        List<Integer> chain = new ArrayList<>(chainLength[index]);
        for (int i = index; i >= 0; i = longest[i]) {
            chain.add(i);
        }
        Collections.reverse(chain);
        return chain;
    }
}
