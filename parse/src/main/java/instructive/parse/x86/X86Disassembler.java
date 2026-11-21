package instructive.parse.x86;

import static instructive.parse.InstructionKind.ALU;
import static instructive.parse.InstructionKind.ATOMIC;
import static instructive.parse.InstructionKind.BRANCH;
import static instructive.parse.InstructionKind.CALL;
import static instructive.parse.InstructionKind.CONDITIONAL_BRANCH;
import static instructive.parse.InstructionKind.EXCEPTION;
import static instructive.parse.InstructionKind.FLOATING_POINT;
import static instructive.parse.InstructionKind.INDIRECT_BRANCH;
import static instructive.parse.InstructionKind.INDIRECT_CALL;
import static instructive.parse.InstructionKind.LOAD;
import static instructive.parse.InstructionKind.PREFETCH;
import static instructive.parse.InstructionKind.RETURN;
import static instructive.parse.InstructionKind.SIMD;
import static instructive.parse.InstructionKind.STORE;
import static instructive.parse.InstructionKind.SYSTEM;
import static instructive.parse.InstructionKind.UNKNOWN;

import instructive.parse.Disassembler;
import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import java.util.List;
import java.util.OptionalLong;

/**
 * A decoder for x86-64 machine code in 64-bit mode: the general purpose instructions,
 * x87, and the SSE, AVX and AVX-512 families (through {@link OpcodeTable}, which was
 * generated from LLVM).
 *
 * <p>Text follows LLVM's Intel syntax, so it can be checked against
 * {@code llvm-objdump -M intel}: hex immediates, {@code qword ptr [rbx + 8*rcx + 0x10]}
 * memory operands, {@code {k1} {z}} masks, {@code {1to16}} broadcasts and absolute branch
 * targets. One deliberate difference: LLVM prints a {@code lock} prefix as an instruction
 * of its own, while here it is part of the instruction it applies to
 * ({@code lock cmpxchg ...}), because a sample can never land between them. Bytes that
 * can't be decoded come back one at a time as {@code .byte 0x...}. XOP, 3DNow! and APX
 * (REX2 and extended EVEX) encodings are not decoded.
 */
public final class X86Disassembler implements Disassembler {
    private static final int MAX_LENGTH = 15;

    static final String[] GPR64 = {
        "rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi", "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15"
    };
    static final String[] GPR32 = {
        "eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi", "r8d", "r9d", "r10d", "r11d", "r12d", "r13d", "r14d", "r15d"
    };
    static final String[] GPR16 = {
        "ax", "cx", "dx", "bx", "sp", "bp", "si", "di", "r8w", "r9w", "r10w", "r11w", "r12w", "r13w", "r14w", "r15w"
    };
    static final String[] GPR8 = {
        "al", "cl", "dl", "bl", "spl", "bpl", "sil", "dil", "r8b", "r9b", "r10b", "r11b", "r12b", "r13b", "r14b", "r15b"
    };
    static final String[] GPR8_LEGACY = {"al", "cl", "dl", "bl", "ah", "ch", "dh", "bh"};
    private static final String[] SEGMENTS = {"es", "cs", "ss", "ds", "fs", "gs"};
    private static final String[] CONDITIONS = {
        "o", "no", "b", "ae", "e", "ne", "be", "a", "s", "ns", "p", "np", "l", "ge", "le", "g"
    };
    private static final String[] ROUNDING = {"{rn-sae}", "{rd-sae}", "{ru-sae}", "{rz-sae}"};

    /** Thrown, without a stack trace, for an encoding that isn't valid. */
    private static final class Invalid extends RuntimeException {
        Invalid() {
            super(null, null, false, false);
        }
    }

    private static final Invalid INVALID = new Invalid();

    // ------------------------------------------------------------------------------------
    // General purpose instructions, described by short specifications.
    //
    // A mnemonic may list variants for 16, 32 and 64-bit operands separated by '/'. Operands:
    //   E? ModRM.rm (register or memory), G? ModRM.reg, Z? register in the opcode's low bits,
    //   with size b, w, d, q, v (operand size) or y (32 or 64 by REX.W), or s (a register
    //   of the operand size, or a word in memory);
    //   M? memory only, with size b w d q x t f, or none for an address (lea);
    //   Ib sign-extended imm8, Iu unsigned imm8, Iw imm16, Is signed imm16, Ir imm16 signed
    //   unless there is an operand size prefix, Iz imm16/32 by operand size,
    //   Io imm16/32/64 by operand size; Jb/Jz relative branch targets;
    //   AL, CL, DX fixed registers, rA the accumulator at the operand size; Sw segment
    //   register; Cq/Dq control/debug register; Ob/Ov absolute address; Xb/Xv [rsi]; Yb/Yv es:[rdi].
    // Flags before ':' are d64 (default 64-bit operand size) and f64 (always 64-bit).
    // ------------------------------------------------------------------------------------

    private record Op(String[] mnemonics, String[] operands, boolean d64, boolean f64, InstructionKind kind) {
        String mnemonic(int size) {
            return mnemonics.length == 1 ? mnemonics[0] : mnemonics[size == 16 ? 0 : size == 32 ? 1 : 2];
        }
    }

    private static Op op(String spec, InstructionKind kind) {
        boolean d64 = false, f64 = false;
        int colon = spec.indexOf(':');
        if (colon >= 0) {
            String flag = spec.substring(0, colon);
            d64 = flag.equals("d64");
            f64 = flag.equals("f64");
            spec = spec.substring(colon + 1);
        }
        int space = spec.indexOf(' ');
        String mnemonic = space < 0 ? spec : spec.substring(0, space);
        String[] operands = space < 0 ? new String[0] : spec.substring(space + 1).split(",");
        return new Op(mnemonic.split("/"), operands, d64, f64, kind);
    }

    private static final Op[] ONE_BYTE = new Op[256];
    private static final Op[][] GROUPS = new Op[256][];
    private static final Op[] TWO_BYTE = new Op[256];
    private static final Op[][] TWO_BYTE_GROUPS = new Op[256][];
    // Special kind marker: LOAD if a source operand is memory, STORE if the destination is, else ALU.
    private static final InstructionKind MOVE = null;

