package instructive.parse;

import java.util.ArrayList;
import java.util.List;

/** Decodes machine code for one architecture. */
public interface Disassembler {

    /**
     * Decodes the instruction at {@code offset} in {@code code}, which is located at
     * {@code address}. Never throws for undecodable bytes: those come back with kind
     * {@link InstructionKind#UNKNOWN}.
     */
    Instruction decode(byte[] code, int offset, long address);

    /** Decodes {@code code}, located at {@code address}, from start to end. */
    default List<Instruction> decodeAll(byte[] code, long address) {
        List<Instruction> instructions = new ArrayList<>();
        for (int offset = 0; offset < code.length; ) {
            Instruction instruction = decode(code, offset, address + offset);
            instructions.add(instruction);
            offset += instruction.length();
        }
        return instructions;
    }
}
