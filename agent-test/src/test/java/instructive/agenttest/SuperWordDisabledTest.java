package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.Sampler;
import instructive.analyse.MissedVectorisation;
import instructive.parse.Instruction;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * With C2's vectoriser turned off ({@code -XX:-UseSuperWord}, set by the
 * {@code noSuperWordTest} task), a loop it would vectorise is unrolled into scalar
 * copies, which {@code MissedVectorisationRule} reports.
 */
@Tag("no-superword")
class SuperWordDisabledTest {

    @BeforeAll
    static void onlyOnAarch64WithoutSuperWord() {
        assumeTrue(System.getProperty("os.arch").equals("aarch64"), "the register model is for aarch64");
        assumeTrue(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                .contains("-XX:-UseSuperWord"), "needs -XX:-UseSuperWord");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void findsAnIntersectionThatWasNotVectorised() {
        InstructionProfile profile = MissedVectorisationTest.profileIntersect();
        List<MissedVectorisation> findings = MissedVectorisationTest.apply(profile);
        String report = MissedVectorisationTest.describe(profile, findings);
        assertEquals(1, findings.size(), report);
        MissedVectorisation found = findings.get(0);
        assertTrue(found.lanes() >= 2, report);
        assertEquals(8, found.elementSize(), report);
        assertEquals(List.of("and"), found.operations().stream().map(Instruction::mnemonic).toList(), report);
        for (List<Instruction> copy : found.copies()) {
            assertEquals(List.of("ldr", "ldr", "and", "str"),
                    copy.stream().map(Instruction::mnemonic).toList(), report);
        }
        // Only the loop: the stubs after the code hold addresses, which can decode as SIMD.
        assertEquals(0, MissedVectorisationTest.simd(found.instructions()), report);
        assertTrue(found.samples() * 4 >= profile.sampled() * 3, report);
    }
}