    static {
        String[] alu = {"add", "or", "adc", "sbb", "and", "sub", "xor", "cmp"};
        for (int i = 0; i < 8; i++) {
            int base = i << 3;
            ONE_BYTE[base] = op(alu[i] + " Eb,Gb", ALU);
            ONE_BYTE[base + 1] = op(alu[i] + " Ev,Gv", ALU);
            ONE_BYTE[base + 2] = op(alu[i] + " Gb,Eb", ALU);
            ONE_BYTE[base + 3] = op(alu[i] + " Gv,Ev", ALU);
            ONE_BYTE[base + 4] = op(alu[i] + " AL,Ib", ALU);
            ONE_BYTE[base + 5] = op(alu[i] + " rA,Iz", ALU);
        }
        for (int i = 0; i < 8; i++) {
            ONE_BYTE[0x50 + i] = op("d64:push Zv", STORE);
            ONE_BYTE[0x58 + i] = op("d64:pop Zv", LOAD);
            ONE_BYTE[0xB0 + i] = op("mov Zb,Ib", ALU);
            ONE_BYTE[0xB8 + i] = op("mov/mov/movabs Zv,Io", ALU);
            ONE_BYTE[0x70 + i] = op("f64:j" + CONDITIONS[i] + " Jb", CONDITIONAL_BRANCH);
            ONE_BYTE[0x78 + i] = op("f64:j" + CONDITIONS[i + 8] + " Jb", CONDITIONAL_BRANCH);
        }
        ONE_BYTE[0x63] = op("movsxd Gv,Ed", MOVE);
        ONE_BYTE[0x68] = op("d64:push Iz", STORE);
        ONE_BYTE[0x69] = op("imul Gv,Ev,Iz", ALU);
        ONE_BYTE[0x6A] = op("d64:push Ib", STORE);
        ONE_BYTE[0x6B] = op("imul Gv,Ev,Ib", ALU);
        ONE_BYTE[0x6C] = op("insb Yb,DX", SYSTEM);
        ONE_BYTE[0x6D] = op("insw/insd/insd Yz,DX", SYSTEM);
        ONE_BYTE[0x6E] = op("outsb DX,Xb", SYSTEM);
        ONE_BYTE[0x6F] = op("outsw/outsd/outsd DX,Xz", SYSTEM);
        ONE_BYTE[0x84] = op("test Eb,Gb", ALU);
        ONE_BYTE[0x85] = op("test Ev,Gv", ALU);
        ONE_BYTE[0x86] = op("xchg Eb,Gb", ATOMIC);
        ONE_BYTE[0x87] = op("xchg Ev,Gv", ATOMIC);
        ONE_BYTE[0x88] = op("mov Eb,Gb", MOVE);
        ONE_BYTE[0x89] = op("mov Ev,Gv", MOVE);
        ONE_BYTE[0x8A] = op("mov Gb,Eb", MOVE);
        ONE_BYTE[0x8B] = op("mov Gv,Ev", MOVE);
        ONE_BYTE[0x8C] = op("mov Es,Sw", MOVE);
        ONE_BYTE[0x8D] = op("lea Gv,M", ALU);
        ONE_BYTE[0x8E] = op("mov Sw,Es", MOVE);
        GROUPS[0x8F] = new Op[] {op("d64:pop Ev", LOAD), null, null, null, null, null, null, null};
        ONE_BYTE[0x98] = op("cbw/cwde/cdqe", ALU);
        ONE_BYTE[0x99] = op("cwd/cdq/cqo", ALU);
        ONE_BYTE[0x9B] = op("wait", SYSTEM);
        ONE_BYTE[0x9C] = op("d64:pushf/pushfq/pushfq", STORE);
        ONE_BYTE[0x9D] = op("d64:popf/popfq/popfq", LOAD);
        ONE_BYTE[0x9E] = op("sahf", ALU);
        ONE_BYTE[0x9F] = op("lahf", ALU);
        ONE_BYTE[0xA0] = op("movabs AL,Ob", LOAD);
        ONE_BYTE[0xA1] = op("movabs rA,Ov", LOAD);
        ONE_BYTE[0xA2] = op("movabs Ob,AL", STORE);
        ONE_BYTE[0xA3] = op("movabs Ov,rA", STORE);
        ONE_BYTE[0xA4] = op("movsb Yb,Xb", STORE);
        ONE_BYTE[0xA5] = op("movsw/movsd/movsq Yv,Xv", STORE);
        ONE_BYTE[0xA6] = op("cmpsb Xb,Yb", LOAD);
        ONE_BYTE[0xA7] = op("cmpsw/cmpsd/cmpsq Xv,Yv", LOAD);
        ONE_BYTE[0xA8] = op("test AL,Ib", ALU);
        ONE_BYTE[0xA9] = op("test rA,Iz", ALU);
        ONE_BYTE[0xAA] = op("stosb Yb,AL", STORE);
        ONE_BYTE[0xAB] = op("stosw/stosd/stosq Yv,rA", STORE);
        ONE_BYTE[0xAC] = op("lodsb AL,Xb", LOAD);
        ONE_BYTE[0xAD] = op("lodsw/lodsd/lodsq rA,Xv", LOAD);
        ONE_BYTE[0xAE] = op("scasb AL,Yb", LOAD);
        ONE_BYTE[0xAF] = op("scasw/scasd/scasq rA,Yv", LOAD);
        ONE_BYTE[0xC2] = op("f64:ret Ir", RETURN);
        ONE_BYTE[0xC3] = op("f64:ret", RETURN);
        ONE_BYTE[0xC8] = op("enter Is,Ib", STORE);
        ONE_BYTE[0xC9] = op("d64:leave", LOAD);
        ONE_BYTE[0xCA] = op("retf/retf/retfq Ir", RETURN);
        ONE_BYTE[0xCB] = op("retf/retf/retfq", RETURN);
        ONE_BYTE[0xCC] = op("int3", EXCEPTION);
        ONE_BYTE[0xCD] = op("int Iu", EXCEPTION);
        ONE_BYTE[0xCF] = op("iret/iretd/iretq", RETURN);
        ONE_BYTE[0xD7] = op("xlatb", LOAD);
        ONE_BYTE[0xE0] = op("f64:loopne Jb", CONDITIONAL_BRANCH);
        ONE_BYTE[0xE1] = op("f64:loope Jb", CONDITIONAL_BRANCH);
        ONE_BYTE[0xE2] = op("f64:loop Jb", CONDITIONAL_BRANCH);
        ONE_BYTE[0xE3] = op("f64:jrcxz Jb", CONDITIONAL_BRANCH);
        ONE_BYTE[0xE4] = op("in AL,Iu", SYSTEM);
        ONE_BYTE[0xE5] = op("in eA,Iu", SYSTEM);
        ONE_BYTE[0xE6] = op("out Iu,AL", SYSTEM);
        ONE_BYTE[0xE7] = op("out Iu,eA", SYSTEM);
        ONE_BYTE[0xE8] = op("f64:call Jz", CALL);
        ONE_BYTE[0xE9] = op("f64:jmp Jz", BRANCH);
        ONE_BYTE[0xEB] = op("f64:jmp Jb", BRANCH);
        ONE_BYTE[0xEC] = op("in AL,DX", SYSTEM);
        ONE_BYTE[0xED] = op("in eA,DX", SYSTEM);
        ONE_BYTE[0xEE] = op("out DX,AL", SYSTEM);
        ONE_BYTE[0xEF] = op("out DX,eA", SYSTEM);
        ONE_BYTE[0xF4] = op("hlt", EXCEPTION);
        ONE_BYTE[0xF5] = op("cmc", ALU);
        ONE_BYTE[0xF8] = op("clc", ALU);
        ONE_BYTE[0xF9] = op("stc", ALU);
        ONE_BYTE[0xFA] = op("cli", SYSTEM);
        ONE_BYTE[0xFB] = op("sti", SYSTEM);
        ONE_BYTE[0xFC] = op("cld", ALU);
        ONE_BYTE[0xFD] = op("std", ALU);

        GROUPS[0x80] = aluGroup("Eb,Ib");
        GROUPS[0x81] = aluGroup("Ev,Iz");
        GROUPS[0x83] = aluGroup("Ev,Ib");
        String[] shifts = {"rol", "ror", "rcl", "rcr", "shl", "shr", null, "sar"};
        GROUPS[0xC0] = shiftGroup(shifts, "Eb,Iu");
        GROUPS[0xC1] = shiftGroup(shifts, "Ev,Iu");
        GROUPS[0xD0] = shiftGroup(shifts, "Eb");
        GROUPS[0xD1] = shiftGroup(shifts, "Ev");
        GROUPS[0xD2] = shiftGroup(shifts, "Eb,CL");
        GROUPS[0xD3] = shiftGroup(shifts, "Ev,CL");
        GROUPS[0xC6] = new Op[] {op("mov Eb,Ib", MOVE), null, null, null, null, null, null, null};
        GROUPS[0xC7] = new Op[] {op("mov Ev,Iz", MOVE), null, null, null, null, null, null, null};
        GROUPS[0xF6] = new Op[] {
            op("test Eb,Ib", ALU), null, op("not Eb", ALU), op("neg Eb", ALU),
            op("mul Eb", ALU), op("imul Eb", ALU), op("div Eb", ALU), op("idiv Eb", ALU)
        };
        GROUPS[0xF7] = new Op[] {
            op("test Ev,Iz", ALU), null, op("not Ev", ALU), op("neg Ev", ALU),
            op("mul Ev", ALU), op("imul Ev", ALU), op("div Ev", ALU), op("idiv Ev", ALU)
        };
        GROUPS[0xFE] = new Op[] {op("inc Eb", ALU), op("dec Eb", ALU), null, null, null, null, null, null};
        GROUPS[0xFF] = new Op[] {
            op("inc Ev", ALU), op("dec Ev", ALU), op("f64:call Eq", INDIRECT_CALL), op("call/call/lcall M", INDIRECT_CALL),
            op("f64:jmp Eq", INDIRECT_BRANCH), op("jmp/jmp/ljmp M", INDIRECT_BRANCH), op("d64:push Ev", STORE), null
        };

        // Two-byte opcodes (0F xx) that aren't in the SSE table.
        TWO_BYTE[0x02] = op("lar Gv,Ew", SYSTEM);
        TWO_BYTE[0x03] = op("lsl Gv,Ew", SYSTEM);
        TWO_BYTE[0x05] = op("syscall", SYSTEM);
        TWO_BYTE[0x06] = op("clts", SYSTEM);
        TWO_BYTE[0x07] = op("sysret/sysret/sysretq", SYSTEM);
        TWO_BYTE[0x08] = op("invd", SYSTEM);
        TWO_BYTE[0x0B] = op("ud2", EXCEPTION);
        TWO_BYTE[0x30] = op("wrmsr", SYSTEM);
        TWO_BYTE[0x31] = op("rdtsc", SYSTEM);
        TWO_BYTE[0x32] = op("rdmsr", SYSTEM);
        TWO_BYTE[0x33] = op("rdpmc", SYSTEM);
        TWO_BYTE[0x34] = op("sysenter", SYSTEM);
        TWO_BYTE[0x35] = op("sysexit/sysexit/sysexitq", SYSTEM);
        TWO_BYTE[0x0E] = op("femms", SIMD);
        TWO_BYTE[0x37] = op("getsec", SYSTEM);
        for (int i = 0; i < 16; i++) {
            TWO_BYTE[0x40 + i] = op("cmov" + CONDITIONS[i] + " Gv,Ev", ALU);
            TWO_BYTE[0x80 + i] = op("f64:j" + CONDITIONS[i] + " Jz", CONDITIONAL_BRANCH);
            TWO_BYTE[0x90 + i] = op("set" + CONDITIONS[i] + " Eb", ALU);
        }
        TWO_BYTE[0xA0] = op("d64:push fs", STORE);
        TWO_BYTE[0xA1] = op("d64:pop fs", LOAD);
        TWO_BYTE[0xA2] = op("cpuid", SYSTEM);
        TWO_BYTE[0xA3] = op("bt Ev,Gv", ALU);
        TWO_BYTE[0xA4] = op("shld Ev,Gv,Iu", ALU);
        TWO_BYTE[0xA5] = op("shld Ev,Gv,CL", ALU);
        TWO_BYTE[0xA8] = op("d64:push gs", STORE);
        TWO_BYTE[0xA9] = op("d64:pop gs", LOAD);
        TWO_BYTE[0xAA] = op("rsm", SYSTEM);
        TWO_BYTE[0xAB] = op("bts Ev,Gv", ALU);
        TWO_BYTE[0xAC] = op("shrd Ev,Gv,Iu", ALU);
        TWO_BYTE[0xAD] = op("shrd Ev,Gv,CL", ALU);
        TWO_BYTE[0xAF] = op("imul Gv,Ev", ALU);
        TWO_BYTE[0xB0] = op("cmpxchg Eb,Gb", ATOMIC);
        TWO_BYTE[0xB1] = op("cmpxchg Ev,Gv", ATOMIC);
        TWO_BYTE[0xB2] = op("lss Gv,M", LOAD);
        TWO_BYTE[0xB3] = op("btr Ev,Gv", ALU);
        TWO_BYTE[0xB4] = op("lfs Gv,M", LOAD);
        TWO_BYTE[0xB5] = op("lgs Gv,M", LOAD);
        TWO_BYTE[0xB6] = op("movzx Gv,Eb", MOVE);
        TWO_BYTE[0xB7] = op("movzx Gv,Ew", MOVE);
        TWO_BYTE[0xB9] = op("ud1 Gv,Ev", EXCEPTION);
        TWO_BYTE[0xBB] = op("btc Ev,Gv", ALU);
        TWO_BYTE[0xBC] = op("bsf Gv,Ev", ALU);
        TWO_BYTE[0xBD] = op("bsr Gv,Ev", ALU);
        TWO_BYTE[0xBE] = op("movsx Gv,Eb", MOVE);
        TWO_BYTE[0xBF] = op("movsx Gv,Ew", MOVE);
        TWO_BYTE[0xC0] = op("xadd Eb,Gb", ATOMIC);
        TWO_BYTE[0xC1] = op("xadd Ev,Gv", ATOMIC);
        TWO_BYTE[0xC3] = op("movnti My,Gy", STORE);
        for (int i = 0; i < 8; i++) {
            TWO_BYTE[0xC8 + i] = op("bswap Zv", ALU);
        }
        TWO_BYTE_GROUPS[0x00] = new Op[] {
            op("sldt Es", SYSTEM), op("str Es", SYSTEM), op("lldt Ew", SYSTEM), op("ltr Ew", SYSTEM),
            op("verr Ew", SYSTEM), op("verw Ew", SYSTEM), null, null
        };
        TWO_BYTE_GROUPS[0xBA] = new Op[] {
            null, null, null, null, op("bt Ev,Iu", ALU), op("bts Ev,Iu", ALU), op("btr Ev,Iu", ALU), op("btc Ev,Iu", ALU)
        };
        TWO_BYTE_GROUPS[0x18] = new Op[] {
            op("prefetchnta Mb", PREFETCH), op("prefetcht0 Mb", PREFETCH), op("prefetcht1 Mb", PREFETCH),
            op("prefetcht2 Mb", PREFETCH), op("prefetchrst2 Mb", PREFETCH), null, op("prefetchit1 Mb", PREFETCH),
            op("prefetchit0 Mb", PREFETCH)
        };
        TWO_BYTE_GROUPS[0x0D] = new Op[] {
            op("prefetch Mb", PREFETCH), op("prefetchw Mb", PREFETCH), op("prefetchwt1 Mb", PREFETCH),
            null, null, null, null, null
        };
    }

