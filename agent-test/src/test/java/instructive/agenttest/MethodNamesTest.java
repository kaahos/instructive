package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.Sampler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Method names are kept in the sampler's dictionaries, each class signature, method name
 * and method signature once, and profiles hold their codes.
 */
class MethodNamesTest {
    private static final String CLASS = "instructive.agenttest.MethodNamesTest.";

    private static final List<String> KERNELS = List.of("twice(I)I", "twice(J)J", "thrice(I)I", "half(D)D");

    // Calls the kernels until each is compiled: on a busy machine the compiler can take a
    // while to get to them. They're kept out of line (dontinline in the build), so they
    // are compiled as methods of their own and stay in use.
    @BeforeAll
    static void compile() {
        long sink = 0;
        long deadline = System.nanoTime() + 30_000_000_000L;
        Set<String> compiled = new HashSet<>();
        while (compiled.size() < KERNELS.size()) {
            assertTrue(System.nanoTime() < deadline, () -> "not compiled after 30s: only " + compiled);
            for (int i = 0; i < 20000; i++) {
                sink += twice(i) + twice((long) i) + thrice(i) + (long) half(i);
            }
            Sampler.forEachMethod(profile -> {
                String name = profile.name();
                if (name.startsWith(CLASS) && KERNELS.contains(name.substring(CLASS.length()))) {
                    compiled.add(name.substring(CLASS.length()));
                }
            });
        }
        assertNotEquals(42, sink);
    }

    static int twice(int x) {
        return x * 2;
    }

    static long twice(long x) {
        return x * 2;
    }

    static int thrice(int x) {
        return x * 3;
    }

    static double half(double x) {
        return x / 2;
    }

    /** The codes of a method's three strings. */
    record Codes(int classSignature, int methodName, int methodSignature) {
        static Codes of(InstructionProfile profile) {
            return new Codes(profile.classSignatureCode(), profile.methodNameCode(), profile.methodSignatureCode());
        }
    }

    @Test
    void sharesCodesExactlyWhenStringsAreShared() {
        Codes twiceInt = codes("twice(I)I");
        Codes twiceLong = codes("twice(J)J");
        Codes thrice = codes("thrice(I)I");
        Codes half = codes("half(D)D");
        // One class.
        assertEquals(twiceInt.classSignature(), twiceLong.classSignature());
        assertEquals(twiceInt.classSignature(), thrice.classSignature());
        assertEquals(twiceInt.classSignature(), half.classSignature());
        // Overloads share their name, not their signature.
        assertEquals(twiceInt.methodName(), twiceLong.methodName());
        assertNotEquals(twiceInt.methodSignature(), twiceLong.methodSignature());
        // Different names with one signature.
        assertNotEquals(twiceInt.methodName(), thrice.methodName());
        assertEquals(twiceInt.methodSignature(), thrice.methodSignature());
        assertNotEquals(thrice.methodSignature(), half.methodSignature());
    }

    @Test
    void namesAndCodesCorrespond() {
        // Over every compiled method: equal names have equal codes, and equal codes have
        // equal names. A method compiled more than once (C1 and C2) has its codes once.
        Map<String, Codes> byName = new HashMap<>();
        Map<Codes, String> byCodes = new HashMap<>();
        List<String> mismatches = new ArrayList<>();
        int[] methods = {0};
        Sampler.forEachMethod(profile -> {
            methods[0]++;
            String name = profile.name();
            Codes codes = Codes.of(profile);
            Codes previousCodes = byName.putIfAbsent(name, codes);
            String previousName = byCodes.putIfAbsent(codes, name);
            if ((previousCodes != null && !previousCodes.equals(codes)) || (previousName != null && !previousName.equals(name))) {
                mismatches.add(name + " " + codes + " vs " + previousName + " " + previousCodes);
            }
        });
        assertTrue(methods[0] > 100, () -> "only " + methods[0] + " compiled methods");
        assertTrue(mismatches.isEmpty(), mismatches::toString);
        assertTrue(byName.keySet().stream().noneMatch(String::isEmpty));
        // Far fewer class signatures than methods: each is stored once.
        long classes = byCodes.keySet().stream().mapToInt(Codes::classSignature).distinct().count();
        long signatures = byCodes.keySet().stream().mapToInt(Codes::methodSignature).distinct().count();
        assertTrue(classes < byName.size() && signatures < byName.size(),
                () -> classes + " classes and " + signatures + " signatures for " + byName.size() + " methods");
        // Codes are assigned from 0 in order, so they are small.
        int largest = byCodes.keySet().stream()
                .mapToInt(codes -> Math.max(codes.classSignature(), Math.max(codes.methodName(), codes.methodSignature())))
                .max()
                .orElseThrow();
        assertTrue(largest >= 0 && largest < 10 * methods[0], () -> "largest code " + largest);
    }

    @Test
    void snapshotsResolveTheirNames() {
        InstructionProfile[] snapshot = new InstructionProfile[1];
        String[] live = new String[1];
        Sampler.forEachMethod(profile -> {
            if (snapshot[0] == null && profile.name().equals(CLASS + "twice(I)I")) {
                live[0] = profile.name();
                snapshot[0] = profile.snapshot();
            }
        });
        assertNotNull(snapshot[0]);
        assertEquals(live[0], snapshot[0].name());
        assertEquals(CLASS + "twice(I)I", snapshot[0].toString().substring("InstructionProfile[".length()).split(" ")[0]);
    }

    private static Codes codes(String method) {
        Codes[] codes = new Codes[1];
        Sampler.forEachMethod(profile -> {
            if (codes[0] == null && profile.name().equals(CLASS + method)) {
                codes[0] = Codes.of(profile);
            }
        });
        assertNotNull(codes[0], () -> method + " isn't compiled");
        return codes[0];
    }
}
