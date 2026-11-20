package instructive.parse.aarch64;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.OptionalLong;

/**
 * A decoder for the A64 instructions that JIT compilers commonly emit: integer
 * arithmetic and logic, moves, bitfield operations, loads and stores (including pairs,
 * exclusives, acquire/release and LSE atomics), branches, barriers, hints, system
 * register access and scalar floating point. Vector SIMD instructions are classified as
 * {@link InstructionKind#SIMD} but not decoded.
 *
 * <p>Text follows LLVM's disassembler, so it can be checked against
 * {@code llvm-objdump}: preferred aliases ({@code mov}, {@code cmp}, {@code lsl},
 * {@code cset}...), immediates in hex except shift amounts and bitfield positions, and
 * branch targets as absolute addresses. Words that can't be decoded come back as
 * {@code .word 0x...}.
 */
public final class Aarch64Disassembler implements Disassembler {

    private static final String[] CONDITIONS = {
        "eq", "ne", "hs", "lo", "mi", "pl", "vs", "vc", "hi", "ls", "ge", "lt", "gt", "le", "al", "nv"
    };
    private static final String[] SHIFTS = {"lsl", "lsr", "asr", "ror"};
    private static final String[] EXTENDS = {"uxtb", "uxth", "uxtw", "uxtx", "sxtb", "sxth", "sxtw", "sxtx"};
    private static final String[] LOGICAL = {"and", "bic", "orr", "orn", "eor", "eon", "ands", "bics"};
    private static final String[] ATOMIC_OPS = {"ldadd", "ldclr", "ldeor", "ldset", "ldsmax", "ldsmin", "ldumax", "ldumin"};
    private static final String[] BARRIER_OPTIONS = {
        null, "oshld", "oshst", "osh", null, "nshld", "nshst", "nsh", null, "ishld", "ishst", "ish", null, "ld", "st", "sy"
    };
    private static final Map<Integer, String> HINTS = Map.ofEntries(
            Map.entry(0x00, "nop"), Map.entry(0x01, "yield"), Map.entry(0x02, "wfe"), Map.entry(0x03, "wfi"),
            Map.entry(0x04, "sev"), Map.entry(0x05, "sevl"), Map.entry(0x07, "xpaclri"), Map.entry(0x08, "pacia1716"),
            Map.entry(0x06, "dgh"), Map.entry(0x0a, "pacib1716"), Map.entry(0x0c, "autia1716"),
            Map.entry(0x0e, "autib1716"), Map.entry(0x10, "esb"), Map.entry(0x11, "psb csync"),
            Map.entry(0x12, "tsb csync"), Map.entry(0x13, "gcsb dsync"), Map.entry(0x14, "csdb"),
            Map.entry(0x16, "clrbhb"), Map.entry(0x18, "paciaz"), Map.entry(0x19, "paciasp"),
            Map.entry(0x1a, "pacibz"), Map.entry(0x1b, "pacibsp"), Map.entry(0x1c, "autiaz"),
            Map.entry(0x1d, "autiasp"), Map.entry(0x1e, "autibz"), Map.entry(0x1f, "autibsp"), Map.entry(0x20, "bti"),
            Map.entry(0x22, "bti c"), Map.entry(0x24, "bti j"), Map.entry(0x26, "bti jc"));
    // Keyed by op0:op1:CRn:CRm:op2.
    private static final Map<Integer, String> SYSTEM_REGISTERS = Map.ofEntries(
            Map.entry(sysreg(3, 3, 4, 2, 0), "NZCV"), Map.entry(sysreg(3, 3, 4, 2, 1), "DAIF"),
            Map.entry(sysreg(3, 3, 4, 4, 0), "FPCR"), Map.entry(sysreg(3, 3, 4, 4, 1), "FPSR"),
            Map.entry(sysreg(3, 3, 13, 0, 2), "TPIDR_EL0"), Map.entry(sysreg(3, 3, 13, 0, 3), "TPIDRRO_EL0"),
            Map.entry(sysreg(3, 3, 14, 0, 0), "CNTFRQ_EL0"), Map.entry(sysreg(3, 3, 14, 0, 1), "CNTPCT_EL0"),
            Map.entry(sysreg(3, 3, 14, 0, 2), "CNTVCT_EL0"), Map.entry(sysreg(3, 3, 0, 0, 1), "CTR_EL0"),
            Map.entry(sysreg(3, 3, 0, 0, 7), "DCZID_EL0"), Map.entry(sysreg(3, 0, 0, 0, 0), "MIDR_EL1"),
            Map.entry(sysreg(3, 0, 0, 0, 5), "MPIDR_EL1"));
    // Registers that can only be read: MSR to them is shown with the generic name.
    private static final Set<String> READ_ONLY_REGISTERS =
            Set.of("CTR_EL0", "DCZID_EL0", "MIDR_EL1", "MPIDR_EL1", "CNTPCT_EL0", "CNTVCT_EL0");
    // Cache maintenance (SYS with op0 = 1), keyed by op1:CRn:CRm:op2.
    private static final Map<Integer, String> CACHE_OPERATIONS = Map.ofEntries(
            Map.entry(sysreg(0, 3, 7, 4, 1), "dc zva"), Map.entry(sysreg(0, 0, 7, 6, 1), "dc ivac"),
            Map.entry(sysreg(0, 0, 7, 6, 2), "dc isw"), Map.entry(sysreg(0, 3, 7, 10, 1), "dc cvac"),
            Map.entry(sysreg(0, 3, 7, 11, 1), "dc cvau"), Map.entry(sysreg(0, 3, 7, 12, 1), "dc cvap"),
            Map.entry(sysreg(0, 3, 7, 14, 1), "dc civac"), Map.entry(sysreg(0, 3, 7, 5, 1), "ic ivau"),
            Map.entry(sysreg(0, 0, 7, 5, 0), "ic iallu"), Map.entry(sysreg(0, 0, 7, 1, 0), "ic ialluis"));

