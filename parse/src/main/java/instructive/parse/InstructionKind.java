package instructive.parse;

/** What an instruction does, coarsely, for analysing control flow and hot code. */
public enum InstructionKind {
    /** Unconditional direct branch. */
    BRANCH,
    /** Conditional direct branch, falling through when not taken. */
    CONDITIONAL_BRANCH,
    /** Branch to an address in a register. */
    INDIRECT_BRANCH,
    /** Direct call. */
    CALL,
    /** Call to an address in a register. */
    INDIRECT_CALL,
    RETURN,
    /** Integer arithmetic, logic, moves, comparisons and address computation. */
    ALU,
    LOAD,
    STORE,
    /** Atomic read-modify-write, compare-and-swap and exclusive accesses. */
    ATOMIC,
    PREFETCH,
    FLOATING_POINT,
    SIMD,
    /** Barriers, hints, system registers and cache maintenance. */
    SYSTEM,
    /** Traps such as breakpoints, supervisor calls and permanently undefined instructions. */
    EXCEPTION,
    /** Not decoded. */
    UNKNOWN;

    /** Whether control can transfer somewhere other than the next instruction. */
    public boolean isControlFlow() {
        return switch (this) {
            case BRANCH, CONDITIONAL_BRANCH, INDIRECT_BRANCH, CALL, INDIRECT_CALL, RETURN -> true;
            default -> false;
        };
    }
}
