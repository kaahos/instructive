package instructive.analyse;

import instructive.parse.Instruction;
import java.util.List;

/**
 * A chain of data-dependent instructions: each one uses a register the one before it
 * wrote, so they can only complete one after another.
 *
 * @param instructions the chain, first to last
 * @param samples      the samples taken on the chain's instructions
 * @param spanSamples  the samples taken on every instruction from the chain's first to its
 *                     last, chain or not. Samples tend to land on instructions near a stall
 *                     (skid) rather than on the instructions causing it, so this is the
 *                     better measure of how hot the code the chain runs through is.
 */
public record DependencyChain(List<Instruction> instructions, long samples, long spanSamples) {

    public DependencyChain {
        instructions = List.copyOf(instructions);
    }

    /** The number of instructions in the chain. */
    public int length() {
        return instructions.size();
    }

    public Instruction first() {
        return instructions.get(0);
    }

    public Instruction last() {
        return instructions.get(instructions.size() - 1);
    }
}
