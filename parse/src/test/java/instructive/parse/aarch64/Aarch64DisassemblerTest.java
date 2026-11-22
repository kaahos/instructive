package instructive.parse.aarch64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class Aarch64DisassemblerTest {
    private final Aarch64Disassembler disassembler = new Aarch64Disassembler();

    @Test
    void classifiesControlFlowAndResolvesTargets() {
        long base = 0x1_0000_1000L;
        assertBranch(0x14000002, base, "b", InstructionKind.BRANCH, base + 8);
        assertBranch(0x97ffffff, base, "bl", InstructionKind.CALL, base - 4);
        assertBranch(0x54000040, base, "b.eq", InstructionKind.CONDITIONAL_BRANCH, base + 8);
        assertBranch(0xb4000780, base, "cbz", InstructionKind.CONDITIONAL_BRANCH, base + 0xf0);
        assertBranch(0x36000088, base, "tbz", InstructionKind.CONDITIONAL_BRANCH, base + 0x10);
        assertKind(0xd61f0100, "br x8", InstructionKind.INDIRECT_BRANCH);
        assertKind(0xd63f0100, "blr x8", InstructionKind.INDIRECT_CALL);
        assertKind(0xd65f03c0, "ret", InstructionKind.RETURN);
        assertTrue(InstructionKind.CONDITIONAL_BRANCH.isControlFlow());
        assertFalse(InstructionKind.LOAD.isControlFlow());
    }

    @Test
    void classifiesDataAndSystemInstructions() {
        assertKind(0xb9400828, "ldr w8, [x1, #0x8]", InstructionKind.LOAD);
        assertKind(0xa9bf7bfd, "stp x29, x30, [sp, #-0x10]!", InstructionKind.STORE);
        assertKind(0x88e8fd4d, "casal w8, w13, [x10]", InstructionKind.ATOMIC);
        assertKind(0xf8e00062, "ldaddal x0, x2, [x3]", InstructionKind.ATOMIC);
        assertKind(0x88dffd4a, "ldar w10, [x10]", InstructionKind.LOAD);
        assertKind(0xf980c1b0, "prfm pstl1keep, [x13, #0x180]", InstructionKind.PREFETCH);
        assertKind(0xf2a28c48, "movk x8, #0x1462, lsl #16", InstructionKind.ALU);
        assertKind(0x1e600864, "fmul d4, d3, d0", InstructionKind.FLOATING_POINT);
        assertKind(0xd5033bbf, "dmb ish", InstructionKind.SYSTEM);
        assertKind(0xd503201f, "nop", InstructionKind.SYSTEM);
        assertKind(0xd4200000, "brk #0", InstructionKind.EXCEPTION);
        assertKind(0x00000000, "udf #0x0", InstructionKind.EXCEPTION);
    }

    @Test
    void reportsWhatItCannotDecode() {
        // cnt.8b: vector SIMD is classified but not decoded.
        Instruction simd = disassembler.decode(0x0e205a31, 0);
        assertEquals(InstructionKind.SIMD, simd.kind());
        assertFalse(simd.isDecoded());
        assertEquals(".word 0x0e205a31", simd.text());
        Instruction unallocated = disassembler.decode(0x0d0bf000, 0);
        assertFalse(unallocated.isDecoded());
    }

    @Test
    void classifiesSve() {
        // From C2's code on a CPU with SVE: ptrue p7.b, ld1w {z16.s}, p7/z, [x14, #1, mul vl],
        // add z16.s, z16.s, z17.s and st1w {z16.s}, p7, [x13, #1, mul vl].
        for (int word : new int[] {0x2518e3e7, 0xa541bdd0, 0x04b10210, 0xe541fdb0}) {
            Instruction sve = disassembler.decode(word, 0);
            assertEquals(InstructionKind.SIMD, sve.kind(), sve::text);
            assertFalse(sve.isDecoded());
        }
    }

    @Test
    void resolvesPageAddresses() {
        // adrp x0, 0x4000 from 0x1b8: the page base plus four pages.
        Instruction adrp = disassembler.decode(0x90000020, 0x1b8);
        assertEquals("adrp x0, 0x4000", adrp.text());
        assertEquals(OptionalLong.empty(), adrp.branchTarget());
    }

    @Test
    void decodesCodeInOrder() {
        byte[] code = {
            (byte) 0x1f, 0x20, 0x03, (byte) 0xd5, // nop
            0x28, 0x08, 0x40, (byte) 0xb9, // ldr w8, [x1, #0x8]
            0x40, 0x00, 0x00, 0x54, // b.eq +8
            (byte) 0xc0, 0x03, 0x5f, (byte) 0xd6, // ret
            0x01, 0x02 // truncated
        };
        List<Instruction> instructions = disassembler.decodeAll(code, 0x1000);
        assertEquals(List.of("nop", "ldr w8, [x1, #0x8]", "b.eq 0x1010", "ret", ".byte"),
                instructions.stream().map(Instruction::text).toList());
        assertEquals(List.of(0x1000L, 0x1004L, 0x1008L, 0x100cL, 0x1010L),
                instructions.stream().map(Instruction::address).toList());
        assertEquals(2, instructions.get(instructions.size() - 1).length());
        assertEquals(InstructionKind.UNKNOWN, instructions.get(instructions.size() - 1).kind());
    }

    @Test
    void decodesBitMaskImmediates() {
        assertEquals(0xFFL, Aarch64Disassembler.decodeBitMask(0, 7, 0, 32));
        assertEquals(0x5555_5555_5555_5555L, Aarch64Disassembler.decodeBitMask(0, 0x3c, 0, 64));
        assertEquals(0xFFFF_FFFF_FFFF_FFE0L, Aarch64Disassembler.decodeBitMask(1, 58, 59, 64));
        // All ones is reserved.
        assertEquals(0, Aarch64Disassembler.decodeBitMask(1, 63, 0, 64));
    }

    private void assertBranch(int word, long address, String mnemonic, InstructionKind kind, long target) {
        Instruction instruction = disassembler.decode(word, address);
        assertEquals(mnemonic, instruction.mnemonic(), instruction::toString);
        assertEquals(kind, instruction.kind(), instruction::toString);
        assertEquals(OptionalLong.of(target), instruction.branchTarget(), instruction::toString);
        assertTrue(instruction.operands().endsWith("0x" + Long.toHexString(target)), instruction::toString);
    }

    private void assertKind(int word, String text, InstructionKind kind) {
        Instruction instruction = disassembler.decode(word, 0);
        assertEquals(text, instruction.text());
        assertEquals(kind, instruction.kind(), text);
        assertTrue(instruction.isDecoded(), text);
        assertEquals(OptionalLong.empty(), instruction.branchTarget(), text);
    }
}
