package instructive.parse;

import java.util.OptionalLong;

/**
 * One decoded machine instruction.
 *
 * @param address      where the instruction is
 * @param length       its size in bytes
 * @param mnemonic     the mnemonic, using the architecture's preferred aliases, or
 *                     {@code .word} if it couldn't be decoded
 * @param operands     the operands as text, possibly empty
 * @param kind         what the instruction does
 * @param branchTarget the destination of a direct branch or call
 */
public record Instruction(
        long address, int length, String mnemonic, String operands, InstructionKind kind, OptionalLong branchTarget) {

    public boolean isDecoded() {
        return kind != InstructionKind.UNKNOWN && !".word".equals(mnemonic);
    }

    /** The instruction as assembly text, e.g. {@code ldr x0, [x1, #0x8]}. */
    public String text() {
        return operands.isEmpty() ? mnemonic : mnemonic + " " + operands;
    }

    @Override
    public String toString() {
        return String.format("0x%x: %s", address, text());
    }
}
