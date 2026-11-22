package instructive.analyse;

import instructive.parse.Instruction;
import java.util.List;

/**
 * A hot loop whose branches depend on the data it loads, and whose samples land where a
 * mispredicted branch's would: on the first instructions of the paths those branches
 * choose between. After a misprediction the CPU throws away what it fetched down the
 * wrong path and starts again at the right one, so samples taken while it catches up
 * fall on the start of the path it should have taken.
 *
 * @param loopHeader      the address of the loop's first instruction
 * @param branches        the loop's branches whose condition depends on a value loaded
 *                        in the same iteration, and that choose between paths in the
 *                        loop, in address order
 * @param samples         the samples taken on the loop's instructions
 * @param landingSamples  the samples taken on the first instructions of the paths those
 *                        branches lead to, other than the next of the branches, each
 *                        instruction counted once
 */
public record UnpredictableBranches(long loopHeader, List<Branch> branches, long samples, long landingSamples) {

    /**
     * A branch whose direction depends on loaded data.
     *
     * @param branch         the conditional branch
     * @param condition      where its condition comes from: the instruction setting the
     *                       flags it tests, or the branch itself for one testing a
     *                       register ({@code cbz}, {@code tbnz}...)
     * @param load           a load in the loop whose value the condition depends on
     * @param landingSamples the samples on the first instructions of the paths it leads
     *                       to that no earlier branch in the list already counted
     */
    public record Branch(Instruction branch, Instruction condition, Instruction load, long landingSamples) {}

    public UnpredictableBranches {
        branches = List.copyOf(branches);
    }

    /** {@link #landingSamples()} as a share of {@link #samples()}, or 0 without samples. */
    public double landingShare() {
        return samples == 0 ? 0 : (double) landingSamples / samples;
    }
}
