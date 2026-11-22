package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.RegisterModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Flags hot loops with branches that are likely mispredicted. Timer samples can't see a
 * misprediction directly, so the rule combines the code's structure with where the
 * samples land:
 * <ul>
 *   <li>a branch can be unpredictable if its condition depends on data loaded in the
 *       same iteration, such as a switch over the elements of an array, rather than on
 *       the loop counter or values that don't change. Only branches choosing between
 *       paths inside the loop count: ones that leave it, such as range checks branching
 *       to a trap or a break out of a search, go one way until the last time.</li>
 *   <li>when such branches mispredict, the samples land on the first instructions of
 *       the paths they lead to, where the CPU restarts. A loop is reported if at least
 *       {@code minimumLandingShare} of its samples land on the first
 *       {@value #LANDING_INSTRUCTIONS} instructions of those paths. Paths that are just
 *       the next compare and branch of the same decision (a switch's tree, an if/else
 *       chain) don't count: their cost is there whether the branches mispredict or not.</li>
 * </ul>
 * The share shows that branches mispredict, not that it costs much: a long dependency
 * chain elsewhere in the loop can hide the penalty. How far samples skid past the point
 * of the stall depends on the CPU.
 *
 * <p>A {@code b.cond}'s condition comes from the nearest flag-setting instruction before
 * it in its block ({@code cmp}, {@code tst}, {@code subs}...); a {@code cbz},
 * {@code cbnz}, {@code tbz} or {@code tbnz} tests a register itself. Loops with less
 * than {@code minimumShare} of the method's samples aren't considered. Without samples,
 * every loop with such branches is reported. Findings come hottest first.
 */
public final class UnpredictableBranchRule {

    /** The default share of the method's samples a loop needs before it is considered. */
    public static final double DEFAULT_MINIMUM_SHARE = 0.1;
    /**
     * The default share of a loop's samples that must land on the paths its branches lead
     * to: between the shares measured on loops whose branches mispredict (37% to 76%, for
     * switches and if/else chains over random data, on JDK 17, 21 and 25) and on the same
     * loops over sorted data (3% to 14%), all on Apple M2 cores.
     */
    public static final double DEFAULT_MINIMUM_LANDING_SHARE = 0.25;
    /** How many instructions at the start of a branch's target count as landing there. */
    public static final int LANDING_INSTRUCTIONS = 2;

    private static final Set<String> FLAG_SETTERS = Set.of(
            "cmp", "cmn", "tst", "subs", "adds", "ands", "bics", "negs", "ngcs", "sbcs", "adcs", "ccmp", "ccmn",
            "fcmp", "fcmpe", "fccmp", "fccmpe");

    private final double minimumShare;
    private final double minimumLandingShare;

    public UnpredictableBranchRule() {
        this(DEFAULT_MINIMUM_SHARE, DEFAULT_MINIMUM_LANDING_SHARE);
    }

    public UnpredictableBranchRule(double minimumShare, double minimumLandingShare) {
        if (!(minimumShare >= 0 && minimumShare <= 1) || !(minimumLandingShare >= 0 && minimumLandingShare <= 1)) {
            throw new IllegalArgumentException("shares not in [0, 1]: " + minimumShare + ", " + minimumLandingShare);
        }
        this.minimumShare = minimumShare;
        this.minimumLandingShare = minimumLandingShare;
    }

    /** Every loop in {@code instructions} with branches on loaded data, ignoring samples. */
    public List<UnpredictableBranches> apply(List<Instruction> instructions, RegisterModel registers) {
        return apply(instructions, registers, instruction -> 0);
    }

    /** The hot loops of {@code instructions}, which must be in address order, likely to mispredict. */
    public List<UnpredictableBranches> apply(
            List<Instruction> instructions, RegisterModel registers, ToLongFunction<Instruction> sampleCount) {
        ControlFlowGraph controlFlow = ControlFlowGraph.of(instructions);
        DependencyGraph graph = DependencyGraph.of(controlFlow, registers);
        List<Instruction> code = controlFlow.instructions();
        long total = 0;
        for (Instruction instruction : code) {
            total += sampleCount.applyAsLong(instruction);
        }

        List<UnpredictableBranches> findings = new ArrayList<>();
        for (int index = 0; index < controlFlow.loops().size(); index++) {
            ControlFlowGraph.Loop loop = controlFlow.loops().get(index);
            BitSet blocks = loop.blocks();
            boolean innermost = true;
            long samples = 0;
            for (int block = blocks.nextSetBit(0); block >= 0; block = blocks.nextSetBit(block + 1)) {
                innermost &= controlFlow.innermostLoop(block) == index;
                ControlFlowGraph.BasicBlock basicBlock = controlFlow.blocks().get(block);
                for (int i = basicBlock.start(); i < basicBlock.end(); i++) {
                    samples += sampleCount.applyAsLong(code.get(i));
                }
            }
            if (!innermost || (total > 0 && samples < minimumShare * total)) {
                continue;
            }
            // The branches on loaded data, and the blocks they end.
            List<int[]> candidates = new ArrayList<>();
            Set<Integer> branchBlocks = new HashSet<>();
            for (int block = blocks.nextSetBit(0); block >= 0; block = blocks.nextSetBit(block + 1)) {
                ControlFlowGraph.BasicBlock basicBlock = controlFlow.blocks().get(block);
                int last = basicBlock.end() - 1;
                if (code.get(last).kind() != InstructionKind.CONDITIONAL_BRANCH || basicBlock.successors().size() != 2
                        || !basicBlock.successors().stream().allMatch(loop::contains)) {
                    continue;
                }
                int condition = condition(code, basicBlock, last);
                int load = condition < 0 ? -1 : loadFeeding(graph, controlFlow, loop, condition);
                if (load >= 0) {
                    candidates.add(new int[] {block, last, condition, load});
                    branchBlocks.add(block);
                }
            }
            // Samples land at the start of the paths those branches choose between: the
            // blocks doing the work, not the next compare and branch of the same decision
            // tree or if/else chain, whose cost is there whether or not anything mispredicts.
            List<UnpredictableBranches.Branch> branches = new ArrayList<>();
            BitSet landing = new BitSet();
            long landingSamples = 0;
            for (int[] candidate : candidates) {
                long here = 0;
                for (int successor : controlFlow.blocks().get(candidate[0]).successors()) {
                    if (branchBlocks.contains(successor)) {
                        continue;
                    }
                    ControlFlowGraph.BasicBlock target = controlFlow.blocks().get(successor);
                    for (int i = target.start(); i < Math.min(target.end(), target.start() + LANDING_INSTRUCTIONS); i++) {
                        if (!landing.get(i)) {
                            landing.set(i);
                            here += sampleCount.applyAsLong(code.get(i));
                        }
                    }
                }
                landingSamples += here;
                branches.add(new UnpredictableBranches.Branch(
                        code.get(candidate[1]), code.get(candidate[2]), code.get(candidate[3]), here));
            }
            if (branches.isEmpty() || (total > 0 && landingSamples < minimumLandingShare * samples)) {
                continue;
            }
            branches.sort(Comparator.comparingLong(b -> b.branch().address()));
            long header = code.get(controlFlow.blocks().get(loop.header()).start()).address();
            findings.add(new UnpredictableBranches(header, branches, samples, landingSamples));
        }
        findings.sort(Comparator.comparingLong(UnpredictableBranches::samples).reversed()
                .thenComparingLong(UnpredictableBranches::loopHeader));
        return findings;
    }

    // Where the branch at `last` gets its condition: the register-testing branch itself,
    // or the nearest flag setter before it in its block; -1 if the flags come from
    // elsewhere.
    private static int condition(List<Instruction> code, ControlFlowGraph.BasicBlock block, int last) {
        String mnemonic = code.get(last).mnemonic();
        if (!mnemonic.startsWith("b.")) {
            return last;
        }
        for (int i = last - 1; i >= block.start(); i--) {
            if (FLAG_SETTERS.contains(code.get(i).mnemonic())) {
                return i;
            }
        }
        return -1;
    }

    // A load in `loop` that `condition` depends on within the iteration, or -1.
    private static int loadFeeding(DependencyGraph graph, ControlFlowGraph controlFlow, ControlFlowGraph.Loop loop, int condition) {
        Deque<Integer> work = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        work.push(condition);
        while (!work.isEmpty()) {
            int i = work.pop();
            if (!seen.add(i)) {
                continue;
            }
            if (i != condition && graph.instruction(i).kind() == InstructionKind.LOAD) {
                return i;
            }
            for (int writer : graph.dependencies(i)) {
                if (loop.contains(controlFlow.blockOf(writer))) {
                    work.push(writer);
                }
            }
        }
        return -1;
    }
}
