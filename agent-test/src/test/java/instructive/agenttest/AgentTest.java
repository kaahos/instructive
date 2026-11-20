package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.agent.NativeLibraryLoader;
import org.junit.jupiter.api.Test;

class AgentTest {
    @Test
    void javaAgentLoadsNativeLibrary() {
        assertTrue(NativeLibraryLoader.isLoaded());
    }
}