    @Override
    public Instruction decode(byte[] code, int offset, long address) {
        if (offset + 4 > code.length) {
            return new Instruction(address, code.length - offset, ".byte", "", UNKNOWN, OptionalLong.empty());
        }
        int word = (code[offset] & 0xFF) | (code[offset + 1] & 0xFF) << 8 | (code[offset + 2] & 0xFF) << 16
                | (code[offset + 3] & 0xFF) << 24;
        return decode(word, address);
    }

    /** Decodes the instruction encoded as {@code word}, located at {@code address}. */
    public Instruction decode(int word, long address) {
        return new Decoding(word, address).decode();
    }

    // The state of decoding one word, so the many small decoders can share the fields
    // every instruction has.
    private static final class Decoding {
        private final int w;
        private final long address;
        private final boolean sf;
        private final int rd;
        private final int rn;
        private final int rm;

        Decoding(int word, long address) {
            this.w = word;
            this.address = address;
            this.sf = bit(31);
            this.rd = field(0, 5);
            this.rn = field(5, 5);
            this.rm = field(16, 5);
        }

        Instruction decode() {
            return switch (field(25, 4)) {
                case 0b1000, 0b1001 -> dataProcessingImmediate();
                case 0b1010, 0b1011 -> branchesExceptionsSystem();
                case 0b0100, 0b0110, 0b1100, 0b1110 -> loadsAndStores();
                case 0b0101, 0b1101 -> dataProcessingRegister();
                case 0b0111, 0b1111 -> floatingPointAndSimd();
                case 0b0000 -> field(16, 16) == 0 ? of("udf", uimm(field(0, 16)), EXCEPTION) : unknown(UNKNOWN);
                default -> unknown(UNKNOWN);
            };
        }

        // Data processing (immediate)

        private Instruction dataProcessingImmediate() {
            return switch (field(23, 3)) {
                case 0b000, 0b001 -> pcRelative();
                case 0b010 -> addSubtractImmediate();
                case 0b100 -> logicalImmediate();
                case 0b101 -> moveWide();
                case 0b110 -> bitfield();
                case 0b111 -> extract();
                default -> unknown(ALU);
            };
        }

        private Instruction pcRelative() {
            long offset = signed(5, 19) << 2 | field(29, 2);
            if (sf) {
                return of("adrp", x(rd) + ", " + address((address & ~0xFFFL) + (offset << 12)), ALU);
            }
            return of("adr", x(rd) + ", " + address(address + offset), ALU);
        }

        private Instruction addSubtractImmediate() {
            boolean subtract = bit(30);
            boolean setFlags = bit(29);
            boolean shifted = bit(22);
            long imm = field(10, 12);
            String operand = uimm(imm) + (shifted ? ", lsl #12" : "");
            if (!subtract && !setFlags && !shifted && imm == 0 && (rd == 31 || rn == 31)) {
                return of("mov", regSp(rd) + ", " + regSp(rn), ALU);
            }
            if (setFlags && rd == 31) {
                return of(subtract ? "cmp" : "cmn", regSp(rn) + ", " + operand, ALU);
            }
            String mnemonic = (subtract ? "sub" : "add") + (setFlags ? "s" : "");
            return of(mnemonic, (setFlags ? reg(rd) : regSp(rd)) + ", " + regSp(rn) + ", " + operand, ALU);
        }

        private Instruction logicalImmediate() {
            int opc = field(29, 2);
            int n = field(22, 1);
            int immr = field(16, 6);
            int imms = field(10, 6);
            if (!sf && n == 1) {
                return unknown(ALU);
            }
            long mask = decodeBitMask(n, imms, immr, sf ? 64 : 32);
            if (mask == 0) {
                return unknown(ALU);
            }
            String value = uimm(sf ? mask : mask & 0xFFFF_FFFFL);
            if (opc == 3 && rd == 31) {
                return of("tst", reg(rn) + ", " + value, ALU);
            }
            // LLVM shows ORR with the zero register as MOV, with a signed value, unless
            // MOVZ or MOVN could have produced the value.
            if (opc == 1 && rn == 31 && !movableWide(mask, sf ? 64 : 32)) {
                return of("mov", regSp(rd) + ", " + imm(sf ? mask : (int) mask), ALU);
            }
            String destination = opc == 3 ? reg(rd) : regSp(rd);
            return of(LOGICAL[opc * 2], destination + ", " + reg(rn) + ", " + value, ALU);
        }

        private Instruction moveWide() {
            int opc = field(29, 2);
            int hw = field(21, 2);
            long imm16 = field(5, 16);
            if (opc == 1 || (!sf && hw >= 2)) {
                return unknown(ALU);
            }
            int shift = hw * 16;
            String plain = reg(rd) + ", " + uimm(imm16) + (shift != 0 ? ", lsl #" + shift : "");
            boolean zeroShifted = imm16 == 0 && hw != 0;
            switch (opc) {
                case 0 -> {
                    if (!zeroShifted && (sf || imm16 != 0xFFFF)) {
                        long value = ~(imm16 << shift);
                        return of("mov", reg(rd) + ", " + imm(sf ? value : (int) value), ALU);
                    }
                    return of("movn", plain, ALU);
                }
                case 2 -> {
                    if (!zeroShifted) {
                        long value = imm16 << shift;
                        return of("mov", reg(rd) + ", " + imm(sf ? value : (int) value), ALU);
                    }
                    return of("movz", plain, ALU);
                }
                default -> {
                    return of("movk", plain, ALU);
                }
            }
        }

