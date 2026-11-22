package instructive.analyse;

import instructive.parse.Instruction;
import java.util.List;

/**
 * A virtual or interface call that dispatches through a vtable or itable stub: the call
 * site has seen too many receiver types for the compiler to inline them, or to call one
 * directly behind a class check. Every call looks the target up in the receiver's class
 * and jumps to it indirectly, and with targets that vary the jump is often mispredicted.
 *
 * <p>The agent attributes samples taken in a dispatch stub to the call that reached it,
 * so the dispatch's cost is on {@link #call()} itself: an ordinary call's instruction
 * hardly ever has samples of its own, since the time spent in the callee is the
 * callee's. Part of the cost still shows up in the callees, on their first instruction,
 * where execution resumes after the mispredicted jump.
 *
 * @param call        the call instruction
 * @param setup       the instructions loading the inline cache register just before it,
 *                    in address order
 * @param loopHeader  the address of the first instruction of the innermost loop the call
 *                    is in, or -1 if it isn't in a loop
 * @param samples     the samples on the call: the dispatch's
 * @param loopSamples the samples taken on the loop's instructions, the call's included,
 *                    or on the method's if it isn't in a loop
 */
public record MegamorphicCall(Instruction call, List<Instruction> setup, long loopHeader, long samples, long loopSamples) {

    public MegamorphicCall {
        setup = List.copyOf(setup);
    }

    /** Whether the call is in a loop. */
    public boolean inLoop() {
        return loopHeader >= 0;
    }

    /** {@link #samples()} as a share of {@link #loopSamples()}, or 0 without samples. */
    public double share() {
        return loopSamples == 0 ? 0 : (double) samples / loopSamples;
    }
}
