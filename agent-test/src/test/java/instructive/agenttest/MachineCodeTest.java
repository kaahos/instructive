package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.Sampler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MachineCodeTest {
    private static final String COMPILED = "instructive.agenttest.CodeCacheTest.compiledLoop(J)J";

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void copiesTheMethodsCode() {
        InstructionProfile hot = hottestCompiledLoop();
        byte[] code = hot.machineCode().orElseThrow(() -> new AssertionError("no code for " + hot));
        // JVMTI's code size is the nmethod's code section, which the copy spans.
        assertEquals(hot.methodSize(), code.length, hot::toString);
        assertTrue(hot.profileLength() >= code.length, hot::toString);
        // Every sampled PC is an executed instruction, so within the code.
        for (int offset = 0; offset < hot.profileLength(); offset++) {
            if (hot.count(offset) > 0) {
                int sampled = offset;
                assertTrue(sampled < code.length, () -> "sample at +" + sampled + " beyond " + code.length + " bytes");
            }
        }
        // Stable while the method stays loaded.
        assertArrayEquals(code, hot.machineCode().orElseThrow());
    }

    @Test
    void worksFromTheLiveFlyweight() {
        InstructionProfile hot = hottestCompiledLoop();
        byte[][] code = new byte[1][];
        Sampler.forEachMethod(profile -> {
            if (profile.address() == hot.address() && profile.compileId() == hot.compileId()) {
                code[0] = profile.machineCode().orElse(null);
            }
        });
        assertNotNull(code[0]);
        assertArrayEquals(hot.machineCode().orElseThrow(), code[0]);
    }

    @Test
    void isOnlyReachableThroughInstructionProfile() throws Exception {
        Method method = machineCode0();
        assertTrue(Modifier.isPrivate(method.getModifiers()) && Modifier.isStatic(method.getModifiers()));
        assertEquals(List.of(long.class, int.class, int.class), List.of(method.getParameterTypes()));
    }

    @Test
    void refusesAnythingButAnExactMatch() throws Exception {
        InstructionProfile hot = hottestCompiledLoop();
        Method machineCode0 = machineCode0();
        machineCode0.setAccessible(true);
        long address = hot.address();
        int size = hot.methodSize();
        int id = hot.compileId();
        assertNotNull(machineCode0.invoke(null, address, size, id));
        // One field off at a time: each must be refused.
        assertNull(machineCode0.invoke(null, address + 4, size, id), "address");
        assertNull(machineCode0.invoke(null, address - 4, size, id), "address");
        assertNull(machineCode0.invoke(null, address, size + 1, id), "size");
        assertNull(machineCode0.invoke(null, address, size - 1, id), "size");
        assertNull(machineCode0.invoke(null, address, size, id + 1), "compile id");
        assertNull(machineCode0.invoke(null, address, 0, id), "size");
        // Not in the code cache at all.
        assertNull(machineCode0.invoke(null, 4096L, size, id), "address");
    }

    private static Method machineCode0() throws NoSuchMethodException {
        return InstructionProfile.class.getDeclaredMethod("machineCode0", long.class, int.class, int.class);
    }

    private static InstructionProfile hottestCompiledLoop() {
        assertTrue(Sampler.start());
        try {
            CodeCacheTest.compiledLoop(400);
        } finally {
            Sampler.stop();
        }
        InstructionProfile[] hottest = new InstructionProfile[1];
        Sampler.forEachHottestMethod(Integer.MAX_VALUE, profile -> {
            if (hottest[0] == null && COMPILED.equals(profile.name())) {
                hottest[0] = profile.snapshot();
            }
        });
        assertNotNull(hottest[0], "no samples of " + COMPILED);
        return hottest[0];
    }
}