    private static Op[] aluGroup(String operands) {
        String[] alu = {"add", "or", "adc", "sbb", "and", "sub", "xor", "cmp"};
        Op[] group = new Op[8];
        for (int i = 0; i < 8; i++) {
            group[i] = op(alu[i] + " " + operands, ALU);
        }
        return group;
    }

    private static Op[] shiftGroup(String[] names, String operands) {
        Op[] group = new Op[8];
        for (int i = 0; i < 8; i++) {
            group[i] = names[i] == null ? null : op(names[i] + " " + operands, ALU);
        }
        return group;
    }

    // x87: memory forms by opcode (D8-DF) and ModRM.reg, as "mnemonic size".
    private static final String[][] X87_MEMORY = {
        {"fadd d", "fmul d", "fcom d", "fcomp d", "fsub d", "fsubr d", "fdiv d", "fdivr d"},
        {"fld d", null, "fst d", "fstp d", "fldenv -", "fldcw w", "fnstenv -", "fnstcw w"},
        {"fiadd d", "fimul d", "ficom d", "ficomp d", "fisub d", "fisubr d", "fidiv d", "fidivr d"},
        {"fild d", "fisttp d", "fist d", "fistp d", null, "fld t", null, "fstp t"},
        {"fadd q", "fmul q", "fcom q", "fcomp q", "fsub q", "fsubr q", "fdiv q", "fdivr q"},
        {"fld q", "fisttp q", "fst q", "fstp q", "frstor -", null, "fnsave -", "fnstsw w"},
        {"fiadd w", "fimul w", "ficom w", "ficomp w", "fisub w", "fisubr w", "fidiv w", "fidivr w"},
        {"fild w", "fisttp w", "fist w", "fistp w", "fbld t", "fild q", "fbstp t", "fistp q"},
    };

    // x87 register forms by opcode and ModRM.reg; null where LLVM decodes nothing.
    private static final String[][] X87_REGISTER = {
        {"fadd", "fmul", "fcom", "fcomp", "fsub", "fsubr", "fdiv", "fdivr"},
        {"fld", "fxch", null, null, null, null, null, null},
        {"fcmovb", "fcmove", "fcmovbe", "fcmovu", null, null, null, null},
        {"fcmovnb", "fcmovne", "fcmovnbe", "fcmovnu", null, "fucomi", "fcomi", null},
        {"fadd", "fmul", null, null, "fsubr", "fsub", "fdivr", "fdiv"},
        {"ffree", null, "fst", "fstp", "fucom", "fucomp", null, null},
        {"faddp", "fmulp", null, null, "fsubrp", "fsubp", "fdivrp", "fdivp"},
        {"ffreep", null, null, null, null, "fucompi", "fcompi", null},
    };
    // D9 with ModRM.reg 2 to 7, by (reg - 2) * 8 + rm.
    private static final String[] X87_D9 = {
        "fnop", null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null,
        "fchs", "fabs", null, null, "ftst", "fxam", null, null,
        "fld1", "fldl2t", "fldl2e", "fldpi", "fldlg2", "fldln2", "fldz", null,
        "f2xm1", "fyl2x", "fptan", "fpatan", "fxtract", "fprem1", "fdecstp", "fincstp",
        "fprem", "fyl2xp1", "fsqrt", "fsincos", "frndint", "fscale", "fsin", "fcos"
    };

