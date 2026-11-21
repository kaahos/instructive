package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FailedCheckRuleTest {
    private static final long BASE = Listing.BASE;

    private final Aarch64RegisterModel registers = new Aarch64RegisterModel();

    @Test
    void findsTheNullCheckAfterANull() {
        // boxes[i].value, after compiled code threw a NullPointerException 5 times: each
        // element's reference is tested, in the pre-loop, the 4 copies of the unrolled loop and the post-loop,
        // and all branch to one throw.
        Listing method = Listing.load("sum-boxes-after-null.tsv");
        List<FailedCheck> checks = new FailedCheckRule().apply(method.code(), registers, method::count);
        assertEquals(1, checks.size(), () -> describe(checks));
        FailedCheck check = checks.get(0);
        assertEquals(FailedCheck.Kind.NULL_CHECK, check.kind());
        assertEquals(FailedCheck.Failure.THROW, check.failure());
        assertTrue(check.trapRequest().isEmpty());
        assertEquals(List.of("cbz w13", "cbz w15", "cbz w17", "cbz w16", "cbz w18", "cbz w11"),
                check.checks().stream().map(i -> i.mnemonic() + " " + i.operands().split(",")[0]).toList());
        assertEquals(BASE + 0x10c, check.failurePath());
        assertTrue(check.inLoop());
        assertEquals(95, check.samples());
    }

    @Test
    void findsNoNullChecksWhenNoneHaveFailed() {
        Listing method = Listing.load("sum-boxes.tsv");
        assertTrue(new FailedCheckRule().apply(method.code(), registers, method::count).isEmpty());
    }

    @Test
    void findsTheRangeCheckAfterAnOverflow() {
        // a[i] for i < n, after compiled code was called once with n past the end: the
        // main loop is still
        // unchecked (C2 clamps its limit to the length), but the code before it tests for
        // an empty array and the post-loop checks each index, throwing.
        Listing method = Listing.load("sum-range-after-overflow.tsv");
        List<FailedCheck> checks = new FailedCheckRule().apply(method.code(), registers, method::count);
        assertEquals(1, checks.size(), () -> describe(checks));
        FailedCheck check = checks.get(0);
        assertEquals(FailedCheck.Kind.RANGE_CHECK, check.kind());
        assertEquals(FailedCheck.Failure.THROW, check.failure());
        assertEquals(List.of("cbz w15", "b.hs"),
                check.checks().stream().map(i -> i.mnemonic().startsWith("cb") ? i.mnemonic() + " " + i.operands().split(",")[0] : i.mnemonic()).toList());
        assertEquals(BASE + 0x174, check.failurePath());
        // Cold: the checked code runs a few iterations per call.
        assertTrue(check.samples() <= 2);
    }

    @Test
    void findsNoRangeChecksWhenNoneHaveFailed() {
        // The loop's checks are replaced by predicates before it, which trap with a
        // predicate reason: eliminated checks.
        Listing method = Listing.load("sum-range.tsv");
        assertTrue(new FailedCheckRule().apply(method.code(), registers, method::count).isEmpty());
    }

    @Test
    void findsRangeChecksALoopKept() {
        // c[i] = a[i] + b[i] with limit(a) in the condition: not a counted loop, so each
        // array's check stays in it, trapping with reason range_check.
        Listing method = Listing.load("add-with-call-in-condition.tsv");
        List<FailedCheck> checks = new FailedCheckRule().apply(method.code(), registers, method::count);
        assertEquals(1, checks.size(), () -> describe(checks));
        FailedCheck check = checks.get(0);
        assertEquals(FailedCheck.Kind.RANGE_CHECK, check.kind());
        assertEquals(FailedCheck.Failure.TRAP, check.failure());
        assertEquals(-0x1c, check.trapRequest().getAsInt());
        assertEquals(FailedCheckRule.REASON_RANGE_CHECK, check.trapReason().getAsInt());
        assertEquals(3, check.trapAction().getAsInt()); // make_not_entrant
        assertEquals(List.of(BASE + 0x90, BASE + 0x9c, BASE + 0xac),
                check.checks().stream().map(Instruction::address).toList());
        assertEquals(43, check.samples());
    }

    @Test
    void ignoresNullChecksThatTrap() {
        // Iterating a LinkedList, each item is tested for null before its class check: a
        // cast lets null through, so the test can't be folded into a load. It traps with
        // reason null_check, C2 speculating it's never null; no null was seen.
        Listing method = Listing.load("linked-list-sum.tsv");
        assertTrue(new FailedCheckRule().apply(method.code(), registers, method::count).isEmpty());
        assertTrue(new FailedCheckRule().apply(Listing.load("array-list-sum.tsv").code(), registers).isEmpty());
    }

    @Test
    void ignoresKernelsWithoutChecks() {
        for (String listing : List.of("add.tsv", "sum.tsv", "linked-list-sum-shifted.tsv")) {
            assertTrue(new FailedCheckRule().apply(Listing.load(listing).code(), registers).isEmpty(), listing);
        }
        assertTrue(new FailedCheckRule().apply(Listing.decode(LongDependencyChainRuleTest.HASH_LOOP), registers).isEmpty());
        assertFalse(new FailedCheckRule().apply(Listing.load("sum-with-call-in-condition.tsv").code(), registers).isEmpty());
    }

    private static String describe(List<FailedCheck> checks) {
        return checks.stream()
                .map(check -> String.format("%s %s -> +0x%x: %s", check.kind(), check.failure(), check.failurePath() - BASE,
                        check.checks().stream().map(i -> String.format("+0x%x %s", i.address() - BASE, i.text())).toList()))
                .collect(Collectors.joining("\n"));
    }
}
