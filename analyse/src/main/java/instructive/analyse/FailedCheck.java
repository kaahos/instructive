package instructive.analyse;

import instructive.parse.Instruction;
import java.util.List;
import java.util.OptionalInt;

/**
 * Explicit null or range checks in compiled code: a test and a branch to code that throws
 * the exception or deoptimises. C2 normally needs neither. A null check is implicit (the
 * access itself faults on null, and the fault is turned into a trap), and the range
 * checks of a counted loop are eliminated or hoisted out of it. When a check has failed at
 * a location, the profile records it, and the recompiled code checks explicitly there.
 *
 * @param kind           what is checked
 * @param failure        what happens when the check fails
 * @param checks         the branches that check, in address order: one bytecode's check,
 *                       copied by unrolling and by the loops C2 puts before and after an
 *                       unrolled loop (for a throw), or one loop's range checks (for a
 *                       trap)
 * @param failurePath    the address the first check branches to
 * @param trapRequest    for {@link Failure#TRAP}, the trap request passed to the
 *                       uncommon trap, which encodes the reason and the action
 * @param inLoop         whether any of the checks is in a loop
 * @param samples        the samples taken on the innermost loops containing the checks,
 *                       or on the checks' blocks for checks outside loops: how hot the
 *                       checked code is
 */
public record FailedCheck(
        Kind kind, Failure failure, List<Instruction> checks, long failurePath, OptionalInt trapRequest,
        boolean inLoop, long samples) {

    public enum Kind {
        /** A reference tested for null before it is used as an address. */
        NULL_CHECK,
        /** An index compared with an array's length. */
        RANGE_CHECK
    }

    public enum Failure {
        /**
         * The compiled code throws the exception itself, from a preallocated instance
         * (C2's fast throw): the check has failed at this location, the profile recorded
         * it, and the recompiled code branches to the throw. Once is enough.
         */
        THROW,
        /**
         * The compiled code calls the uncommon trap, deoptimising, if the check fails: a
         * range check C2 couldn't eliminate from a loop, for example one it couldn't treat
         * as counted. Every iteration pays for it.
         */
        TRAP
    }

    public FailedCheck {
        checks = List.copyOf(checks);
    }

    /** The trap's reason, decoded from {@link #trapRequest()}, as HotSpot numbers them. */
    public OptionalInt trapReason() {
        return trapRequest.isPresent() ? OptionalInt.of((~trapRequest.getAsInt() >> 3) & 0x1f) : OptionalInt.empty();
    }

    /** The trap's action, decoded from {@link #trapRequest()}. */
    public OptionalInt trapAction() {
        return trapRequest.isPresent() ? OptionalInt.of(~trapRequest.getAsInt() & 0x7) : OptionalInt.empty();
    }
}