        private Instruction bitfield() {
            int opc = field(29, 2);
            int n = field(22, 1);
            int immr = field(16, 6);
            int imms = field(10, 6);
            int width = sf ? 64 : 32;
            if (opc == 3 || n != (sf ? 1 : 0) || (!sf && (immr >= 32 || imms >= 32))) {
                return unknown(ALU);
            }
            String d = reg(rd);
            String s = reg(rn);
            String insert = "#" + (width - immr) + ", #" + (imms + 1);
            String extract = "#" + immr + ", #" + (imms - immr + 1);
            switch (opc) {
                case 0 -> {
                    if (immr == 0 && (imms == 7 || imms == 15 || (sf && imms == 31))) {
                        String mnemonic = imms == 7 ? "sxtb" : imms == 15 ? "sxth" : "sxtw";
                        return of(mnemonic, d + ", " + w(rn), ALU);
                    }
                    if (imms == width - 1) {
                        return of("asr", d + ", " + s + ", #" + immr, ALU);
                    }
                    return imms < immr ? of("sbfiz", d + ", " + s + ", " + insert, ALU)
                            : of("sbfx", d + ", " + s + ", " + extract, ALU);
                }
                case 1 -> {
                    return imms < immr ? of("bfi", d + ", " + s + ", " + insert, ALU)
                            : of("bfxil", d + ", " + s + ", " + extract, ALU);
                }
                default -> {
                    if (imms != width - 1 && imms + 1 == immr) {
                        return of("lsl", d + ", " + s + ", #" + (width - 1 - imms), ALU);
                    }
                    if (imms == width - 1) {
                        return of("lsr", d + ", " + s + ", #" + immr, ALU);
                    }
                    if (imms < immr) {
                        return of("ubfiz", d + ", " + s + ", " + insert, ALU);
                    }
                    if (!sf && immr == 0 && (imms == 7 || imms == 15)) {
                        return of(imms == 7 ? "uxtb" : "uxth", d + ", " + s, ALU);
                    }
                    return of("ubfx", d + ", " + s + ", " + extract, ALU);
                }
            }
        }

        private Instruction extract() {
            int imms = field(10, 6);
            if (field(29, 2) != 0 || bit(21) || field(22, 1) != (sf ? 1 : 0) || (!sf && imms >= 32)) {
                return unknown(ALU);
            }
            if (rn == rm) {
                return of("ror", reg(rd) + ", " + reg(rn) + ", " + uimm(imms), ALU);
            }
            return of("extr", reg(rd) + ", " + reg(rn) + ", " + reg(rm) + ", " + uimm(imms), ALU);
        }

        // Branches, exception generation and system instructions

        private Instruction branchesExceptionsSystem() {
            if ((w & 0x7C00_0000) == 0x1400_0000) {
                long target = address + (signed(0, 26) << 2);
                return branch(sf ? "bl" : "b", address(target), sf ? CALL : BRANCH, target);
            }
            if ((w & 0xFF00_0010) == 0x5400_0000) {
                long target = address + (signed(5, 19) << 2);
                return branch("b." + CONDITIONS[field(0, 4)], address(target), CONDITIONAL_BRANCH, target);
            }
            if ((w & 0x7E00_0000) == 0x3400_0000) {
                long target = address + (signed(5, 19) << 2);
                return branch(bit(24) ? "cbnz" : "cbz", reg(rd) + ", " + address(target), CONDITIONAL_BRANCH, target);
            }
            if ((w & 0x7E00_0000) == 0x3600_0000) {
                int bitNumber = (sf ? 32 : 0) | field(19, 5);
                long target = address + (signed(5, 14) << 2);
                String operands = reg(rd) + ", " + uimm(bitNumber) + ", " + address(target);
                return branch(bit(24) ? "tbnz" : "tbz", operands, CONDITIONAL_BRANCH, target);
            }
            if ((w & 0xFF00_0000) == 0xD400_0000) {
                return exceptionGeneration();
            }
            if ((w & 0xFFC0_0000) == 0xD500_0000) {
                return system();
            }
            if ((w & 0xFE00_0000) == 0xD600_0000) {
                return branchRegister();
            }
            return unknown(UNKNOWN);
        }

        private Instruction exceptionGeneration() {
            int opc = field(21, 3);
            int imm16 = field(5, 16);
            int ll = field(0, 2);
            if (field(2, 3) != 0) {
                return unknown(EXCEPTION);
            }
            String mnemonic = switch (opc << 2 | ll) {
                case 0b000_01 -> "svc";
                case 0b000_10 -> "hvc";
                case 0b000_11 -> "smc";
                case 0b001_00 -> "brk";
                case 0b010_00 -> "hlt";
                case 0b101_01 -> "dcps1";
                case 0b101_10 -> "dcps2";
                case 0b101_11 -> "dcps3";
                default -> null;
            };
            if (mnemonic == null) {
                return unknown(EXCEPTION);
            }
            // LLVM prints a zero breakpoint as #0 but other immediates in hex.
            String operand = mnemonic.equals("brk") && imm16 == 0 ? "#0" : uimm(imm16);
            return of(mnemonic, operand, EXCEPTION);
        }

        private Instruction system() {
            boolean read = bit(21);
            int op0 = field(19, 2);
            int op1 = field(16, 3);
            int crn = field(12, 4);
            int crm = field(8, 4);
            int op2 = field(5, 3);
            int rt = rd;
            if (!read && op0 == 0 && op1 == 3 && crn == 2 && rt == 31) {
                int code = crm << 3 | op2;
                String hint = HINTS.get(code);
                return hint != null ? of(hint, "", SYSTEM) : of("hint", uimm(code), SYSTEM);
            }
            if (!read && op0 == 0 && op1 == 3 && crn == 3 && rt == 31) {
                return barrier(op2, crm);
            }
            if (!read && op0 == 1) {
                String operation = CACHE_OPERATIONS.get(sysreg(0, op1, crn, crm, op2));
                if (operation != null) {
                    return operation.startsWith("ic iall") ? of(operation.substring(0, 2), operation.substring(3), SYSTEM)
                            : of(operation.substring(0, 2), operation.substring(3) + ", " + x(rt), SYSTEM);
                }
                String operands = uimm(op1) + ", c" + crn + ", c" + crm + ", " + uimm(op2);
                return of("sys", rt == 31 ? operands : operands + ", " + x(rt), SYSTEM);
            }
            if (op0 >= 2) {
                String generic = String.format(Locale.ROOT, "S%d_%d_C%d_C%d_%d", op0, op1, crn, crm, op2);
                String register = SYSTEM_REGISTERS.getOrDefault(sysreg(op0, op1, crn, crm, op2), generic);
                if (!read && READ_ONLY_REGISTERS.contains(register)) {
                    register = generic;
                }
                return read ? of("mrs", x(rt) + ", " + register, SYSTEM) : of("msr", register + ", " + x(rt), SYSTEM);
            }
            return unknown(SYSTEM);
        }