    @Override
    public Instruction decode(byte[] code, int offset, long address) {
        if (offset >= code.length) {
            return new Instruction(address, 0, ".byte", "", UNKNOWN, OptionalLong.empty());
        }
        try {
            if ((code[offset] & 0xFF) == 0xF0 && offset + 1 < code.length) {
                // A leading lock prefix applies to whatever follows it, decoded on its own
                // (LLVM decodes it as a separate instruction).
                Instruction rest = decode(code, offset + 1, address + 1);
                if (rest.isDecoded() && rest.length() < MAX_LENGTH) {
                    String mnemonic = rest.mnemonic().startsWith("lock ") ? rest.mnemonic() : "lock " + rest.mnemonic();
                    return new Instruction(address, rest.length() + 1, mnemonic, rest.operands(), ATOMIC, rest.branchTarget());
                }
                throw INVALID;
            }
            return new Decoding(code, offset, address).decode();
        } catch (Invalid | ArrayIndexOutOfBoundsException e) {
            return new Instruction(address, 1, ".byte", String.format("0x%02x", code[offset] & 0xFF), UNKNOWN,
                    OptionalLong.empty());
        }
    }

    /** The state of decoding one instruction. */
    private static final class Decoding {
        final byte[] code;
        final int start;
        final long address;
        int pos;

        // Legacy prefixes.
        boolean lock, operandSize, addressSize;
        int repeat; // 0, 0xF2 or 0xF3: the last one seen
        int segment = -1;
        // REX, or the equivalent bits of VEX and EVEX.
        boolean rex, w, r, x, b;
        // VEX and EVEX.
        int vvvv, vectorLength, aaa;
        boolean rPrime, vPrime, zero, evexB;
        // ModRM and the memory operand.
        boolean hasModrm;
        int mod, reg, rm;
        int base = -1, index = -1, scale = 1;
        boolean sib, ripRelative, vsibIndex;
        long displacement;
        boolean displacement8;
        boolean evex;
        // Whether the address size prefix applied to something; LLVM prints it as addr32 otherwise.
        boolean addressSizeUsed;
        boolean twoByte, stringInstruction, xchgNop, rexAfterOperandSize, tileMemory;
        // LLVM's "mandatory prefix": the F2/F3 (or else 66) immediately before 0F or REX.
        int mandatory;

        Decoding(byte[] code, int offset, long address) {
            this.code = code;
            this.start = offset;
            this.address = address;
            this.pos = offset;
        }

        int next() {
            if (pos - start >= MAX_LENGTH) {
                throw INVALID;
            }
            return code[pos++] & 0xFF;
        }

        long nextSigned(int bytes) {
            long value = 0;
            for (int i = 0; i < bytes; i++) {
                value |= (long) next() << (8 * i);
            }
            int shift = 64 - 8 * bytes;
            return shift == 0 ? value : value << shift >> shift;
        }

        Instruction decode() {
            int opcode;
            while (true) {
                opcode = next();
                switch (opcode) {
                    case 0xF0 -> lock = true;
                    case 0xF2, 0xF3 -> {
                        int following = code[pos] & 0xFF;
                        repeat = opcode;
                        if (following == 0x0F || (following & 0xF0) == 0x40 || following == 0x66) {
                            mandatory = opcode;
                        }
                    }
                    case 0x2E -> segment = 1;
                    case 0x36 -> segment = 2;
                    case 0x3E -> segment = 3;
                    case 0x26 -> segment = 0;
                    case 0x64 -> segment = 4;
                    case 0x65 -> segment = 5;
                    case 0x66 -> {
                        int following = code[pos] & 0xFF;
                        operandSize = true;
                        if (mandatory == 0 && (following == 0x0F || (following & 0xF0) == 0x40)) {
                            mandatory = 0x66;
                        }
                    }
                    case 0x67 -> addressSize = true;
                    default -> {
                        if ((opcode & 0xF0) == 0x40) {
                            rex = true;
                            w = (opcode & 8) != 0;
                            r = (opcode & 4) != 0;
                            x = (opcode & 2) != 0;
                            b = (opcode & 1) != 0;
                            opcode = next();
                            if ((opcode & 0xF0) == 0x40 || isLegacyPrefix(opcode)) {
                                // REX must come last; anything before it is ignored.
                                pos--;
                                rex = w = r = x = b = false;
                                continue;
                            }
                            // In the 0F maps, LLVM ignores a 66 prefix that isn't right before REX.
                            rexAfterOperandSize = operandSize && mandatory != 0x66;
                        }
                        return opcode(opcode);
                    }
                }
            }
        }

        private static boolean isLegacyPrefix(int value) {
            return switch (value) {
                case 0xF0, 0xF2, 0xF3, 0x2E, 0x36, 0x3E, 0x26, 0x64, 0x65, 0x66, 0x67 -> true;
                default -> false;
            };
        }

        Instruction opcode(int opcode) {
            switch (opcode) {
                case 0x0F:
                    return twoByte(next());
                case 0xC4, 0xC5:
                    return vex(opcode);
                case 0x62:
                    return evex();
                case 0x90:
                    xchgNop = true;
                    if (b || (w && repeat == 0xF3)) {
                        return general(op("xchg rA,Zv", ATOMIC), opcode);
                    }
                    if (repeat == 0xF3) {
                        if (operandSize) {
                            throw INVALID;
                        }
                        repeat = 0;
                        return finish("pause", "", SYSTEM, -1);
                    }
                    return finish("nop", "", SYSTEM, -1);
                case 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97:
                    return general(op("xchg rA,Zv", ATOMIC), opcode);
                case 0x8F: {
                    readModrm();
                    if (reg != 0) {
                        throw INVALID;
                    }
                    return general(GROUPS[0x8F][0], opcode);
                }
                case 0xC6, 0xC7: {
                    int modrm = code[pos] & 0xFF;
                    if (modrm == 0xF8) {
                        next();
                        if (opcode == 0xC6) {
                            return finish("xabort", imm((byte) next(), true), SYSTEM, -1);
                        }
                        int length = operandSize && !w ? 2 : 4;
                        long rel = nextSigned(length);
                        return branch("xbegin", rel, SYSTEM);
                    }
                    break;
                }
                default:
                    break;
            }
            if (opcode >= 0xD8 && opcode <= 0xDF) {
                return x87(opcode);
            }
            Op[] group = GROUPS[opcode];
            if (group != null) {
                readModrm();
                Op op = group[reg];
                if (op == null) {
                    throw INVALID;
                }
                return general(op, opcode);
            }
            Op op = ONE_BYTE[opcode];
            if (op == null) {
                throw INVALID;
            }
            return general(op, opcode);
        }

        Instruction twoByte(int opcode) {
            twoByte = true;
            switch (opcode) {
                case 0x38:
                    return table(OpcodeTable.LEGACY, 2, next());
                case 0x3A:
                    return table(OpcodeTable.LEGACY, 3, next());
                case 0x01:
                    return group7();
                case 0x1E:
                    if (repeat == 0xF3 && (code[pos] & 0xFF) == 0xFA) {
                        next();
                        return finish("endbr64", "", SYSTEM, -1);
                    }
                    if (repeat == 0xF3 && (code[pos] & 0xFF) == 0xFB) {
                        next();
                        return finish("endbr32", "", SYSTEM, -1);
                    }
                    if (repeat == 0xF3 && w && (code[pos] & 0xFF) == 0xF8) {
                        next();
                        return finish("ibhf", "", SYSTEM, -1);
                    }
                    if (repeat == 0xF3) {
                        readModrm();
                        if (mod == 3 && reg == 1) {
                            return finish(w ? "rdsspq" : "rdsspd", (w ? GPR64 : GPR32)[rm | (b ? 8 : 0)], SYSTEM, -1);
                        }
                    }
                    throw INVALID;
                case 0x18: {
                    readModrm();
                    if (mod == 3 || TWO_BYTE_GROUPS[0x18][reg] == null) {
                        throw INVALID;
                    }
                    return general(TWO_BYTE_GROUPS[0x18][reg], opcode);
                }
                case 0x09:
                    // LLVM: 66 and F2 make it invalid, except with REX.W; F3 selects wbnoinvd.
                    if (!w && (operandSize || repeat == 0xF2)) {
                        throw INVALID;
                    }
                    return finish(repeat == 0xF3 ? "wbnoinvd" : "wbinvd", "", SYSTEM, -1);
                case 0x1C: {
                    readModrm();
                    if (mod == 3 || reg != 0) {
                        throw INVALID;
                    }
                    return general(op("cldemote Mb", SYSTEM), opcode);
                }
                case 0x1F:
                    return hintNop();
                case 0x19, 0x1A, 0x1B, 0x1D:
                    throw INVALID;
                case 0x0D: {
                    readModrm();
                    if (mod == 3) {
                        throw INVALID;
                    }
                    Op prefetch = TWO_BYTE_GROUPS[0x0D][reg];
                    if (prefetch == null) {
                        throw INVALID;
                    }
                    return general(prefetch, opcode);
                }
                case 0x20, 0x21, 0x22, 0x23: {
                    readModrm();
                    if (mod != 3) {
                        throw INVALID;
                    }
                    String special = (opcode & 1) == 0 ? "cr" + (reg | (r ? 8 : 0)) : "dr" + (reg | (r ? 8 : 0));
                    String gpr = GPR64[rm | (b ? 8 : 0)];
                    String operands = opcode < 0x22 ? gpr + ", " + special : special + ", " + gpr;
                    return finish("mov", operands, SYSTEM, -1);
                }
                case 0xAE:
                    return group15();
                case 0xB8:
                    if (repeat == 0xF3) {
                        return general(op("popcnt Gv,Ev", ALU), opcode);
                    }
                    throw INVALID;
                case 0xBC, 0xBD:
                    if (repeat == 0xF3) {
                        return general(op((opcode == 0xBC ? "tzcnt" : "lzcnt") + " Gv,Ev", ALU), opcode);
                    }
                    break;
                case 0xC7:
                    return group9();
                default:
                    break;
            }
            Op[] group = TWO_BYTE_GROUPS[opcode];
            if (group != null) {
                readModrm();
                if (opcode == 0x00 && reg == 6 && repeat == 0xF2) {
                    return finish("lkgs", mod != 3 ? memory("w") : GPR16[rm | (b ? 8 : 0)], SYSTEM, -1);
                }
                Op op = group[reg];
                if (op == null) {
                    throw INVALID;
                }
                return general(op, opcode);
            }
            Op op = TWO_BYTE[opcode];
            if (op != null) {
                return general(op, opcode);
            }
            return table(OpcodeTable.LEGACY, 1, opcode);
        }

