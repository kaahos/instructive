package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PointerChasingRuleTest {
    private static final long BASE = Listing.BASE;

    // i = next[i], n times: each load's result is the next load's index.
    private static final String[] INDEX_CHASE = {
        "01 d8 61 b8", // ldr w1, [x0, w1, sxtw #2]
        "42 04 00 71", // subs w2, w2, #0x1
        "c1 ff ff 54", // b.ne (to the loop start)
        "c0 03 5f d6", // ret
    };

    private final Aarch64RegisterModel registers = new Aarch64RegisterModel();

    @Test
    void findsTheChaseThroughALinkedList() {
        // Unrolled twice: each iteration loads node.next twice, the second from the first.
        Listing method = Listing.load("linked-list-sum.tsv");
        List<PointerChase> chases = new PointerChasingRule().apply(method.code(), registers, method::count);
        assertEquals(1, chases.size(), () -> describe(chases));
        PointerChase chase = chases.get(0);
        assertEquals(BASE + 0x104, chase.loopHeader());
        assertEquals("x16", chase.carriedRegister());
        assertEquals(List.of("ldr w17, [x16, #0x10]", "ldr w16, [x17, #0x10]"), texts(chase.cycle()));
        assertEquals(chase.cycle(), chase.loads());
        // Each node's item, the item's class (checked before unboxing) and its value.
        assertEquals(List.of(
                "ldr w14, [x16, #0xc]", "ldr w3, [x14, #0x8]", "ldr w14, [x14, #0xc]",
                "ldr w2, [x17, #0xc]", "ldr w3, [x2, #0x8]", "ldr w2, [x14, #0xc]"), texts(chase.dereferences()));
        // The class check of the first item has more than half the samples.
        assertEquals(56, method.count(method.at(0x10c)));
        assertEquals(88, chase.chaseSamples());
        assertEquals(98, chase.samples());
    }

    @Test
    void followsDecodedCompressedReferences() {
        // With a heap needing shifted compressed oops, each reference is decoded before
        // it is used as an address: the cycle includes the lsl.
        Listing method = Listing.load("linked-list-sum-shifted.tsv");
        List<PointerChase> chases = new PointerChasingRule().apply(method.code(), registers, method::count);
        assertEquals(1, chases.size(), () -> describe(chases));
        PointerChase chase = chases.get(0);
        assertEquals(List.of("lsl x17, x1, #3", "ldr w12, [x17, #0x10]", "lsl x5, x12, #3", "ldr w1, [x5, #0x10]"),
                texts(chase.cycle()));
        assertEquals(List.of("ldr w12, [x17, #0x10]", "ldr w1, [x5, #0x10]"), texts(chase.loads()));
        assertEquals(6, chase.dereferences().size());
    }

    @Test
    void leavesAnArrayWalkAlone() {
        // ArrayList's elements are loaded from addresses computed from an index, not
        // from each other.
        Listing method = Listing.load("array-list-sum.tsv");
        assertTrue(new PointerChasingRule().apply(method.code(), registers, method::count).isEmpty());
        assertTrue(new PointerChasingRule().apply(method.code(), registers).isEmpty());
    }

    @Test
    void leavesLoopsWithoutLoadsOnTheirRecurrencesAlone() {
        assertTrue(new PointerChasingRule().apply(Listing.decode(LongDependencyChainRuleTest.HASH_LOOP), registers).isEmpty());
        assertTrue(new PointerChasingRule().apply(Listing.decode(LongDependencyChainRuleTest.DELTA_LOOP), registers).isEmpty());
        assertTrue(new PointerChasingRule().apply(Listing.load("add.tsv").code(), registers).isEmpty());
    }

    @Test
    void findsAChaseThroughAnIndex() {
        List<PointerChase> chases = new PointerChasingRule().apply(Listing.decode(INDEX_CHASE), registers);
        assertEquals(1, chases.size());
        assertEquals("x1", chases.get(0).carriedRegister());
        assertEquals(List.of("ldr w1, [x0, w1, sxtw #2]"), texts(chases.get(0).cycle()));
    }

    @Test
    void leavesColdLoopsAlone() {
        // The linked list's post-loop, one node per iteration, chases too, but is cold;
        // without samples it is reported as well.
        Listing method = Listing.load("linked-list-sum.tsv");
        List<PointerChase> all = new PointerChasingRule().apply(method.code(), registers);
        assertEquals(2, all.size(), () -> describe(all));
        assertEquals(1, new PointerChasingRule().apply(method.code(), registers, method::count).size());
    }

    private static List<String> texts(List<Instruction> instructions) {
        return instructions.stream().map(Instruction::text).toList();
    }

    private static String describe(List<PointerChase> chases) {
        return chases.stream()
                .map(chase -> String.format("+0x%x via %s: %s; dereferences %s", chase.loopHeader() - BASE,
                        chase.carriedRegister(), texts(chase.cycle()), texts(chase.dereferences())))
                .collect(Collectors.joining("\n"));
    }
}