        private Instruction barrier(int op2, int crm) {
            switch (op2) {
                case 2 -> {
                    return of("clrex", crm == 15 ? "" : "#" + crm, SYSTEM);
                }
                case 4, 5 -> {
                    String option = BARRIER_OPTIONS[crm];
                    return of(op2 == 4 ? "dsb" : "dmb", option != null ? option : "#" + crm, SYSTEM);
                }
                case 6 -> {
                    return of("isb", crm == 15 ? "" : "#" + crm, SYSTEM);
                }
                default -> {
                    return unknown(SYSTEM);
                }
            }
        }

        private Instruction branchRegister() {
            if (field(16, 5) != 31 || field(10, 6) != 0 || field(0, 5) != 0) {
                return unknown(INDIRECT_BRANCH);
            }
            return switch (field(21, 4)) {
                case 0 -> of("br", x(rn), INDIRECT_BRANCH);
                case 1 -> of("blr", x(rn), INDIRECT_CALL);
                case 2 -> of("ret", rn == 30 ? "" : x(rn), RETURN);
                case 4 -> rn == 31 ? of("eret", "", RETURN) : unknown(RETURN);
                case 5 -> rn == 31 ? of("drps", "", RETURN) : unknown(RETURN);
                default -> unknown(INDIRECT_BRANCH);
            };
        }

        // Loads and stores

        private Instruction loadsAndStores() {
            if ((w & 0x3F00_0000) == 0x0800_0000) {
                return exclusiveOrdered();
            }
            if ((w & 0x3B00_0000) == 0x1800_0000) {
                return loadLiteral();
            }
            if ((w & 0x3A00_0000) == 0x2800_0000) {
                return pair();
            }
            if ((w & 0x3A00_0000) == 0x3800_0000) {
                return register();
            }
            return unknown(bit(26) ? SIMD : UNKNOWN);
        }

        private Instruction exclusiveOrdered() {
            int size = field(30, 2);
            boolean o2 = bit(23);
            boolean load = bit(22);
            boolean o1 = bit(21);
            boolean o0 = bit(15);
            int rs = rm;
            int rt2 = field(10, 5);
            int rt = rd;
            String suffix = sizeSuffix(size);
            String value = reg(rt, size == 3);
            String base = "[" + x(rn, true) + "]";
            if (rt2 != 31 || (o1 && !o2)) {
                // Exclusive pairs and CASP are not decoded.
                return unknown(ATOMIC);
            }
            if (!o2) {
                return load ? of((o0 ? "ldaxr" : "ldxr") + suffix, value + ", " + base, ATOMIC)
                        : of((o0 ? "stlxr" : "stxr") + suffix, w(rs) + ", " + value + ", " + base, ATOMIC);
            }
            if (!o1) {
                if (rs != 31) {
                    return unknown(ATOMIC);
                }
                String mnemonic = (load ? (o0 ? "ldar" : "ldlar") : (o0 ? "stlr" : "stllr")) + suffix;
                return of(mnemonic, value + ", " + base, load ? LOAD : STORE);
            }
            String mnemonic = "cas" + (load ? "a" : "") + (o0 ? "l" : "") + suffix;
            return of(mnemonic, reg(rs, size == 3) + ", " + value + ", " + base, ATOMIC);
        }

        private Instruction loadLiteral() {
            int opc = field(30, 2);
            int rt = rd;
            long target = address + (signed(5, 19) << 2);
            String where = address(target);
            if (bit(26)) {
                return opc == 3 ? unknown(LOAD) : of("ldr", "sdq".charAt(opc) + String.valueOf(rt) + ", " + where, LOAD);
            }
            return switch (opc) {
                case 0 -> of("ldr", w(rt) + ", " + where, LOAD);
                case 1 -> of("ldr", x(rt) + ", " + where, LOAD);
                case 2 -> of("ldrsw", x(rt) + ", " + where, LOAD);
                default -> of("prfm", prefetchOperation(rt) + ", " + where, PREFETCH);
            };
        }

        private Instruction pair() {
            int opc = field(30, 2);
            boolean vector = bit(26);
            int indexing = field(23, 2);
            boolean load = bit(22);
            long imm7 = signed(15, 7);
            int rt = rd;
            int rt2 = field(10, 5);
            String first;
            String second;
            int scale;
            String mnemonic = indexing == 0 ? (load ? "ldnp" : "stnp") : (load ? "ldp" : "stp");
            if (vector) {
                if (opc == 3) {
                    return unknown(load ? LOAD : STORE);
                }
                char type = "sdq".charAt(opc);
                first = type + String.valueOf(rt);
                second = type + String.valueOf(rt2);
                scale = 4 << opc;
            } else if (opc == 1) {
                if (!load || indexing == 0) {
                    return unknown(load ? LOAD : STORE);
                }
                mnemonic = "ldpsw";
                first = x(rt);
                second = x(rt2);
                scale = 4;
            } else if (opc == 3) {
                return unknown(load ? LOAD : STORE);
            } else {
                boolean wide = opc == 2;
                first = reg(rt, wide);
                second = reg(rt2, wide);
                scale = wide ? 8 : 4;
            }
            return of(mnemonic, first + ", " + second + ", " + indexed(indexing, imm7 * scale), load ? LOAD : STORE);
        }

        // [base], #offset (post), [base, #offset]! (pre), otherwise [base, #offset].
        private String indexed(int indexing, long offset) {
            String base = x(rn, true);
            return switch (indexing) {
                case 1 -> "[" + base + "], " + imm(offset);
                case 3 -> "[" + base + ", " + imm(offset) + "]!";
                default -> offset == 0 ? "[" + base + "]" : "[" + base + ", " + imm(offset) + "]";
            };
        }