        Instruction hintNop() {
            return general(op("nop Ev", SYSTEM), 0);
        }

        Instruction group7() {
            readModrm();
            if (mod != 3) {
                if (repeat == 0xF3 && reg == 5) {
                    return finish("rstorssp", memory("d"), SYSTEM, -1);
                }
                String[] names = {"sgdt", "sidt", "lgdt", "lidt", "smsw", null, "lmsw", "invlpg"};
                String name = names[reg];
                if (name == null) {
                    throw INVALID;
                }
                String operand = reg == 4 || reg == 6 ? memory("w") : reg == 7 ? memory("b") : memory("-");
                return finish(name, operand, SYSTEM, -1);
            }
            int modrm = 0xC0 | reg << 3 | rm;
            String prefixed = repeat == 0xF3 ? switch (modrm) {
                case 0xC6 -> "wrmsrlist";
                case 0xCA -> "eretu";
                case 0xE8 -> "setssbsy";
                case 0xEA -> "saveprevssp";
                case 0xEC -> "uiret";
                case 0xED -> "testui";
                case 0xEE -> "clui";
                case 0xEF -> "stui";
                case 0xFD -> "rmpquery";
                case 0xFE -> "rmpadjust";
                case 0xFF -> "psmash";
                default -> null;
            } : repeat == 0xF2 ? switch (modrm) {
                case 0xC6 -> "rdmsrlist";
                case 0xCA -> "erets";
                case 0xE8 -> "xsusldtrk";
                case 0xE9 -> "xresldtrk";
                case 0xFE -> "rmpupdate";
                case 0xFF -> "pvalidate";
                default -> null;
            } : operandSize ? switch (modrm) {
                case 0xCC -> "tdcall";
                case 0xCD -> "seamret";
                case 0xCE -> "seamops";
                case 0xCF -> "seamcall";
                default -> null;
            } : null;
            if (prefixed != null) {
                return finish(prefixed, "", SYSTEM, -1);
            }
            String name = switch (modrm) {
                case 0xC1 -> "vmcall";
                case 0xC2 -> "vmlaunch";
                case 0xC3 -> "vmresume";
                case 0xC4 -> "vmxoff";
                case 0xC8 -> "monitor";
                case 0xC9 -> "mwait";
                case 0xCA -> "clac";
                case 0xCB -> "stac";
                case 0xD0 -> "xgetbv";
                case 0xD1 -> "xsetbv";
                case 0xD4 -> "vmfunc";
                case 0xD5 -> "xend";
                case 0xD6 -> "xtest";
                case 0xE8 -> "serialize";
                case 0xEE -> "rdpkru";
                case 0xEF -> "wrpkru";
                case 0xF8 -> "swapgs";
                case 0xF9 -> "rdtscp";
                case 0xFD -> "rdpru";
                case 0xC0 -> "enclv";
                case 0xC5 -> "pconfig";
                case 0xC6 -> "wrmsrns";
                case 0xC7 -> "pbndkb";
                case 0xCF -> "encls";
                case 0xD7 -> "enclu";
                case 0xD8 -> "vmrun";
                case 0xD9 -> "vmmcall";
                case 0xDA -> "vmload";
                case 0xDB -> "vmsave";
                case 0xDC -> "stgi";
                case 0xDD -> "clgi";
                case 0xDE -> "skinit";
                case 0xDF -> "invlpga";
                case 0xFA -> "monitorx";
                case 0xFB -> "mwaitx";
                case 0xFC -> "clzero";
                case 0xFE -> "invlpgb";
                case 0xFF -> "tlbsync";
                default -> null;
            };
            if (name == null) {
                if (reg == 4) {
                    return general(op("smsw Ev", SYSTEM), 0);
                }
                if (reg == 6) {
                    return general(op("lmsw Ew", SYSTEM), 0);
                }
                throw INVALID;
            }
            return finish(name, "", SYSTEM, -1);
        }

        Instruction group15() {
            readModrm();
            if (mod != 3) {
                if (repeat == 0xF3 && reg == 4) {
                    return finish("ptwrite", memory(w ? "q" : "d"), SYSTEM, -1);
                }
                if (repeat == 0xF3 && reg == 6 && !w) {
                    return finish("clrssbsy", memory("d"), SYSTEM, -1);
                }
                String[] names = w
                        ? new String[] {"fxsave64", "fxrstor64", "ldmxcsr", "stmxcsr", "xsave64", "xrstor64", "xsaveopt64", "clflush"}
                        : new String[] {"fxsave", "fxrstor", "ldmxcsr", "stmxcsr", "xsave", "xrstor", "xsaveopt", "clflush"};
                String name = names[reg];
                if (operandSize && !w && reg == 6) {
                    name = "clwb";
                } else if (operandSize && reg == 7) {
                    name = "clflushopt";
                }
                String size = reg == 2 || reg == 3 ? "d" : reg == 7 || name.equals("clwb") ? "b" : "-";
                InstructionKind kind = reg == 3 ? STORE : reg == 2 ? LOAD : SYSTEM;
                return finish(name, memory(size), kind, -1);
            }
            if (repeat == 0xF3 && reg == 4) {
                return finish("ptwrite", (w ? GPR64 : GPR32)[rm | (b ? 8 : 0)], SYSTEM, -1);
            }
            if (repeat == 0xF3 && reg == 5 && !operandSize) {
                return finish(w ? "incsspq" : "incsspd", (w ? GPR64 : GPR32)[rm | (b ? 8 : 0)], SYSTEM, -1);
            }
            if (reg == 6 && (repeat != 0 || operandSize)) {
                if (repeat == 0xF3) {
                    return finish("umonitor", (addressSize ? GPR32 : GPR64)[rm | (b ? 8 : 0)], SYSTEM, -1);
                }
                return finish(repeat == 0xF2 ? "umwait" : "tpause", GPR32[rm | (b ? 8 : 0)], SYSTEM, -1);
            }
            if (repeat == 0xF3 && reg < 4) {
                String[] names = {"rdfsbase", "rdgsbase", "wrfsbase", "wrgsbase"};
                return finish(names[reg], (w ? GPR64 : GPR32)[rm | (b ? 8 : 0)], SYSTEM, -1);
            }
            return switch (reg) {
                case 5 -> finish("lfence", "", SYSTEM, -1);
                case 6 -> finish("mfence", "", SYSTEM, -1);
                case 7 -> finish("sfence", "", SYSTEM, -1);
                default -> throw INVALID;
            };
        }

