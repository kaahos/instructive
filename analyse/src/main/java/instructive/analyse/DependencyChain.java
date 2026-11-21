package instructive.analyse;

import instructive.parse.Instruction;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A chain of data-dependent instructions.
 *
 * @param kind            a straight-line chain, or a recurrence carried from one loop
 *                        iteration to the next
 * @param instructions    the chain, in address order. For a recurrence, everything the
 *                        carried value's producer depends on within one iteration: walking
 *                        back from it through the writers of every register each
 *                        instruction reads, loads and address arithmetic included, plus
 *                        the stores writing out a value the chain produced (by their stored
 *                        register, not their address). For a
 *                        straight-line chain, the same as {@code criticalPath}.
 * @param criticalPath    the longest run of instructions in the chain that each need the
 *                        one before, first to last. For a recurrence, the first uses the
 *                        value the last produced on the previous iteration, so every
 *                        iteration takes at least this many instructions' latency.
 * @param samples         the samples taken on the chain's instructions
 * @param spanSamples     the samples taken on the code the chain runs through, chain or
 *                        not: for a recurrence, the whole loop; for a straight-line chain,
 *                        every instruction from its first to its last
 * @param carriedRegister for a recurrence, the register carrying the value between
 *                        iterations
 * @param loopHeader      for a recurrence, the address of its loop's first instruction
 */
public record DependencyChain(
        Kind kind, List<Instruction> instructions, List<Instruction> criticalPath, long samples, long spanSamples,
        Optional<String> carriedRegister, OptionalLong loopHeader) {

    public enum Kind {
        /** Dependent instructions within one pass through the code. */
        STRAIGHT_LINE,
        /**
         * A cycle through a loop: every iteration must run all of it in sequence, so it
         * bounds how fast the loop can go however much it is unrolled.
         */
        LOOP_CARRIED,
    }

    public DependencyChain {
        instructions = List.copyOf(instructions);
        criticalPath = List.copyOf(criticalPath);
    }

    /** The number of instructions in the chain. */
    public int length() {
        return instructions.size();
    }

    /** The number of instructions on the critical path. */
    public int criticalPathLength() {
        return criticalPath.size();
    }
}