        private Instruction register() {
            int size = field(30, 2);
            boolean vector = bit(26);
            int opc = field(22, 2);
            int rt = rd;
            // LSE atomics share this space, with acquire/release where opc would be.
            if (!vector && !bit(24) && bit(21) && field(10, 2) == 0) {
                return atomicMemory(size);
            }
            String mnemonic;
            String value;
            int scale;
            InstructionKind kind;
            if (vector) {
                if (opc >= 2) {
                    if (size != 0) {
                        return unknown(opc == 2 ? STORE : LOAD);
                    }
                    mnemonic = opc == 2 ? "str" : "ldr";
                    value = "q" + rt;
                    scale = 4;
                } else {
                    mnemonic = opc == 0 ? "str" : "ldr";
                    value = "bhsd".charAt(size) + String.valueOf(rt);
                    scale = size;
                }
                kind = mnemonic.equals("str") ? STORE : LOAD;
            } else {
                scale = size;
                switch (opc) {
                    case 0 -> {
                        mnemonic = "str" + sizeSuffix(size);
                        value = reg(rt, size == 3);
                        kind = STORE;
                    }
                    case 1 -> {
                        mnemonic = "ldr" + sizeSuffix(size);
                        value = reg(rt, size == 3);
                        kind = LOAD;
                    }
                    case 2 -> {
                        if (size == 3) {
                            mnemonic = "prfm";
                            value = prefetchOperation(rt);
                            kind = PREFETCH;
                        } else {
                            mnemonic = size == 2 ? "ldrsw" : "ldrs" + sizeSuffix(size);
                            value = x(rt);
                            kind = LOAD;
                        }
                    }
                    default -> {
                        if (size >= 2) {
                            return unknown(LOAD);
                        }
                        mnemonic = "ldrs" + sizeSuffix(size);
                        value = w(rt);
                        kind = LOAD;
                    }
                }
            }
            String base = x(rn, true);
            if (bit(24)) {
                long offset = (long) field(10, 12) << scale;
                return of(mnemonic, value + ", " + (offset == 0 ? "[" + base + "]" : "[" + base + ", " + uimm(offset) + "]"), kind);
            }
            if (!bit(21)) {
                long imm9 = signed(12, 9);
                int indexing = field(10, 2);
                boolean prefetch = mnemonic.equals("prfm");
                switch (indexing) {
                    case 0 -> {
                        String unscaled = prefetch ? "prfum" : mnemonic.replaceFirst("^(ld|st)r", "$1ur");
                        return of(unscaled, value + ", " + indexed(2, imm9), kind);
                    }
                    case 2 -> {
                        if (prefetch || vector) {
                            return unknown(kind);
                        }
                        return of(mnemonic.replaceFirst("^(ld|st)r", "$1tr"), value + ", " + indexed(2, imm9), kind);
                    }
                    default -> {
                        return prefetch ? unknown(kind) : of(mnemonic, value + ", " + indexed(indexing, imm9), kind);
                    }
                }
            }
            return field(10, 2) == 2 ? registerOffset(mnemonic, value, scale, kind) : unknown(kind);
        }

        private Instruction registerOffset(String mnemonic, String value, int scale, InstructionKind kind) {
            int option = field(13, 3);
            boolean shifted = bit(12);
            if ((option & 2) == 0) {
                return unknown(kind);
            }
            if (mnemonic.equals("prfm") && (rd >> 3) == 3) {
                // RPRFM, range prefetch: not decoded.
                return unknown(PREFETCH);
            }
            String base = x(rn, true);
            String index = reg(rm, (option & 1) == 1);
            String extend;
            if (option == 3) {
                extend = shifted ? ", lsl #" + scale : "";
            } else {
                extend = ", " + EXTENDS[option] + (shifted ? " #" + scale : "");
            }
            return of(mnemonic, value + ", [" + base + ", " + index + extend + "]", kind);
        }

        private Instruction atomicMemory(int size) {
            boolean acquire = bit(23);
            boolean release = bit(22);
            int rs = rm;
            int rt = rd;
            boolean o3 = bit(15);
            int opc = field(12, 3);
            boolean wide = size == 3;
            String base = "[" + x(rn, true) + "]";
            String suffix = sizeSuffix(size);
            String operation;
            if (!o3) {
                operation = ATOMIC_OPS[opc];
            } else if (opc == 0) {
                operation = "swp";
            } else if (opc == 4 && acquire && !release && rs == 31) {
                return of("ldapr" + suffix, reg(rt, wide) + ", " + base, LOAD);
            } else {
                return unknown(ATOMIC);
            }
            if (!o3 && rt == 31 && !acquire) {
                // The result is discarded: LLVM prefers the store form.
                return of("st" + operation.substring(2) + (release ? "l" : "") + suffix, reg(rs, wide) + ", " + base, ATOMIC);
            }
            String mnemonic = operation + (acquire ? "a" : "") + (release ? "l" : "") + suffix;
            return of(mnemonic, reg(rs, wide) + ", " + reg(rt, wide) + ", " + base, ATOMIC);
        }

        // Data processing (register)

        private Instruction dataProcessingRegister() {
            int op2 = field(21, 4);
            if (!bit(28)) {
                if ((op2 & 0b1000) == 0) {
                    return logicalShifted();
                }
                return (op2 & 1) == 0 ? addSubtractShifted() : addSubtractExtended();
            }
            return switch (op2) {
                case 0b0000 -> addSubtractCarry();
                case 0b0010 -> conditionalCompare();
                case 0b0100 -> conditionalSelect();
                case 0b0110 -> bit(30) ? oneSource() : twoSource();
                default -> op2 >= 0b1000 ? threeSource() : unknown(ALU);
            };
        }

        private String shift(int type, int amount) {
            return type == 0 && amount == 0 ? "" : ", " + SHIFTS[type] + " #" + amount;
        }

        private Instruction logicalShifted() {
            int opc = field(29, 2);
            int type = field(22, 2);
            boolean negate = bit(21);
            int amount = field(10, 6);
            if (!sf && amount >= 32) {
                return unknown(ALU);
            }
            String shifted = reg(rm) + shift(type, amount);
            if (opc == 1 && rn == 31) {
                if (!negate && type == 0 && amount == 0) {
                    return of("mov", reg(rd) + ", " + reg(rm), ALU);
                }
                if (negate) {
                    return of("mvn", reg(rd) + ", " + shifted, ALU);
                }
            }
            if (opc == 3 && !negate && rd == 31) {
                return of("tst", reg(rn) + ", " + shifted, ALU);
            }
            return of(LOGICAL[opc * 2 + (negate ? 1 : 0)], reg(rd) + ", " + reg(rn) + ", " + shifted, ALU);
        }

