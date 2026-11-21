package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.RegisterModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Flags long chains of data-dependent instructions. A long chain serialises execution:
 * however wide the CPU, each instruction must wait for the one before it, so a hot loop
 * dominated by one runs at the chain's latency rather than its throughput. Hashing
 * ({@code h = 31 * h + x}) is the classic example.
 *
 * <p>Builds a {@link DependencyGraph} and reports each maximal chain (one no later
 * instruction extends) of at least {@code minimumLength} instructions. Chains come
 * hottest first, by the samples in the code they span ({@link DependencyChain#spanSamples()}),
 * then by length.
 */
public final class LongDependencyChainRule {

    /** The default minimum length of a chain to flag. */
    public static final int DEFAULT_MINIMUM_LENGTH = 16;

    private final int minimumLength;

    public LongDependencyChainRule() {
        this(DEFAULT_MINIMUM_LENGTH);
    }

    public LongDependencyChainRule(int minimumLength) {
        if (minimumLength < 2) {
            throw new IllegalArgumentException("a chain needs at least two instructions: " + minimumLength);
        }
        this.minimumLength = minimumLength;
    }

    /**
     * The long dependency chains in {@code instructions}, which must be in address order,
     * with {@code samples} giving the samples taken on each instruction.
     */
    public List<DependencyChain> apply(
            List<Instruction> instructions, RegisterModel registers, ToLongFunction<Instruction> samples) {
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        // Samples before each instruction, for the samples in a chain's span.
        long[] before = new long[graph.size() + 1];
        for (int i = 0; i < graph.size(); i++) {
            before[i + 1] = before[i] + samples.applyAsLong(graph.instruction(i));
        }
        List<DependencyChain> chains = new ArrayList<>();
        for (int i = 0; i < graph.size(); i++) {
            if (graph.chainLength(i) < minimumLength || graph.isExtended(i)) {
                continue;
            }
            List<Integer> indexes = graph.longestChainEndingAt(i);
            List<Instruction> chain = new ArrayList<>(indexes.size());
            long chainSamples = 0;
            for (int index : indexes) {
                chain.add(graph.instruction(index));
                chainSamples += before[index + 1] - before[index];
            }
            long spanSamples = before[i + 1] - before[indexes.get(0)];
            chains.add(new DependencyChain(chain, chainSamples, spanSamples));
        }
        chains.sort(Comparator.comparingLong(DependencyChain::spanSamples).reversed()
                .thenComparing(Comparator.comparingInt(DependencyChain::length).reversed()));
        return chains;
    }

    /** {@link #apply(List, RegisterModel, ToLongFunction)} without samples. */
    public List<DependencyChain> apply(List<Instruction> instructions, RegisterModel registers) {
        return apply(instructions, registers, instruction -> 0);
    }
}