        Instruction group9() {
            readModrm();
            if (mod != 3) {
                switch (reg) {
                    case 1:
                        return finish(w ? "cmpxchg16b" : "cmpxchg8b", memory(w ? "x" : "q"), ATOMIC, -1);
                    case 3:
                        return finish(w ? "xrstors64" : "xrstors", memory("-"), SYSTEM, -1);
                    case 4:
                        return finish(w ? "xsavec64" : "xsavec", memory("-"), SYSTEM, -1);
                    case 5:
                        return finish(w ? "xsaves64" : "xsaves", memory("-"), SYSTEM, -1);
                    case 6:
                        return finish(repeat == 0xF3 ? "vmxon" : operandSize ? "vmclear" : "vmptrld", memory("q"), SYSTEM, -1);
                    case 7:
                        return finish("vmptrst", memory("q"), SYSTEM, -1);
                    default:
                        throw INVALID;
                }
            }
            if (reg == 6 && repeat == 0xF3) {
                return finish("senduipi", GPR64[rm | (b ? 8 : 0)], SYSTEM, -1);
            }
            if (reg == 6 || reg == 7) {
                String name = reg == 6 ? "rdrand" : repeat == 0xF3 ? "rdpid" : "rdseed";
                if (name.equals("rdpid")) {
                    return finish(name, GPR64[rm | (b ? 8 : 0)], SYSTEM, -1);
                }
                int size = w ? 64 : operandSize ? 16 : 32;
                return finish(name, gpr(rm | (b ? 8 : 0), size), SYSTEM, -1);
            }
            throw INVALID;
        }

        // ---------------------------------------------------------------- x87

        Instruction x87(int opcode) {
            readModrm();
            int row = opcode - 0xD8;
            if (mod != 3) {
                String spec = X87_MEMORY[row][reg];
                if (spec == null) {
                    throw INVALID;
                }
                int space = spec.indexOf(' ');
                String size = spec.substring(space + 1);
                String name = spec.substring(0, space);
                InstructionKind kind = name.equals("fnstcw") || name.equals("fnstsw") || name.equals("fldcw")
                        || name.equals("fldenv") || name.equals("fnstenv") || name.equals("frstor") || name.equals("fnsave")
                        ? SYSTEM : FLOATING_POINT;
                return finish(name, memory(size), kind, -1);
            }
            String st = "st(" + rm + ")";
            String name = X87_REGISTER[row][reg];
            String operands;
            switch (row) {
                case 0 -> operands = reg == 2 || reg == 3 ? st : "st, " + st;
                case 1 -> {
                    if (reg >= 2) {
                        name = X87_D9[(reg - 2) * 8 + rm];
                        operands = "";
                    } else {
                        operands = st;
                    }
                }
                case 2 -> {
                    if (reg == 5) {
                        name = rm == 1 ? "fucompp" : null;
                        operands = "";
                    } else {
                        operands = "st, " + st;
                    }
                }
                case 3 -> {
                    if (reg == 4) {
                        name = rm == 2 ? "fnclex" : rm == 3 ? "fninit" : null;
                        operands = "";
                    } else {
                        operands = "st, " + st;
                    }
                }
                case 4, 6 -> {
                    if (row == 6 && reg == 3) {
                        name = rm == 1 ? "fcompp" : null;
                        operands = "";
                    } else {
                        operands = st + ", st";
                    }
                }
                case 5 -> operands = st;
                default -> {
                    if (reg == 4) {
                        name = rm == 0 ? "fnstsw" : null;
                        operands = "ax";
                    } else {
                        operands = reg == 0 ? st : "st, " + st;
                    }
                }
            }
            if (name == null) {
                throw INVALID;
            }
            return finish(name, operands, FLOATING_POINT, -1);
        }

        // ---------------------------------------------------------------- general purpose

        int operandSize(Op op) {
            if (op.f64()) {
                return 64;
            }
            if (op.d64()) {
                return w || !operandSize ? 64 : 16;
            }
            return w ? 64 : operandSize ? 16 : 32;
        }

        Instruction general(Op op, int opcode) {
            int size = operandSize(op);
            if ((opcode == 0x86 || opcode == 0x87) && op.mnemonic(size).equals("xchg")) {
                readModrm();
                if (mod == 3) {
                    op = new Op(op.mnemonics(), new String[] {op.operands()[1], op.operands()[0]}, false, false, op.kind());
                }
            }
            boolean needsModrm = false;
            for (String operand : op.operands()) {
                char c = operand.charAt(0);
                if ((c == 'E' || c == 'G' || c == 'M' || c == 'S' || c == 'C' || c == 'D') && !operand.equals("DX")) {
                    needsModrm = true;
                }
            }
            if (needsModrm && !hasModrm) {
                readModrm();
            }
            StringBuilder text = new StringBuilder();
            long target = -1;
            boolean hasTarget = false;
            boolean memoryDestination = false, memorySource = false;
            for (int i = 0; i < op.operands().length; i++) {
                String operand = op.operands()[i];
                String formatted;
                switch (operand) {
                    case "Ib" -> formatted = imm(nextSigned(1), true);
                    case "Iu" -> formatted = imm(next(), false);
                    case "Iw" -> formatted = imm(nextSigned(2) & 0xFFFF, false);
                    case "Is" -> formatted = imm(nextSigned(2), true);
                    case "Ir" -> formatted = operandSize && !w ? imm(nextSigned(2) & 0xFFFF, false) : imm(nextSigned(2), true);
                    case "Iz" -> formatted = size == 16 ? imm(nextSigned(2) & 0xFFFF, false)
                            : size == 32 ? imm(nextSigned(4) & 0xFFFFFFFFL, false) : imm(nextSigned(4), true);
                    case "Io" -> formatted = size == 16 ? imm(nextSigned(2) & 0xFFFF, false)
                            : size == 32 ? imm(nextSigned(4) & 0xFFFFFFFFL, false) : imm(nextSigned(8), true);
                    case "Jb", "Jz" -> {
                        // LLVM: 66 shortens jmp and two-byte jcc, but not call; REX.W undoes it for jmp.
                        boolean rel16 = operand.equals("Jz") && operandSize
                                && (twoByte ? mandatory != 0xF2 && mandatory != 0xF3 && !rexAfterOperandSize : opcode == 0xE9 && !w);
                        long rel = nextSigned(operand.equals("Jb") ? 1 : rel16 ? 2 : 4);
                        target = address + (pos - start) + rel;
                        hasTarget = true;
                        formatted = "0x" + Long.toHexString(target);
                    }
                    case "AL" -> formatted = "al";
                    case "CL" -> formatted = "cl";
                    case "DX" -> formatted = "dx";
                    case "rA" -> formatted = gpr(0, size);
                    case "eA" -> formatted = operandSize && !w ? "ax" : "eax";
                    case "fs", "gs" -> formatted = operand;
                    case "Sw" -> {
                        if (reg >= 6) {
                            throw INVALID;
                        }
                        formatted = SEGMENTS[reg];
                    }
                    case "Ob", "Ov" -> {
                        addressSizeUsed = true;
                        boolean byteForm = (opcode & 1) == 0;
                        if (addressSize && !(byteForm && (w || operandSize))) {
                            op = new Op(new String[] {"mov"}, op.operands(), false, false, op.kind());
                        } else if (addressSize) {
                            addressSizeUsed = false;
                            long value = nextSigned(4) & 0xFFFFFFFFL;
                            String sz = operand.equals("Ob") ? "b" : sizeLetter(size);
                            formatted = sizeName(sz) + segmentPrefix() + "[" + imm(value, true) + "]";
                            break;
                        }
                        long value = addressSize ? nextSigned(4) & 0xFFFFFFFFL : nextSigned(8);
                        String sz = operand.equals("Ob") ? "b" : sizeLetter(size);
                        formatted = sizeName(sz) + segmentPrefix() + "[" + imm(value, true) + "]";
                    }
                    case "Xb", "Xv", "Xz" -> formatted = stringOperand(operand, size, false);
                    case "Yb", "Yv", "Yz" -> formatted = stringOperand(operand, size, true);
                    default -> {
                        char kind = operand.charAt(0);
                        char sz = operand.length() > 1 ? operand.charAt(1) : '-';
                        int bits = switch (sz) {
                            case 'b' -> 8;
                            case 'w' -> 16;
                            case 'd' -> 32;
                            case 'q' -> 64;
                            case 'v' -> size;
                            case 'y' -> w ? 64 : 32;
                            case 's' -> mod == 3 ? size : 16;
                            default -> 0;
                        };
                        switch (kind) {
                            case 'E' -> {
                                if (mod == 3) {
                                    formatted = gpr(rm | (b ? 8 : 0), bits);
                                } else {
                                    formatted = memory(sizeLetter(bits));
                                    if (i == 0) {
                                        memoryDestination = true;
                                    } else {
                                        memorySource = true;
                                    }
                                }
                            }
                            case 'M' -> {
                                if (mod == 3) {
                                    throw INVALID;
                                }
                                String letter = bits != 0 ? sizeLetter(bits) : String.valueOf(sz);
                                formatted = memory(letter);
                                if (i == 0) {
                                    memoryDestination = true;
                                } else {
                                    memorySource = true;
                                }
                            }
                            case 'G' -> formatted = gpr(reg | (r ? 8 : 0), bits);
                            case 'Z' -> formatted = gpr((opcode & 7) | (b ? 8 : 0), bits);
                            default -> throw new IllegalStateException(operand);
                        }
                    }
                }
                if (i > 0) {
                    text.append(", ");
                }
                text.append(formatted);
            }
            InstructionKind kind = op.kind();
            if (kind == null) {
                kind = memoryDestination ? STORE : memorySource ? LOAD : ALU;
            }
            String mnemonic = op.mnemonic(size);
            if (op.mnemonics().length == 3 && op.mnemonics()[2].startsWith("l") && op.mnemonics()[0].equals(op.mnemonics()[1])) {
                // Far call and jump: LLVM writes lcall/ljmp only with an operand size prefix or REX.W.
                mnemonic = operandSize || w ? op.mnemonics()[2] : op.mnemonics()[0];
            }
            if (mnemonic.equals("jrcxz") && addressSize && !w && !operandSize) {
                mnemonic = "jecxz";
                addressSizeUsed = true;
            }
            if (hasTarget) {
                return finishBranch(mnemonic, text.toString(), kind, target);
            }
            return finish(mnemonic, text.toString(), kind, -1);
        }