        private Instruction addSubtractShifted() {
            int type = field(22, 2);
            int amount = field(10, 6);
            if (type == 3 || (!sf && amount >= 32)) {
                return unknown(ALU);
            }
            boolean subtract = bit(30);
            boolean setFlags = bit(29);
            String shifted = reg(rm) + shift(type, amount);
            if (setFlags && rd == 31) {
                return of(subtract ? "cmp" : "cmn", reg(rn) + ", " + shifted, ALU);
            }
            if (subtract && rn == 31) {
                return of(setFlags ? "negs" : "neg", reg(rd) + ", " + shifted, ALU);
            }
            String mnemonic = (subtract ? "sub" : "add") + (setFlags ? "s" : "");
            return of(mnemonic, reg(rd) + ", " + reg(rn) + ", " + shifted, ALU);
        }

        private Instruction addSubtractExtended() {
            int option = field(13, 3);
            int amount = field(10, 3);
            if (field(22, 2) != 0 || amount > 4) {
                return unknown(ALU);
            }
            boolean subtract = bit(30);
            boolean setFlags = bit(29);
            // With SP as an operand, the extend that matches the register size is shown as LSL.
            boolean asShift = (rn == 31 || (!setFlags && rd == 31)) && option == (sf ? 3 : 2);
            String extend = asShift ? (amount == 0 ? "" : ", lsl #" + amount)
                    : ", " + EXTENDS[option] + (amount == 0 ? "" : " #" + amount);
            String index = reg(rm, sf && (option & 3) == 3) + extend;
            if (setFlags && rd == 31) {
                return of(subtract ? "cmp" : "cmn", regSp(rn) + ", " + index, ALU);
            }
            String mnemonic = (subtract ? "sub" : "add") + (setFlags ? "s" : "");
            return of(mnemonic, (setFlags ? reg(rd) : regSp(rd)) + ", " + regSp(rn) + ", " + index, ALU);
        }

        private Instruction addSubtractCarry() {
            if (field(10, 6) != 0) {
                return unknown(ALU);
            }
            boolean subtract = bit(30);
            boolean setFlags = bit(29);
            if (subtract && rn == 31) {
                return of(setFlags ? "ngcs" : "ngc", reg(rd) + ", " + reg(rm), ALU);
            }
            String mnemonic = (subtract ? "sbc" : "adc") + (setFlags ? "s" : "");
            return of(mnemonic, reg(rd) + ", " + reg(rn) + ", " + reg(rm), ALU);
        }

        private Instruction conditionalCompare() {
            if (!bit(29) || bit(10) || bit(4)) {
                return unknown(ALU);
            }
            String second = bit(11) ? uimm(rm) : reg(rm);
            String operands = reg(rn) + ", " + second + ", " + uimm(field(0, 4)) + ", " + CONDITIONS[field(12, 4)];
            return of(bit(30) ? "ccmp" : "ccmn", operands, ALU);
        }

        private Instruction conditionalSelect() {
            int op2 = field(10, 2);
            if (bit(29) || op2 > 1) {
                return unknown(ALU);
            }
            int condition = field(12, 4);
            boolean aliasable = condition < 14;
            String inverted = CONDITIONS[condition ^ 1];
            int variant = (bit(30) ? 2 : 0) | op2;
            switch (variant) {
                case 1 -> {
                    if (aliasable && rm == 31 && rn == 31) {
                        return of("cset", reg(rd) + ", " + inverted, ALU);
                    }
                    if (aliasable && rm == rn && rn != 31) {
                        return of("cinc", reg(rd) + ", " + reg(rn) + ", " + inverted, ALU);
                    }
                }
                case 2 -> {
                    if (aliasable && rm == 31 && rn == 31) {
                        return of("csetm", reg(rd) + ", " + inverted, ALU);
                    }
                    if (aliasable && rm == rn && rn != 31) {
                        return of("cinv", reg(rd) + ", " + reg(rn) + ", " + inverted, ALU);
                    }
                }
                case 3 -> {
                    if (aliasable && rm == rn) {
                        return of("cneg", reg(rd) + ", " + reg(rn) + ", " + inverted, ALU);
                    }
                }
                default -> {
                }
            }
            String mnemonic = new String[] {"csel", "csinc", "csinv", "csneg"}[variant];
            return of(mnemonic, reg(rd) + ", " + reg(rn) + ", " + reg(rm) + ", " + CONDITIONS[condition], ALU);
        }

        private Instruction twoSource() {
            if (bit(29)) {
                return unknown(ALU);
            }
            int opcode = field(10, 6);
            String mnemonic = switch (opcode) {
                case 2 -> "udiv";
                case 3 -> "sdiv";
                case 8 -> "lsl";
                case 9 -> "lsr";
                case 10 -> "asr";
                case 11 -> "ror";
                default -> null;
            };
            if (mnemonic != null) {
                return of(mnemonic, reg(rd) + ", " + reg(rn) + ", " + reg(rm), ALU);
            }
            if ((opcode & 0b111000) == 0b010000) {
                int size = opcode & 3;
                if (sf != (size == 3)) {
                    return unknown(ALU);
                }
                String crc = ((opcode & 4) != 0 ? "crc32c" : "crc32") + "bhwx".charAt(size);
                return of(crc, w(rd) + ", " + w(rn) + ", " + reg(rm, size == 3), ALU);
            }
            return unknown(ALU);
        }

        private Instruction oneSource() {
            if (bit(29) || field(16, 5) != 0) {
                return unknown(ALU);
            }
            String mnemonic = switch (field(10, 6)) {
                case 0 -> "rbit";
                case 1 -> "rev16";
                case 2 -> sf ? "rev32" : "rev";
                case 3 -> sf ? "rev" : null;
                case 4 -> "clz";
                case 5 -> "cls";
                default -> null;
            };
            return mnemonic == null ? unknown(ALU) : of(mnemonic, reg(rd) + ", " + reg(rn), ALU);
        }

