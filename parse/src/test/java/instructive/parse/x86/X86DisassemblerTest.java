package instructive.parse.x86;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class X86DisassemblerTest {
    private final X86Disassembler disassembler = new X86Disassembler();

    @Test
    void classifiesControlFlowAndResolvesTargets() {
        long base = 0x7f00_0000_1000L;
        assertBranch("eb10", base, "jmp", InstructionKind.BRANCH, base + 2 + 0x10);
        assertBranch("e9fbffffff", base, "jmp", InstructionKind.BRANCH, base);
        assertBranch("e800010000", base, "call", InstructionKind.CALL, base + 5 + 0x100);
        assertBranch("75fe", base, "jne", InstructionKind.CONDITIONAL_BRANCH, base);
        assertBranch("0f8c00100000", base, "jl", InstructionKind.CONDITIONAL_BRANCH, base + 6 + 0x1000);
        assertBranch("e2f0", base, "loop", InstructionKind.CONDITIONAL_BRANCH, base + 2 - 0x10);
        assertKind("ffe0", "jmp rax", InstructionKind.INDIRECT_BRANCH);
        assertKind("41ffd3", "call r11", InstructionKind.INDIRECT_CALL);
        assertKind("ff5008", "call qword ptr [rax + 0x8]", InstructionKind.INDIRECT_CALL);
        assertKind("c3", "ret", InstructionKind.RETURN);
        assertKind("c20800", "ret 0x8", InstructionKind.RETURN);
    }

    @Test
    void classifiesDataInstructions() {
        assertKind("448b5608", "mov r10d, dword ptr [rsi + 0x8]", InstructionKind.LOAD);
        assertKind("4d899788040000", "mov qword ptr [r15 + 0x488], r10", InstructionKind.STORE);
        assertKind("4889c3", "mov rbx, rax", InstructionKind.ALU);
        assertKind("488d540c08", "lea rdx, [rsp + rcx + 0x8]", InstructionKind.ALU);
        assertKind("4883ec40", "sub rsp, 0x40", InstructionKind.ALU);
        assertKind("49ba5481a787b77f0000", "movabs r10, 0x7fb787a78154", InstructionKind.ALU);
        assertKind("f0834424c000", "lock add dword ptr [rsp - 0x40], 0x0", InstructionKind.ATOMIC);
        assertKind("f0480fb10a", "lock cmpxchg qword ptr [rdx], rcx", InstructionKind.ATOMIC);
        assertKind("8700", "xchg dword ptr [rax], eax", InstructionKind.ATOMIC);
        assertKind("0f0d08", "prefetchw byte ptr [rax]", InstructionKind.PREFETCH);
        assertKind("f20f58c1", "addsd xmm0, xmm1", InstructionKind.FLOATING_POINT);
        assertKind("c5fb2ac0", "vcvtsi2sd xmm0, xmm0, eax", InstructionKind.FLOATING_POINT);
        assertKind("c5fe6f00", "vmovdqu ymm0, ymmword ptr [rax]", InstructionKind.LOAD);
        assertKind("62f1fe487f4c2401", "vmovdqu64 zmmword ptr [rsp + 0x40], zmm1", InstructionKind.STORE);
        assertKind("62f17dc9fe4002", "vpaddd zmm0 {k1} {z}, zmm0, zmmword ptr [rax + 0x80]", InstructionKind.SIMD);
        assertKind("62f17d58fe4002", "vpaddd zmm0, zmm0, dword ptr [rax + 0x8]{1to16}", InstructionKind.SIMD);
        assertKind("62f1ff1858c1", "vaddsd xmm0, xmm0, xmm1, {rn-sae}", InstructionKind.FLOATING_POINT);
        assertKind("c4e27bf6c1", "mulx eax, eax, ecx", InstructionKind.ALU);
        assertKind("c5f877", "vzeroupper", InstructionKind.SIMD);
        assertKind("dd00", "fld qword ptr [rax]", InstructionKind.FLOATING_POINT);
    }

    @Test
    void classifiesSystemInstructions() {
        assertKind("90", "nop", InstructionKind.SYSTEM);
        assertKind("0f1f440000", "nop dword ptr [rax + rax]", InstructionKind.SYSTEM);
        assertKind("f390", "pause", InstructionKind.SYSTEM);
        assertKind("0faee8", "lfence", InstructionKind.SYSTEM);
        assertKind("0f31", "rdtsc", InstructionKind.SYSTEM);
        assertKind("cc", "int3", InstructionKind.EXCEPTION);
        assertKind("f4", "hlt", InstructionKind.EXCEPTION);
        assertKind("0f0b", "ud2", InstructionKind.EXCEPTION);
    }

    @Test
    void formatsMemoryOperandsLikeLlvm() {
        assertText("8b44 8df0", "mov eax, dword ptr [rbp + 4*rcx - 0x10]");
        assertText("8b0425 10000000", "mov eax, dword ptr [0x10]");
        assertText("8b04c5 10000000", "mov eax, dword ptr [8*rax + 0x10]");
        assertText("8b05 10000000", "mov eax, dword ptr [rip + 0x10]");
        assertText("64488b0425 28000000", "mov rax, qword ptr fs:[0x28]");
        assertText("8b0420", "mov eax, dword ptr [rax + riz]");
        assertText("418b0424", "mov eax, dword ptr [r12]");
        assertText("678b00", "mov eax, dword ptr [eax]");
        assertText("4883c4f8", "add rsp, -0x8");
        assertText("3dffffffff", "cmp eax, 0xffffffff");
        assertText("483dffffffff", "cmp rax, -0x1");
        assertText("62f3fd4825c196", "vpternlogq zmm0, zmm0, zmm1, 0x96");
        assertText("0fc2c100", "cmpeqps xmm0, xmm1");
    }

    @Test
    void reportsWhatItCannotDecode() {
        Instruction invalid = disassembler.decode(HexFormat.of().parseHex("0e"), 0, 0);
        assertFalse(invalid.isDecoded());
        assertEquals(".byte 0x0e", invalid.text());
        assertEquals(1, invalid.length());
        assertEquals(InstructionKind.UNKNOWN, invalid.kind());
        // Truncated: the displacement is missing.
        assertFalse(disassembler.decode(HexFormat.of().parseHex("8b8000"), 0, 0).isDecoded());
        // 15 bytes is the limit.
        assertTrue(disassembler.decode(HexFormat.of().parseHex("6666666666666666666666666666" + "90"), 0, 0).isDecoded());
        assertFalse(disassembler.decode(HexFormat.of().parseHex("666666666666666666666666666666" + "90"), 0, 0).isDecoded());
        // The limit counts a leading lock prefix and bytes consumed while matching an opcode.
        assertFalse(disassembler.decode(HexFormat.of().parseHex("f0" + "6666666666666666666666666666" + "90"), 0, 0).isDecoded());
        assertFalse(disassembler.decode(HexFormat.of().parseHex("666666666666666666666666" + "f30f1efa"), 0, 0).isDecoded());
        assertFalse(disassembler.decode(HexFormat.of().parseHex("66666666666666666666666666" + "c6f800"), 0, 0).isDecoded());
        assertTrue(disassembler.decode(HexFormat.of().parseHex("6666666666666666666666" + "f30f1efa"), 0, 0).isDecoded());
        // A lone lock prefix.
        assertFalse(disassembler.decode(HexFormat.of().parseHex("f0"), 0, 0).isDecoded());
    }

    @Test
    void decodesCodeInOrder() {
        byte[] code = HexFormat.of().parseHex("554889e54883ec10" + "85c0" + "7404" + "c9" + "c3" + "0f0b" + "8b");
        List<Instruction> instructions = disassembler.decodeAll(code, 0x1000);
        assertEquals(List.of("push rbp", "mov rbp, rsp", "sub rsp, 0x10", "test eax, eax", "je 0x1010", "leave", "ret",
                        "ud2", ".byte 0x8b"),
                instructions.stream().map(Instruction::text).toList());
        assertEquals(List.of(0x1000L, 0x1001L, 0x1004L, 0x1008L, 0x100aL, 0x100cL, 0x100dL, 0x100eL, 0x1010L),
                instructions.stream().map(Instruction::address).toList());
    }

    private void assertText(String hex, String text) {
        byte[] bytes = HexFormat.of().parseHex(hex.replace(" ", ""));
        Instruction instruction = disassembler.decode(bytes, 0, 0);
        assertEquals(text, instruction.text());
        assertEquals(bytes.length, instruction.length(), text);
    }

    private void assertBranch(String hex, long address, String mnemonic, InstructionKind kind, long target) {
        Instruction instruction = disassembler.decode(HexFormat.of().parseHex(hex), 0, address);
        assertEquals(mnemonic, instruction.mnemonic(), instruction::toString);
        assertEquals(kind, instruction.kind(), instruction::toString);
        assertEquals(OptionalLong.of(target), instruction.branchTarget(), instruction::toString);
        assertEquals("0x" + Long.toHexString(target), instruction.operands(), instruction::toString);
        assertTrue(kind.isControlFlow());
    }

    private void assertKind(String hex, String text, InstructionKind kind) {
        byte[] bytes = HexFormat.of().parseHex(hex);
        Instruction instruction = disassembler.decode(bytes, 0, 0);
        assertEquals(text, instruction.text());
        assertEquals(bytes.length, instruction.length(), text);
        assertEquals(kind, instruction.kind(), text);
        assertTrue(instruction.isDecoded(), text);
        assertEquals(OptionalLong.empty(), instruction.branchTarget(), text);
    }
}