        String stringOperand(String operand, int size, boolean destination) {
            stringInstruction = true;
            String sz = operand.charAt(1) == 'b' ? "b" : operand.charAt(1) == 'z' ? sizeLetter(Math.min(size, 32))
                    : sizeLetter(size);
            addressSizeUsed = true;
            String register = destination ? (addressSize ? "edi" : "rdi") : (addressSize ? "esi" : "rsi");
            String seg = destination ? "es:" : segmentPrefix();
            return sizeName(sz) + seg + "[" + register + "]";
        }

        // ---------------------------------------------------------------- VEX, EVEX and SSE

        Instruction vex(int prefix) {
            // LLVM ignores legacy prefixes and REX before VEX and EVEX (the processor faults).
            int map, pp;
            int p1 = next();
            x = b = w = false;
            twoByte = true;
            if (prefix == 0xC5) {
                r = (p1 & 0x80) == 0;
                vvvv = (~p1 >> 3) & 15;
                vectorLength = (p1 >> 2) & 1;
                pp = p1 & 3;
                map = 1;
            } else {
                r = (p1 & 0x80) == 0;
                x = (p1 & 0x40) == 0;
                b = (p1 & 0x20) == 0;
                map = p1 & 0x1F;
                int p2 = next();
                w = (p2 & 0x80) != 0;
                vvvv = (~p2 >> 3) & 15;
                vectorLength = (p2 >> 2) & 1;
                pp = p2 & 3;
            }
            return table(OpcodeTable.VEX, map, pp, next());
        }

        Instruction evex() {
            int p0 = next(), p1 = next(), p2 = next();
            if ((p0 & 0x08) != 0 || (p1 & 0x04) == 0) {
                throw INVALID;
            }
            evex = true;
            twoByte = true;
            r = (p0 & 0x80) == 0;
            x = (p0 & 0x40) == 0;
            b = (p0 & 0x20) == 0;
            rPrime = (p0 & 0x10) == 0;
            int map = p0 & 7;
            w = (p1 & 0x80) != 0;
            vvvv = (~p1 >> 3) & 15;
            int pp = p1 & 3;
            zero = (p2 & 0x80) != 0;
            vectorLength = (p2 >> 5) & 3;
            evexB = (p2 & 0x10) != 0;
            vPrime = (p2 & 0x08) == 0;
            aaa = p2 & 7;
            return table(OpcodeTable.EVEX, map, pp, next());
        }

        Instruction table(int encoding, int map, int opcode) {
            int pp = repeat == 0xF3 ? 2 : repeat == 0xF2 ? 3 : operandSize && !rexAfterOperandSize ? 1 : 0;
            return table(encoding, map, pp, opcode);
        }

        Instruction table(int encoding, int map, int pp, int opcode) {
            List<OpcodeTable.Entry> candidates = OpcodeTable.instance().candidates(encoding, map, pp, opcode);
            if (candidates.isEmpty()) {
                throw INVALID;
            }
            boolean noModrm = candidates.get(0).noModrm;
            if (!noModrm) {
                readModrm();
            }
            boolean memory = !noModrm && mod != 3;
            int vl = encoding == OpcodeTable.LEGACY ? 0 : vectorLength;
            if (vl == 3 && !(evexB && !memory)) {
                // Without embedded rounding, LLVM decodes L'L = 3 as 512 bits.
                vl = 2;
            }
            OpcodeTable.Entry entry = null;
            for (OpcodeTable.Entry e : candidates) {
                if (e.memory == memory && e.broadcast == evexB && (e.wSet & 1 << (w ? 1 : 0)) != 0
                        && (e.lSet & 1 << vl) != 0 && (noModrm || (e.regSet & 1 << reg) != 0) && masking(e)) {
                    entry = e;
                    break;
                }
            }
            if (entry == null) {
                throw INVALID;
            }
            boolean vsib = false;
            for (OpcodeTable.Operand operand : entry.operands) {
                if (operand.source().equals("A") && operand.details()[vl].length() > 1
                        && Character.isUpperCase(operand.details()[vl].charAt(1))) {
                    vsib = true;
                }
            }
            if (entry.noVvvv && (vvvv != 0 || (vPrime && !vsib))) {
                throw INVALID;
            }
            if (memory) {
                if ((vsib || entry.mnemonic.startsWith("tile")) && !sib) {
                    throw INVALID;
                }
            }
            if (entry.mnemonic.startsWith("vp2intersect") && (r || rPrime)) {
                throw INVALID; // the destination is a pair of mask registers
            }
            boolean tile = false;
            for (OpcodeTable.Operand operand : entry.operands) {
                if ("t".equals(operand.details()[vl])) {
                    tile = true;
                }
            }
            if (entry.mnemonic.equals("tilezero") && rm != 0) {
                throw INVALID; // LLVM requires the unused fields to be zero
            }
            tileMemory = tile;
            if (memory) {
                vsibIndex = vsib;
            }
            int imm1 = entry.immediateBytes > 0 ? next() : 0;
            int imm2 = entry.immediateBytes > 1 ? next() : 0;
            String mnemonic = entry.mnemonic;
            boolean dropImmediate = false;
            if (entry.immediateNames != null && entry.immediateNames[imm1] != null) {
                mnemonic = entry.immediateNames[imm1];
                dropImmediate = true;
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < entry.operands.length; i++) {
                OpcodeTable.Operand operand = entry.operands[i];
                String detail = operand.details()[vl];
                String source = operand.source();
                String formatted;
                switch (source) {
                    case "R" -> formatted = addressSizedRegister(entry, detail) != null ? addressSizedRegister(entry, detail)
                            : register(detail, reg | (r ? 8 : 0) | (rPrime ? 16 : 0));
                    case "M" -> formatted = register(detail, rm | (b ? 8 : 0) | (evex && x ? 16 : 0));
                    case "V" -> formatted = register(detail, vvvv | (vPrime ? 16 : 0));
                    case "S" -> formatted = register(detail, imm1 >> 4);
                    case "A" -> formatted = vectorMemory(detail);
                    case "I4" -> formatted = imm(imm1 & 15, false);
                    case "I" -> {
                        if (dropImmediate) {
                            continue;
                        }
                        formatted = detail.equals("s") ? imm((byte) imm1, true) : imm(imm1, false);
                    }
                    case "J" -> formatted = detail.equals("s") ? imm((byte) imm2, true) : imm(imm2, false);
                    case "RC" -> formatted = ROUNDING[vectorLength];
                    case "SAE" -> formatted = "{sae}";
                    case "KM" -> formatted = "{k" + aaa + "}";
                    default -> {
                        if (source.startsWith("=")) {
                            formatted = source.substring(1);
                        } else if (source.startsWith("C")) {
                            formatted = source.substring(1);
                        } else {
                            throw new IllegalStateException(source);
                        }
                    }
                }
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(formatted);
                if (i == 0 && encoding == OpcodeTable.EVEX && !source.equals("KM")) {
                    if (aaa != 0 || zero) {
                        text.append(" {k").append(aaa).append('}');
                    }
                    if (zero) {
                        text.append(" {z}");
                    }
                }
            }
            return finish(mnemonic, text.toString(), entry.kind, -1);
        }