        private Instruction threeSource() {
            int op31 = field(21, 3);
            boolean subtract = bit(15);
            int ra = field(10, 5);
            if (field(29, 2) != 0) {
                return unknown(ALU);
            }
            switch (op31) {
                case 0 -> {
                    if (ra == 31) {
                        return of(subtract ? "mneg" : "mul", reg(rd) + ", " + reg(rn) + ", " + reg(rm), ALU);
                    }
                    return of(subtract ? "msub" : "madd", reg(rd) + ", " + reg(rn) + ", " + reg(rm) + ", " + reg(ra), ALU);
                }
                case 1, 5 -> {
                    if (!sf) {
                        return unknown(ALU);
                    }
                    String sign = op31 == 1 ? "s" : "u";
                    if (ra == 31) {
                        return of(sign + (subtract ? "mnegl" : "mull"), x(rd) + ", " + w(rn) + ", " + w(rm), ALU);
                    }
                    String mnemonic = sign + (subtract ? "msubl" : "maddl");
                    return of(mnemonic, x(rd) + ", " + w(rn) + ", " + w(rm) + ", " + x(ra), ALU);
                }
                case 2, 6 -> {
                    if (!sf || subtract || ra != 31) {
                        return unknown(ALU);
                    }
                    return of(op31 == 2 ? "smulh" : "umulh", x(rd) + ", " + x(rn) + ", " + x(rm), ALU);
                }
                default -> {
                    return unknown(ALU);
                }
            }
        }

        // Floating point and SIMD

        private Instruction floatingPointAndSimd() {
            if ((w & 0x7F20_0000) == 0x1E20_0000) {
                return scalarFloatingPoint();
            }
            if ((w & 0x7F00_0000) == 0x1F00_0000) {
                return floatingPointThreeSource();
            }
            return unknown(SIMD);
        }

        private Instruction scalarFloatingPoint() {
            String type = floatType(field(22, 2));
            if (type == null) {
                return unknown(FLOATING_POINT);
            }
            String d = type + rd;
            String n = type + rn;
            String m = type + rm;
            boolean conversion = field(10, 6) == 0;
            // Bit 31 is sf for conversions between integer and floating point; elsewhere
            // it must be clear.
            if (bit(31) && !conversion) {
                return unknown(FLOATING_POINT);
            }
            switch (field(10, 2)) {
                case 1 -> {
                    return unknown(FLOATING_POINT);
                }
                case 2 -> {
                    String mnemonic = switch (field(12, 4)) {
                        case 0 -> "fmul";
                        case 1 -> "fdiv";
                        case 2 -> "fadd";
                        case 3 -> "fsub";
                        case 4 -> "fmax";
                        case 5 -> "fmin";
                        case 6 -> "fmaxnm";
                        case 7 -> "fminnm";
                        case 8 -> "fnmul";
                        default -> null;
                    };
                    return mnemonic == null ? unknown(FLOATING_POINT) : of(mnemonic, d + ", " + n + ", " + m, FLOATING_POINT);
                }
                case 3 -> {
                    return of("fcsel", d + ", " + n + ", " + m + ", " + CONDITIONS[field(12, 4)], FLOATING_POINT);
                }
                default -> {
                }
            }
            if (bit(12)) {
                if (field(5, 5) != 0) {
                    return unknown(FLOATING_POINT);
                }
                return of("fmov", d + ", " + floatImmediate(field(13, 8)), FLOATING_POINT);
            }
            if (bit(13)) {
                return floatCompare(n, m);
            }
            if (bit(14)) {
                return floatOneSource(type, d, n);
            }
            if (!bit(15)) {
                return floatConversion(type);
            }
            return unknown(FLOATING_POINT);
        }

        private Instruction floatCompare(String n, String m) {
            if (field(14, 2) != 0) {
                return unknown(FLOATING_POINT);
            }
            return switch (field(0, 5)) {
                case 0 -> of("fcmp", n + ", " + m, FLOATING_POINT);
                case 8 -> rm == 0 ? of("fcmp", n + ", #0.0", FLOATING_POINT) : unknown(FLOATING_POINT);
                case 16 -> of("fcmpe", n + ", " + m, FLOATING_POINT);
                case 24 -> rm == 0 ? of("fcmpe", n + ", #0.0", FLOATING_POINT) : unknown(FLOATING_POINT);
                default -> unknown(FLOATING_POINT);
            };
        }

        private Instruction floatOneSource(String type, String d, String n) {
            int opcode = field(15, 6);
            String mnemonic = switch (opcode) {
                case 0 -> "fmov";
                case 1 -> "fabs";
                case 2 -> "fneg";
                case 3 -> "fsqrt";
                case 8 -> "frintn";
                case 9 -> "frintp";
                case 10 -> "frintm";
                case 11 -> "frintz";
                case 12 -> "frinta";
                case 14 -> "frintx";
                case 15 -> "frinti";
                default -> null;
            };
            if (mnemonic != null) {
                return of(mnemonic, d + ", " + n, FLOATING_POINT);
            }
            String target = switch (opcode) {
                case 4 -> "s";
                case 5 -> "d";
                case 7 -> "h";
                default -> null;
            };
            if (target == null || target.equals(type)) {
                return unknown(FLOATING_POINT);
            }
            return of("fcvt", target + rd + ", " + n, FLOATING_POINT);
        }

        private Instruction floatConversion(String type) {
            int rmode = field(19, 2);
            int opcode = field(16, 3);
            String general = reg(rd);
            String source = reg(rn);
            String vectorD = type + rd;
            String vectorN = type + rn;
            String mnemonic;
            switch (rmode << 3 | opcode) {
                case 0b00_010 -> {
                    return of("scvtf", vectorD + ", " + source, FLOATING_POINT);
                }
                case 0b00_011 -> {
                    return of("ucvtf", vectorD + ", " + source, FLOATING_POINT);
                }
                case 0b00_110, 0b00_111 -> {
                    boolean sameSize = (sf && type.equals("d")) || (!sf && type.equals("s")) || type.equals("h");
                    if (!sameSize) {
                        return unknown(FLOATING_POINT);
                    }
                    return opcode == 6 ? of("fmov", general + ", " + vectorN, FLOATING_POINT)
                            : of("fmov", vectorD + ", " + source, FLOATING_POINT);
                }
                case 0b00_000 -> mnemonic = "fcvtns";
                case 0b00_001 -> mnemonic = "fcvtnu";
                case 0b00_100 -> mnemonic = "fcvtas";
                case 0b00_101 -> mnemonic = "fcvtau";
                case 0b01_000 -> mnemonic = "fcvtps";
                case 0b01_001 -> mnemonic = "fcvtpu";
                case 0b10_000 -> mnemonic = "fcvtms";
                case 0b10_001 -> mnemonic = "fcvtmu";
                case 0b11_000 -> mnemonic = "fcvtzs";
                case 0b11_001 -> mnemonic = "fcvtzu";
                default -> {
                    return unknown(FLOATING_POINT);
                }
            }
            return of(mnemonic, general + ", " + vectorN, FLOATING_POINT);
        }

