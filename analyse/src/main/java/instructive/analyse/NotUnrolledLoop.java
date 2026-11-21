package instructive.analyse;

import instructive.parse.Instruction;
import java.util.List;

/**
 * A hot loop that processes one element per iteration: it hasn't been unrolled.
 *
 * @param header            the address of the loop's first instruction, where every
 *                          iteration starts
 * @param instructions      the loop's instructions, in address order
 * @param samples           the samples taken on them
 * @param inductionVariable the register stepping through the elements, by canonical
 *                          name, e.g. {@code x13}
 * @param step              how much it changes by per iteration: one element as an
 *                          index, or one element's size in bytes as a pointer
 * @param stepInstructions  the instructions carrying it from one iteration to the next:
 *                          the add or subtract of {@code step}, and any moves
 * @param wide              whether it is stepped as a 64-bit register. A {@code long}
 *                          loop variable can keep C2 from treating a loop as counted.
 * @param calls             the calls the loop makes: a call that isn't inlined, in the
 *                          loop's condition or its body, keeps C2 from unrolling it
 */
public record NotUnrolledLoop(
        long header, List<Instruction> instructions, long samples, String inductionVariable, long step,
        List<Instruction> stepInstructions, boolean wide, List<Instruction> calls) {

    public NotUnrolledLoop {
        instructions = List.copyOf(instructions);
        stepInstructions = List.copyOf(stepInstructions);
        calls = List.copyOf(calls);
    }

    /** The number of instructions in the loop. */
    public int length() {
        return instructions.size();
    }
}