        /** movdir64b and enqcmd name their destination register at the address size. */
        String addressSizedRegister(OpcodeTable.Entry entry, String detail) {
            if (addressSize && !w && detail.equals("q") && (entry.mnemonic.equals("movdir64b") || entry.mnemonic.startsWith("enqcmd"))) {
                return GPR32[reg | (r ? 8 : 0)];
            }
            return null;
        }

        boolean masking(OpcodeTable.Entry e) {
            if (!evex) {
                return true;
            }
            if (aaa == 0) {
                return zero ? e.zeroingUnmasked : e.unmasked;
            }
            return zero ? e.zeroing : e.merging;
        }

        String register(String detail, int number) {
            return switch (detail) {
                case "x" -> "xmm" + number;
                case "y" -> "ymm" + number;
                case "z" -> "zmm" + number;
                case "k" -> {
                    // LLVM ignores EVEX.R' and V' here but rejects the REX-style extension bit.
                    if ((number & 15) > 7) {
                        throw INVALID;
                    }
                    yield "k" + (number & 7);
                }
                case "m" -> "mm" + (number & 7);
                case "t" -> {
                    if (number > 7) {
                        throw INVALID;
                    }
                    yield "tmm" + (number & 7);
                }
                case "b" -> gpr(number & 15, 8);
                case "w" -> gpr(number & 15, 16);
                case "d" -> gpr(number & 15, 32);
                case "q" -> gpr(number & 15, 64);
                default -> throw new IllegalStateException(detail);
            };
        }

        String vectorMemory(String detail) {
            int scaleAt = detail.indexOf('@');
            int disp8Scale = 1;
            if (scaleAt >= 0) {
                disp8Scale = Integer.parseInt(detail.substring(scaleAt + 1));
                detail = detail.substring(0, scaleAt);
            }
            String size = detail.substring(0, 1);
            int at = 1;
            String indexClass = null;
            if (detail.length() > 1 && Character.isUpperCase(detail.charAt(1))) {
                indexClass = String.valueOf(Character.toLowerCase(detail.charAt(1)));
                at = 2;
            }
            String broadcast = detail.substring(at);
            if (evex && displacement8) {
                displacement *= disp8Scale;
            }
            return memory(size, indexClass) + broadcast;
        }

        // ---------------------------------------------------------------- ModRM and memory

        int modrmStart;

        void readModrm() {
            modrmStart = pos;
            int modrm = next();
            hasModrm = true;
            mod = modrm >> 6;
            reg = (modrm >> 3) & 7;
            rm = modrm & 7;
            if (mod == 3) {
                return;
            }
            if (rm == 4) {
                sib = true;
                int s = next();
                scale = 1 << (s >> 6);
                index = ((s >> 3) & 7) | (x ? 8 : 0);
                int baseBits = s & 7;
                if (baseBits == 5 && mod == 0) {
                    base = -1;
                    displacement = nextSigned(4);
                } else {
                    base = baseBits | (b ? 8 : 0);
                }
            } else if (rm == 5 && mod == 0) {
                ripRelative = true;
                displacement = nextSigned(4);
            } else {
                base = rm | (b ? 8 : 0);
            }
            if (mod == 1) {
                displacement = nextSigned(1);
                displacement8 = true;
            } else if (mod == 2) {
                displacement = nextSigned(4);
            }
        }

        String memory(String size) {
            return memory(size, null);
        }

        String memory(String size, String vsibClass) {
            // An address made only of a displacement doesn't show the address size.
            addressSizeUsed |= ripRelative || base >= 0 || (sib && (index != 4 || vsibClass != null || scale != 1));
            StringBuilder s = new StringBuilder(sizeName(size)).append(segmentPrefix()).append('[');
            String[] registers = addressSize ? GPR32 : GPR64;
            boolean any = false;
            if (ripRelative) {
                s.append(addressSize ? "eip" : "rip");
                any = true;
            } else if (base >= 0) {
                s.append(registers[base]);
                any = true;
            }
            if (sib) {
                String indexName = null;
                if (vsibClass != null) {
                    indexName = vsibClass + "mm" + (index | (evex && vPrime ? 16 : 0));
                } else if (index != 4) {
                    indexName = registers[index];
                } else if (!tileMemory && (scale != 1 || (base >= 0 && (base & 7) != 4))) {
                    indexName = addressSize ? "eiz" : "riz";
                }
                if (indexName != null) {
                    if (any) {
                        s.append(" + ");
                    }
                    if (scale != 1 || base < 0) {
                        s.append(scale).append('*');
                    }
                    s.append(indexName);
                    any = true;
                }
            }
            if (any) {
                if (displacement != 0) {
                    s.append(displacement < 0 ? " - " : " + ").append(hex(Math.abs(displacement)));
                }
            } else {
                s.append(imm(displacement, true));
            }
            return s.append(']').toString();
        }

        String segmentPrefix() {
            return segment >= 0 ? SEGMENTS[segment] + ":" : "";
        }

        String gpr(int number, int bits) {
            return switch (bits) {
                case 8 -> rex || number >= 8 ? GPR8[number] : GPR8_LEGACY[number];
                case 16 -> GPR16[number];
                case 32 -> GPR32[number];
                default -> GPR64[number];
            };
        }

        // ---------------------------------------------------------------- output

        Instruction finishBranch(String mnemonic, String operands, InstructionKind kind, long target) {
            Instruction i = finish(mnemonic, operands, kind, -1);
            return new Instruction(i.address(), i.length(), i.mnemonic(), i.operands(), kind, OptionalLong.of(target));
        }

        Instruction branch(String mnemonic, long rel, InstructionKind kind) {
            long target = address + (pos - start) + rel;
            return finishBranch(mnemonic, "0x" + Long.toHexString(target), kind, target);
        }

        Instruction finish(String mnemonic, String operands, InstructionKind kind, long unused) {
            String prefixes = "";
            boolean flagsShown = !twoByte || mandatory == 0;
            if (lock) {
                if (flagsShown) {
                    prefixes = "lock ";
                }
                kind = ATOMIC;
            }
            // LLVM prints a repeat prefix unless it was a mandatory prefix, except on string
            // instructions, where it is part of the instruction.
            if (repeat != 0 && (!twoByte || mandatory == 0 || stringInstruction) && !(repeat == 0xF3 && xchgNop)) {
                prefixes += repeat == 0xF3 ? "rep " : "repne ";
            }
            if (addressSize && !addressSizeUsed) {
                prefixes += "addr32 ";
            }
            mnemonic = prefixes + mnemonic;
            return new Instruction(address, pos - start, mnemonic, operands, kind, OptionalLong.empty());
        }
    }

    // -------------------------------------------------------------------- formatting helpers

    static String sizeLetter(int bits) {
        return switch (bits) {
            case 8 -> "b";
            case 16 -> "w";
            case 32 -> "d";
            case 64 -> "q";
            default -> "-";
        };
    }

    static int sizeBytes(String letter) {
        return switch (letter) {
            case "b" -> 1;
            case "w" -> 2;
            case "d" -> 4;
            case "q" -> 8;
            case "x" -> 16;
            case "y" -> 32;
            case "z" -> 64;
            case "t" -> 10;
            case "f" -> 6;
            default -> 1;
        };
    }

    static String sizeName(String letter) {
        return switch (letter) {
            case "b" -> "byte ptr ";
            case "w" -> "word ptr ";
            case "d" -> "dword ptr ";
            case "q" -> "qword ptr ";
            case "x" -> "xmmword ptr ";
            case "y" -> "ymmword ptr ";
            case "z" -> "zmmword ptr ";
            case "t" -> "tbyte ptr ";
            case "f" -> "fword ptr ";
            default -> "";
        };
    }

    /** An immediate as LLVM prints it: hex, with a sign when {@code signed}. */
    static String imm(long value, boolean signed) {
        if (signed && value < 0) {
            return value == Long.MIN_VALUE ? "-0x8000000000000000" : "-0x" + Long.toHexString(-value);
        }
        return "0x" + Long.toHexString(value);
    }

    static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }
}
