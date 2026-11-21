package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.Sampler;
import instructive.analyse.DependencyChain;
import instructive.analyse.LongDependencyChainRule;
import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Finds the serial chain h = 31 * h + a[i] in Arrays.hashCode(int[]) as compiled by C2. */
class DependencyChainTest {

    @BeforeAll
    static void onlyOnAarch64() {
        assumeTrue(System.getProperty("os.arch").equals("aarch64"), "the decoder is for aarch64");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void flagsArraysHashCodesDataDependency() {
        InstructionProfile hottest = profileArraysHashCode();
        byte[] code = hottest.machineCode().orElseThrow();
        List<Instruction> instructions = new Aarch64Disassembler().decodeAll(code, hottest.address());

        List<DependencyChain> chains = new LongDependencyChainRule().apply(
                instructions, new Aarch64RegisterModel(), instruction -> hottest.count((int) (instruction.address() - hottest.address())));
        String report = describe(hottest, chains);
        assertFalse(chains.isEmpty(), report);

        DependencyChain hottestChain = chains.get(0);
        assertTrue(hottestChain.length() >= LongDependencyChainRule.DEFAULT_MINIMUM_LENGTH, report);
        // h = 31 * h + x is computed as lsl #5, sub, add.
        long arithmetic = hottestChain.instructions().stream()
                .filter(instruction -> Set.of("lsl", "sub", "add").contains(instruction.mnemonic()))
                .count();
        assertTrue(arithmetic * 10 >= hottestChain.length() * 8, report);
        // The chain runs through the code where the method spends its time.
        assertTrue(hottestChain.spanSamples() * 10 >= hottest.sampled() * 9, report);
    }

    private static InstructionProfile profileArraysHashCode() {
        int[] data = new int[1 << 16];
        for (int i = 0; i < data.length; i++) {
            data[i] = i * 0x9E3779B9;
        }
        long sink = 0;
        for (int i = 0; i < 2000; i++) {
            sink += Arrays.hashCode(data); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                sink += Arrays.hashCode(data);
            }
        } finally {
            Sampler.stop();
        }
        InstructionProfile[] hottest = new InstructionProfile[1];
        Sampler.forEachHottestMethod(1, profile -> hottest[0] = profile.snapshot());
        assertNotNull(hottest[0], "no samples; sink " + sink);
        return hottest[0];
    }

    private static String describe(InstructionProfile profile, List<DependencyChain> chains) {
        return profile.name() + " L" + profile.compLevel() + ", " + profile.sampled() + " samples\n"
                + chains.stream()
                        .map(chain -> "  " + chain.length() + " long, " + chain.samples() + " samples: "
                                + chain.instructions().stream().map(Instruction::text).collect(Collectors.joining("; ")))
                        .collect(Collectors.joining("\n"));
    }
}
