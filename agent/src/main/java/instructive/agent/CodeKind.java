package instructive.agent;

/** The kind of code a sample interrupted. Must match instructive::CodeKind in code_cache.h. */
public enum CodeKind {
    /** The code cache layout couldn't be read, so the PC wasn't classified. */
    UNKNOWN,
    /** Outside the code cache: the JVM itself, JNI or other native code. */
    NATIVE,
    /** The template interpreter. */
    INTERPRETED,
    /** JIT-compiled Java code. */
    COMPILED,
    /** Any other code in the code cache, such as runtime stubs and adapters. */
    STUB,
}
