package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.parse.Disassembler;
import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Decodes the machine code of real hot methods. */
class DisassemblyTest {
    private static final String COMPILED = "instructive.agenttest.CodeCacheTest.compiledLoop(J)J";

    private static final Disassembler disassembler =
            InstructionProfileFormatter.forArchitecture(System.getProperty("os.arch")).orElse(null);

    @BeforeAll
    static void needsADecoder() {
        assumeTrue(disassembler != null, "no decoder for " + System.getProperty("os.arch"));
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void decodesHotMethodsAndTheirSampledInstructions() {
        sample();
        List<InstructionProfile> hottest = new ArrayList<>();
        Sampler.forEachHottestMethod(10, profile -> hottest.add(profile.snapshot()));
        assertTrue(!hottest.isEmpty());
        for (InstructionProfile profile : hottest) {
            byte[] code = profile.machineCode().orElse(null);
            if (code == null) {
                continue;
            }
            List<Instruction> instructions = disassembler.decodeAll(code, profile.address());
            long decoded = instructions.stream().filter(Instruction::isDecoded).count();
            // Compiled code is nearly all decodable; the rest is vector SIMD (on aarch64) or data.
            assertTrue(decoded >= 0.95 * instructions.size(), () -> profile.name() + ": " + decoded + " of " + instructions.size());
            Map<Long, Instruction> byAddress = new TreeMap<>();
            instructions.forEach(instruction -> byAddress.put(instruction.address(), instruction));
            for (int offset = 0; offset < Math.min(code.length, profile.profileLength()); offset++) {
                if (profile.count(offset) > 0) {
                    Instruction sampled = byAddress.get(profile.address() + offset);
                    int at = offset;
                    assertNotNull(sampled, () -> profile.name() + ": sample at +" + at + " is not an instruction start");
                    assertTrue(sampled.isDecoded(), () -> profile.name() + ": sampled " + sampled);
                }
            }
        }
    }

    @Test
    void findsTheHotLoopsBackEdge() {
        sample();
        InstructionProfile[] loop = new InstructionProfile[1];
        Sampler.forEachHottestMethod(Integer.MAX_VALUE, profile -> {
            if (loop[0] == null && COMPILED.equals(profile.name())) {
                loop[0] = profile.snapshot();
            }
        });
        assertNotNull(loop[0]);
        InstructionProfile profile = loop[0];
        List<Instruction> instructions = disassembler.decodeAll(profile.machineCode().orElseThrow(), profile.address());
        long start = profile.address();
        long end = start + profile.methodSize();
        // A loop closes with a conditional branch back to an earlier instruction in the method.
        List<Instruction> backEdges = instructions.stream()
                .filter(instruction -> instruction.kind() == InstructionKind.CONDITIONAL_BRANCH)
                .filter(instruction -> instruction.branchTarget().getAsLong() < instruction.address())
                .filter(instruction -> instruction.branchTarget().getAsLong() >= start)
                .toList();
        assertTrue(!backEdges.isEmpty(), () -> String.join("\n", instructions.stream().map(Instruction::toString).toList()));
        // Most samples fall inside the hottest back-edge's loop body.
        long inLoop = 0;
        for (Instruction edge : backEdges) {
            long body = 0;
            for (long pc = edge.branchTarget().getAsLong(); pc < edge.address() + edge.length(); pc++) {
                body += profile.count((int) (pc - start));
            }
            inLoop = Math.max(inLoop, body);
        }
        long total = profile.sampled();
        long hot = inLoop;
        assertTrue(end > start && hot * 2 > total, () -> hot + " of " + total + " samples in the loop");
    }

    private static void sample() {
        assertTrue(Sampler.start());
        try {
            CodeCacheTest.compiledLoop(400);
        } finally {
            Sampler.stop();
        }
    }
}
