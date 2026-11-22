package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.RegisterModel;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Flags megamorphic calls: virtual or interface calls that dispatch through a vtable or
 * itable stub, where they cost a lookup in the receiver's class and an indirect jump
 * that is often mispredicted.
 *
 * <p>The compiler makes a virtual call it can't bind or inline through an inline cache:
 * it loads the cache's data into a register ({@link #INLINE_CACHE_REGISTER}, with
 * {@code mov}/{@code movk} just before the call) and calls a stub. Once the call site has
 * seen too many receiver types, that is a dispatch stub. Calls with one or two receiver
 * types are inlined, or bound directly behind a class check, and have no such setup.
 *
 * <p>An inline cache call isn't megamorphic by itself: one the profile didn't see before
 * compiling goes straight to a single callee, which checks the receiver's class at its
 * entry. What tells them apart is where the samples land. The agent attributes samples
 * in dispatch stubs to the call instruction that reached them, while a call that reaches
 * its target directly hardly ever has samples of its own. So a call is reported when it
 * has at least {@code minimumShare} of the method's samples (without samples, every
 * inline cache call is). Findings come hottest first.
 *
 * <p>The inline cache register is HotSpot's on AArch64 ({@code rscratch2}), and the
 * matching is on AArch64 code.
 */
public final class MegamorphicCallRule {

    /** The default share of the method's samples a call needs before it is reported. */
    public static final double DEFAULT_MINIMUM_SHARE = 0.1;

    /** The register HotSpot loads an inline cache's data into on AArch64. */
    public static final String INLINE_CACHE_REGISTER = "x9";

    private static final Set<String> MOVES = Set.of("mov", "movz", "movk", "movn");

    private final double minimumShare;

    public MegamorphicCallRule() {
        this(DEFAULT_MINIMUM_SHARE);
    }

    public MegamorphicCallRule(double minimumShare) {
        if (!(minimumShare >= 0 && minimumShare <= 1)) {
            throw new IllegalArgumentException("minimum share not in [0, 1]: " + minimumShare);
        }
        this.minimumShare = minimumShare;
    }

    /** Every inline cache call in {@code instructions}, ignoring samples. */
    public List<MegamorphicCall> apply(List<Instruction> instructions, RegisterModel registers) {
        return apply(instructions, registers, instruction -> 0);
    }

    /** The megamorphic calls in {@code instructions}, which must be in address order. */
    public List<MegamorphicCall> apply(
            List<Instruction> instructions, RegisterModel registers, ToLongFunction<Instruction> sampleCount) {
        ControlFlowGraph controlFlow = ControlFlowGraph.of(instructions);
        List<Instruction> code = controlFlow.instructions();
        long total = 0;
        for (Instruction instruction : code) {
            total += sampleCount.applyAsLong(instruction);
        }
        List<MegamorphicCall> findings = new ArrayList<>();
        for (ControlFlowGraph.BasicBlock block : controlFlow.blocks()) {
            for (int i = block.start(); i < block.end(); i++) {
                Instruction call = code.get(i);
                if (call.kind() != InstructionKind.CALL) {
                    continue;
                }
                // The inline cache's data, loaded just before the call in its block.
                int first = i;
                while (first > block.start() && loadsInlineCache(code.get(first - 1), registers)) {
                    first--;
                }
                if (first == i) {
                    continue;
                }
                long samples = sampleCount.applyAsLong(call);
                if (total > 0 && samples < minimumShare * total) {
                    continue;
                }
                int loop = controlFlow.innermostLoop(block.index());
                long loopHeader = -1;
                long loopSamples = total;
                if (loop >= 0) {
                    ControlFlowGraph.Loop innermost = controlFlow.loops().get(loop);
                    loopHeader = code.get(controlFlow.blocks().get(innermost.header()).start()).address();
                    loopSamples = 0;
                    BitSet blocks = innermost.blocks();
                    for (int b = blocks.nextSetBit(0); b >= 0; b = blocks.nextSetBit(b + 1)) {
                        ControlFlowGraph.BasicBlock inLoop = controlFlow.blocks().get(b);
                        for (int j = inLoop.start(); j < inLoop.end(); j++) {
                            loopSamples += sampleCount.applyAsLong(code.get(j));
                        }
                    }
                }
                findings.add(new MegamorphicCall(call, code.subList(first, i), loopHeader, samples, loopSamples));
            }
        }
        findings.sort(Comparator.comparingLong(MegamorphicCall::samples).reversed()
                .thenComparingLong(finding -> finding.call().address()));
        return findings;
    }

    // A move of an immediate into the inline cache register, and nothing else.
    private static boolean loadsInlineCache(Instruction instruction, RegisterModel registers) {
        if (!MOVES.contains(instruction.mnemonic())) {
            return false;
        }
        var usage = registers.usage(instruction);
        return usage.known() && usage.writes().equals(Set.of(INLINE_CACHE_REGISTER))
                && usage.reads().stream().allMatch(INLINE_CACHE_REGISTER::equals);
    }
}