        private Instruction floatingPointThreeSource() {
            String type = floatType(field(22, 2));
            if (type == null || bit(31)) {
                return unknown(FLOATING_POINT);
            }
            String mnemonic = new String[] {"fmadd", "fmsub", "fnmadd", "fnmsub"}[(bit(21) ? 2 : 0) | (bit(15) ? 1 : 0)];
            String operands = type + rd + ", " + type + rn + ", " + type + rm + ", " + type + field(10, 5);
            return of(mnemonic, operands, FLOATING_POINT);
        }

        private static String floatType(int ptype) {
            return switch (ptype) {
                case 0 -> "s";
                case 1 -> "d";
                case 3 -> "h";
                default -> null;
            };
        }

        // VFPExpandImm: sign, a 3-bit exponent and a 4-bit fraction.
        private static String floatImmediate(int imm8) {
            int sign = imm8 >> 7;
            int exponent = (((~imm8 >> 6) & 1) << 2 | (imm8 >> 4) & 3) - 3;
            double value = (16 + (imm8 & 15)) / 16.0 * Math.pow(2, exponent);
            return String.format(Locale.ROOT, "#%.8f", sign == 1 ? -value : value);
        }

        // Operands and helpers

        private boolean bit(int position) {
            return ((w >>> position) & 1) == 1;
        }

        private int field(int low, int width) {
            return (w >>> low) & ((1 << width) - 1);
        }

        private long signed(int low, int width) {
            return ((long) field(low, width) << (64 - width)) >> (64 - width);
        }

        // A general-purpose register of the instruction's width; 31 is the zero register.
        private String reg(int register) {
            return reg(register, sf);
        }

        // A general-purpose register of the instruction's width; 31 is the stack pointer.
        private String regSp(int register) {
            if (register == 31) {
                return sf ? "sp" : "wsp";
            }
            return reg(register);
        }

        private static String reg(int register, boolean wide) {
            if (register == 31) {
                return wide ? "xzr" : "wzr";
            }
            return (wide ? "x" : "w") + register;
        }

        private static String x(int register, boolean stackPointer) {
            if (register == 31) {
                return stackPointer ? "sp" : "xzr";
            }
            return "x" + register;
        }

        private static String x(int register) {
            return reg(register, true);
        }

        private static String w(int register) {
            return reg(register, false);
        }

        private static String sizeSuffix(int size) {
            return size == 0 ? "b" : size == 1 ? "h" : "";
        }

        // <type><target><policy>, e.g. pldl1keep; target 3 is the system level cache.
        private static String prefetchOperation(int rt) {
            int type = rt >> 3;
            if (type == 3) {
                return uimm(rt);
            }
            int target = (rt >> 1) & 3;
            return new String[] {"pld", "pli", "pst"}[type] + (target == 3 ? "slc" : "l" + (target + 1))
                    + ((rt & 1) == 0 ? "keep" : "strm");
        }

        private static String imm(long value) {
            return value < 0 ? "#-0x" + Long.toHexString(-value) : "#0x" + Long.toHexString(value);
        }

        private static String uimm(long value) {
            return "#0x" + Long.toUnsignedString(value, 16);
        }

        private static String address(long value) {
            return "0x" + Long.toUnsignedString(value, 16);
        }

        private Instruction of(String mnemonic, String operands, InstructionKind kind) {
            return new Instruction(address, 4, mnemonic, operands, kind, OptionalLong.empty());
        }

        private Instruction branch(String mnemonic, String operands, InstructionKind kind, long target) {
            return new Instruction(address, 4, mnemonic, operands, kind, OptionalLong.of(target));
        }

        private Instruction unknown(InstructionKind kind) {
            return new Instruction(address, 4, ".word", String.format("0x%08x", w), kind, OptionalLong.empty());
        }
    }

    private static int sysreg(int op0, int op1, int crn, int crm, int op2) {
        return op0 << 14 | op1 << 11 | crn << 7 | crm << 3 | op2;
    }

    // DecodeBitMasks from the ARM pseudocode, for logical immediates. Returns 0, which
    // no valid encoding produces, for reserved encodings.
    static long decodeBitMask(int n, int imms, int immr, int dataSize) {
        int combined = n << 6 | (~imms & 0x3F);
        if (combined == 0) {
            return 0;
        }
        int length = 31 - Integer.numberOfLeadingZeros(combined);
        if (length < 1) {
            return 0;
        }
        int levels = (1 << length) - 1;
        int s = imms & levels;
        int r = immr & levels;
        if (s == levels) {
            return 0;
        }
        int elementSize = 1 << length;
        long elementMask = elementSize == 64 ? -1L : (1L << elementSize) - 1;
        long ones = (1L << (s + 1)) - 1;
        long element = r == 0 ? ones : ((ones >>> r) | (ones << (elementSize - r))) & elementMask;
        long mask = 0;
        for (int i = 0; i < dataSize; i += elementSize) {
            mask |= element << i;
        }
        return dataSize == 64 ? mask : mask & 0xFFFF_FFFFL;
    }

    // Whether MOVZ or MOVN can produce `value`: all its set bits, or all its clear bits,
    // lie in one aligned 16-bit chunk.
    private static boolean movableWide(long value, int width) {
        long mask = width == 64 ? -1L : 0xFFFF_FFFFL;
        for (long candidate : new long[] {value & mask, ~value & mask}) {
            for (int shift = 0; shift < width; shift += 16) {
                if ((candidate & ~(0xFFFFL << shift)) == 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
