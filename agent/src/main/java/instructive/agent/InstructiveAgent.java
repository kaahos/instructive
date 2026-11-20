package instructive.agent;

import java.lang.instrument.Instrumentation;

/** Java agent entry points. */
public final class InstructiveAgent {
    private InstructiveAgent() {}

    public static void premain(String args, Instrumentation instrumentation) {
        NativeLibraryLoader.load();
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        NativeLibraryLoader.load();
    }
}
