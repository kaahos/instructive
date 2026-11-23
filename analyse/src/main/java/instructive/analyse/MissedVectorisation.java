package instructive.analyse;

import instructive.parse.Instruction;
import java.util.List;

/**
 * A hot loop that has been unrolled but not vectorised: each iteration does the same
 * scalar operations on adjacent elements, once per unrolled copy, where vector
 * instructions could do them all at once. C2 vectorises only after proving the copies
 * independent; a common reason it doesn't is that two arrays indexed at different
 * offsets ({@code y[yOffset + i] += a * x[xOffset + i]}) might be the same array, and
 * then a store in one copy could change what a later copy loads.
 *
 * @param header       the address of the loop's first instruction, where every
 *                     iteration starts
 * @param instructions the loop's instructions, in address order
 * @param samples      the samples taken on them
 * @param copies       the isomorphic copies, in the order of the elements they store:
 *                     each copy's loads, operations and store, in address order. Copy
 *                     {@code k} stores the element after copy {@code k - 1}'s, and loads
 *                     the element after each of its loads.
 * @param operations   the first copy's operations between its loads and its store, the
 *                     ones that would become vector instructions, in address order
 * @param elementSize  the size of the stored elements in bytes
 */
public record MissedVectorisation(
        long header, List<Instruction> instructions, long samples, List<List<Instruction>> copies,
        List<Instruction> operations, int elementSize) {

    public MissedVectorisation {
        instructions = List.copyOf(instructions);
        copies = copies.stream().map(List::copyOf).toList();
        operations = List.copyOf(operations);
    }

    /** How many elements each iteration handles with scalar copies of the same operations. */
    public int lanes() {
        return copies.size();
    }
}
