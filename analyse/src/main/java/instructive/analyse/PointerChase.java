package instructive.analyse;

import instructive.parse.Instruction;
import java.util.List;

/**
 * A loop that chases pointers: a load's result, carried to the next iteration, is where
 * the next iteration's load reads from, as in {@code node = node.next}. Each iteration
 * has to wait for the previous iteration's load to complete before it can even start
 * its own, so the loop runs no faster than one load latency per load on the cycle, and
 * a cache miss stalls everything behind it.
 *
 * @param loopHeader      the address of the loop's first instruction
 * @param carriedRegister the register carrying the pointer between iterations, by
 *                        canonical name
 * @param cycle           the recurrence, first to last: from the instruction using the
 *                        pointer carried in to the one producing the pointer carried out,
 *                        including any decoding of compressed references
 * @param loads           the loads on the cycle, in cycle order: one per step of the
 *                        chase
 * @param dereferences    the other loads in the loop whose address comes from the chase
 *                        within an iteration, such as a node's fields and the objects they
 *                        point to, in address order
 * @param samples         the samples taken on the loop's instructions
 * @param chaseSamples    the samples taken on the cycle and on every instruction in the
 *                        loop that depends on it within an iteration: the dereferences
 *                        and whatever uses what they load. A stalled load's samples often
 *                        land on the instruction waiting for it rather than on the load
 *                        (skid), and how far depends on the CPU, so the consumers count
 *                        too.
 */
public record PointerChase(
        long loopHeader, String carriedRegister, List<Instruction> cycle, List<Instruction> loads,
        List<Instruction> dereferences, long samples, long chaseSamples) {

    public PointerChase {
        cycle = List.copyOf(cycle);
        loads = List.copyOf(loads);
        dereferences = List.copyOf(dereferences);
    }
}
