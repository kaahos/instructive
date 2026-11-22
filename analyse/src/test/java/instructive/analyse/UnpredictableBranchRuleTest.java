package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class UnpredictableBranchRuleTest {
    private static final long BASE = Listing.BASE;

    private final Aarch64RegisterModel registers = new Aarch64RegisterModel();

    @Test
    void flagsASwitchOverRandomData() {
        // switch (x & 3) over random values, after a hash of x: C2's decision tree
        // mispredicts, and the samples land on the cases' first instructions.
        Listing method = Listing.load("switch-random.tsv");
        List<UnpredictableBranches> loops = new UnpredictableBranchRule().apply(method.code(), registers, method::count);
        assertEquals(1, loops.size(), () -> describe(loops));
        UnpredictableBranches loop = loops.get(0);
        assertEquals(BASE + 0x84, loop.loopHeader());
        assertEquals(93, loop.samples());
        // On the cases' first instructions; not on the tree's own compares and branches.
        assertEquals(47, loop.landingSamples());
        // The decision tree: every branch's condition comes from x & 3, so from data[i].
        assertEquals(List.of("b.ne", "b.ge", "cbnz", "b.eq"),
                loop.branches().stream().map(b -> b.branch().mnemonic()).toList());
        assertTrue(loop.branches().stream().allMatch(b -> b.load().text().equals("ldr w3, [x17, #0x10]")), () -> describe(loops));
        assertEquals("cmp w5, #0x1", loop.branches().get(0).condition().text());
        assertEquals(loop.landingSamples(), loop.branches().stream().mapToLong(UnpredictableBranches.Branch::landingSamples).sum());
    }

    @Test
    void leavesTheSameSwitchOverSortedDataAlone() {
        // The same values grouped by case: the branches predict well, and the samples are
        // on the hash instead (6% landing on the cases, under the 25% threshold).
        Listing method = Listing.load("switch-sorted.tsv");
        assertTrue(new UnpredictableBranchRule().apply(method.code(), registers, method::count).isEmpty());
        List<UnpredictableBranches> anyShare = new UnpredictableBranchRule(0.1, 0).apply(method.code(), registers, method::count);
        assertEquals(1, anyShare.size());
        assertEquals(6, anyShare.get(0).landingSamples());
        assertEquals(95, anyShare.get(0).samples());
        // Without samples, the structure alone: the unrolled loop and the loop after it.
        assertEquals(2, new UnpredictableBranchRule().apply(method.code(), registers).size());
    }

    @Test
    void flagsAnIfElseChainOverRandomData() {
        // if (x < 1 << 18) ... else if (x < 2 << 18) ... else if (x < 3 << 18) ... else ...,
        // unrolled twice: three compares and branches per element, on data[i] and data[i + 1].
        Listing method = Listing.load("if-else-random.tsv");
        List<UnpredictableBranches> loops = new UnpredictableBranchRule().apply(method.code(), registers, method::count);
        assertEquals(1, loops.size(), () -> describe(loops));
        UnpredictableBranches loop = loops.get(0);
        assertEquals(83, loop.samples());
        assertEquals(42, loop.landingSamples());
        assertEquals(6, loop.branches().size(), () -> describe(loops));
        assertEquals(List.of("cmp w12, #0x40, lsl #12", "cmp w12, #0x80, lsl #12", "cmp w12, #0xc0, lsl #12"),
                loop.branches().subList(0, 3).stream().map(b -> b.condition().text()).toList());
        assertEquals(List.of("ldr w12, [x15, #0x10]", "ldr w15, [x15, #0x14]"),
                loop.branches().stream().map(b -> b.load().text()).distinct().toList());
    }

    @Test
    void leavesTheSameChainOverSortedDataAlone() {
        Listing method = Listing.load("if-else-sorted.tsv");
        assertTrue(new UnpredictableBranchRule().apply(method.code(), registers, method::count).isEmpty());
        UnpredictableBranches loop = new UnpredictableBranchRule(0.1, 0).apply(method.code(), registers, method::count).get(0);
        assertEquals(3, loop.landingSamples());
        assertEquals(90, loop.samples());
    }

    @Test
    void doesntCountTheChainsOwnCompares() {
        // The sorted chain on JDK 17, where the hash's samples skid onto the chain's
        // compares, which are its branches' targets: 40% of the samples, but 14% on the
        // cases' code.
        Listing method = Listing.load("if-else-sorted-jdk17.tsv");
        assertTrue(new UnpredictableBranchRule().apply(method.code(), registers, method::count).isEmpty());
        UnpredictableBranches loop = new UnpredictableBranchRule(0.1, 0).apply(method.code(), registers, method::count).get(0);
        assertEquals(14, loop.landingSamples());
        assertEquals(100, loop.samples());
    }

    @Test
    void flagsTheSwitchOnJdk17() {
        // The lowest share measured over random data: 36 of 98.
        Listing method = Listing.load("switch-random-jdk17.tsv");
        List<UnpredictableBranches> loops = new UnpredictableBranchRule().apply(method.code(), registers, method::count);
        assertEquals(1, loops.size(), () -> describe(loops));
        assertEquals(36, loops.get(0).landingSamples());
        assertEquals(98, loops.get(0).samples());
    }

    @Test
    void flagsATwoWayIfElse() {
        // if (x < 1 << 19) a += x; else a -= x: C2 keeps the branch, 50/50 on random data.
        Listing method = Listing.load("if-else-two-way.tsv");
        List<UnpredictableBranches> loops = new UnpredictableBranchRule().apply(method.code(), registers, method::count);
        assertEquals(1, loops.size(), () -> describe(loops));
        assertEquals(48, loops.get(0).landingSamples());
        assertEquals(88, loops.get(0).samples());
        assertEquals(List.of("b.lt", "b.lt"), loops.get(0).branches().stream().map(b -> b.branch().mnemonic()).toList());
    }

    @Test
    void leavesAConditionalSelectAlone() {
        // a += x < 1 << 19 ? x : -x compiles to csel: nothing to mispredict.
        Listing method = Listing.load("ternary.tsv");
        assertTrue(method.code().stream().anyMatch(i -> i.mnemonic().equals("csel")));
        assertTrue(new UnpredictableBranchRule().apply(method.code(), registers).isEmpty());
    }

    @Test
    void ignoresBranchesThatLeaveTheLoopOrDontDependOnData() {
        // Range and null checks branching to traps and throws, loop exits, counters.
        for (String listing : List.of("linked-list-sum.tsv", "array-list-sum.tsv", "add.tsv", "add-sve.tsv", "sum.tsv",
                "sum-boxes-after-null.tsv", "sum-range-after-overflow.tsv", "sum-with-call-in-condition.tsv")) {
            assertTrue(new UnpredictableBranchRule().apply(Listing.load(listing).code(), registers).isEmpty(), listing);
        }
        assertTrue(new UnpredictableBranchRule().apply(Listing.decode(LongDependencyChainRuleTest.HASH_LOOP), registers).isEmpty());
    }

    private static String describe(List<UnpredictableBranches> loops) {
        return loops.stream()
                .map(loop -> String.format("+0x%x %d/%d: %s", loop.loopHeader() - BASE, loop.landingSamples(), loop.samples(),
                        loop.branches().stream().map(b -> b.branch().text() + " <- " + b.condition().text() + " <- " + b.load().text())
                                .collect(Collectors.joining("; "))))
                .collect(Collectors.joining("\n"));
    }
}
