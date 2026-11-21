#!/usr/bin/env python3
"""Generates the SSE/AVX/AVX-512 opcode table used by X86Disassembler.

Every combination of encoding space (legacy SSE maps 0F, 0F38, 0F3A; VEX maps 1-3;
EVEX maps 1, 2, 3, 5, 6), mandatory prefix, opcode, W, vector length, ModRM.reg and
operand form (register or memory) is decoded by LLVM. Each probe puts a different
register number in every field (ModRM.reg, ModRM.rm, VEX.vvvv, the is4 immediate), so
each operand LLVM prints can be traced back to the field that encodes it. The resulting
operand templates are merged across W, vector length and ModRM.reg where they agree.

Usage: opcodes.py LLDIS > ../../src/main/resources/instructive/parse/x86/opcodes.txt

LLDIS is a program that reads one hex-encoded byte string per line and prints
"<length>\\t<text>" using LLVM's C disassembler API (LLVMDisasmInstruction, Intel syntax,
hex immediates), or "0\\t" when LLVM can't decode it: lldis.cpp, next to this script.

Opcodes of the legacy 0F map that the disassembler decodes by hand (general purpose
instructions) are skipped; GENERAL_0F lists them and must match the decoder.
"""
import collections
import re
import subprocess
import sys

GENERAL_0F = set(range(0x00, 0x10)) | set(range(0x18, 0x24)) | set(range(0x30, 0x38)) | set(range(0x40, 0x50)) \
    | set(range(0x80, 0xC2)) | {0x38, 0x3A, 0xC3, 0xC7} | set(range(0xC8, 0xD0)) | {0xFF}
IMM, IMM2 = 0xE5, 0xD6
FILLER = [IMM, IMM2, 0xC7, 0xB8]
DISP = [0x78, 0x56, 0x34, 0x12]
IS4 = IMM >> 4

REGISTERS = {}
for i, n in enumerate("rax rcx rdx rbx rsp rbp rsi rdi".split()):
    REGISTERS[n] = ("q", i)
    REGISTERS["e" + n[1:]] = ("d", i)
    REGISTERS[n[1:]] = ("w", i)
for i, n in enumerate("al cl dl bl spl bpl sil dil".split()):
    REGISTERS[n] = ("b", i)
for i in range(8, 32):
    REGISTERS[f"r{i}"] = ("q", i); REGISTERS[f"r{i}d"] = ("d", i); REGISTERS[f"r{i}w"] = ("w", i); REGISTERS[f"r{i}b"] = ("b", i)
for i in range(32):
    for c in "xyz":
        REGISTERS[f"{c}mm{i}"] = (c, i)
for i in range(8):
    REGISTERS[f"k{i}"] = ("k", i); REGISTERS[f"mm{i}"] = ("m", i); REGISTERS[f"tmm{i}"] = ("t", i)
# Classes whose register numbers ignore the REX/VEX/EVEX extension bits.
LOW3 = {"k", "m", "t"}
SIZES = {"byte": "b", "word": "w", "dword": "d", "qword": "q", "xmmword": "x", "ymmword": "y", "zmmword": "z", "tbyte": "t"}
MEM_RE = re.compile(r"^(?:(\w+) ptr )?\[([^\]]*)\]((?:\{1to\d+\})?)$")


def run(tool, probes):
    data = "".join(bytes(p.bytes).hex() + "\n" for p in probes)
    lines = subprocess.run([tool], input=data, capture_output=True, text=True, check=True).stdout.split("\n")
    out = []
    for line in lines[:len(probes)]:
        n, _, text = line.partition("\t")
        text = re.sub(r"\s+#.*$", "", text)
        out.append((int(n), re.sub(r"\s+", " ", text).strip()))
    return out


class Probe:
    """One encoding, with the register number placed in each field."""

    def __init__(self, enc, m, pp, op, W, L, form, r, b=0, aaa=0, z=0, imm=IMM, novvvv=False, disp8=False):
        self.key = (enc, m, pp, op)
        self.W, self.L, self.form, self.r, self.b, self.aaa, self.z = W, L, form, r, b, aaa, z
        rm = (r + 2) % 8
        # Instructions without a vvvv operand require vvvv = 1111, that is register 0.
        v = 0 if novvvv else next(x for x in (3, 4, 5, 6, 7, 0, 1, 2) if x not in (r, rm))
        idx = next(x for x in (7, 5, 2, 1, 6) if x not in (r, v))
        if form == "r":
            modrm = [0xC0 | r << 3 | rm]
        elif disp8:
            modrm = [0x44 | r << 3, 0x80 | idx << 3, 0x01]  # to read EVEX's disp8*N scale
        else:
            modrm = [0x84 | r << 3, 0x80 | idx << 3] + DISP
        tail = [imm] + FILLER[1:]
        if enc == "L":
            prefix = {0: [], 1: [0x66], 2: [0xF3], 3: [0xF2]}[pp]
            body = prefix + [0x45 | W << 3] + {1: [0x0F], 2: [0x0F, 0x38], 3: [0x0F, 0x3A]}[m] + [op]
            self.fields = {"R": 8 + r, "M": 8 + rm}
        elif enc == "V":
            body = [0xC4, 0xE0 | m, W << 7 | (~v & 15) << 3 | L << 2 | pp, op]
            self.fields = {"R": r, "M": rm, "V": v, "S": imm >> 4}
        else:
            body = [0x62, 0xF0 | m, W << 7 | (~v & 15) << 3 | 4 | pp, z << 7 | L << 5 | b << 4 | 8 | aaa, op]
            self.fields = {"R": r, "M": rm, "V": v, "S": imm >> 4}
        if form != "r":
            del self.fields["M"]
        if novvvv and "V" in self.fields:
            del self.fields["V"]
        self.prefix_length = len(body) - 1
        self.bytes = body + modrm + tail
        self.modrm_length = len(modrm)
        self.imm = imm


def operand(text, probe):
    """Abstracts one printed operand as (source, detail): the field it came from and its class."""
    deco = ""
    m = re.match(r"^(.*?)((?: \{k\d\})?)((?: \{z\})?)$", text)
    text, mask, zero = m.group(1), m.group(2), m.group(3)
    flags = ("K" if mask else "") + ("Z" if zero else "")
    if re.fullmatch(r"\{k\d\}", text):
        return ("KM", "", "")
    if text in ("{rn-sae}", "{rd-sae}", "{ru-sae}", "{rz-sae}"):
        return ("RC", "", flags)
    if text == "{sae}":
        return ("SAE", "", flags)
    mm = MEM_RE.match(text)
    if mm:
        size = SIZES.get(mm.group(1) or "", "-") if mm.group(1) is None or mm.group(1) in SIZES else "?" + mm.group(1)
        vsib = re.search(r"\*([xyz])mm\d+", mm.group(2))
        return ("A", size + (vsib.group(1).upper() if vsib else "") + mm.group(3), flags)
    if re.fullmatch(r"-?0x[0-9a-f]+", text):
        value = int(text, 16)
        for name, imm in (("I", probe.imm), ("J", IMM2)):
            if value == imm:
                return (name, "u", flags)
            if value == imm - 256:
                return (name, "s", flags)
        if "S" in probe.fields and value == probe.imm & 15:
            return ("I4", "", flags)  # the low bits of the byte that holds an is4 register
        return ("C" + text, "", flags)
    if text in REGISTERS:
        cls, num = REGISTERS[text]
        for name, value in probe.fields.items():
            if num == value and not (cls in LOW3 and name == "S"):
                return (name, cls, flags)
        if cls in LOW3:
            for name, value in probe.fields.items():
                if name != "S" and num == value & 7:
                    return (name, cls, flags)
        return ("=" + text, "", flags)
    return ("?" + text, "", flags)


def template(probe, result):
    """(mnemonic, operand sources, operand details) or None."""
    length, text = result
    if length == 0:
        return None
    parts = text.split(" ")
    i = 1
    if parts[0] in ("{vex}", "{evex}"):
        i = 2
    mnemonic = " ".join(parts[:i])
    rest = " ".join(parts[i:])
    ops = [operand(o, probe) for o in rest.split(", ")] if rest else []
    imm_bytes = sum(1 for o in ops if o[0] in ("I", "J")) + (1 if any(o[0] == "S" for o in ops) else 0)
    modrm = probe.modrm_length
    if length != probe.prefix_length + 1 + modrm + imm_bytes:
        if length == probe.prefix_length + 1 + imm_bytes:
            modrm = 0
        else:
            return ("!length", (str(length),), ())
    sources = tuple((o[0], o[2]) for o in ops)
    details = tuple(o[1] for o in ops)
    return (mnemonic + ("" if modrm else "|nomodrm"), sources, details)


LENGTHS = {"L": (0,), "V": (0, 1), "E": (0, 1, 2, 3)}
MASKS = {(0, 0): "0", (1, 0): "k", (1, 1): "z", (0, 1): "Z"}


def specs_for(enc):
    """(key, W, L, form, reg) for every combination to probe."""
    maps = {"L": (1, 2, 3), "V": (1, 2, 3), "E": (1, 2, 3, 5, 6)}[enc]
    lengths = {"L": (0,), "V": (0, 1), "E": (0, 1, 2)}[enc]
    for m in maps:
        for pp in range(4):
            for op in range(256):
                if enc == "L" and m == 1 and op in GENERAL_0F:
                    continue
                for W in (0, 1):
                    for L in lengths:
                        for form in ("r", "m"):
                            for r in range(8):
                                yield ((enc, m, pp, op), W, L, form, r)


def main():
    tool = sys.argv[1]
    lines = []
    for enc in ("L", "V", "E"):
        # Try with a vvvv operand, then without; EVEX also with a mask, which gathers require.
        attempts = [(False, 0)]
        if enc == "E":
            attempts.append((False, 1))
        if enc != "L":
            attempts.append((True, 0))
        if enc == "E":
            attempts.append((True, 1))
        found = {}
        pending = list(specs_for(enc))
        for novvvv, aaa in attempts:
            probes = [Probe(*key, W, L, form, r, aaa=aaa, novvvv=novvvv) for key, W, L, form, r in pending]
            remaining = []
            for spec, p, res in zip(pending, probes, run(tool, probes)):
                t = template(p, res)
                if t is None:
                    remaining.append(spec)
                else:
                    found[spec] = (strip_mask(t), novvvv)
            pending = remaining
        valid = collections.defaultdict(dict)
        if enc != "E":
            for (key, W, L, form, r), (t, novvvv) in found.items():
                valid[(key, form)][(W, L, r)] = (t, "V" if novvvv else "")
        else:
            # Masking, zeroing, broadcast and rounding variants of every valid encoding.
            extra = []
            seen = set()
            for (key, W, L, form, r), (t, novvvv) in found.items():
                for b in (0, 1):
                    # With EVEX.b in register form, L'L is the rounding mode: try them all.
                    for L2 in ((0, 1, 2, 3) if (b and form == "r") else (L,)):
                        if (key, W, L2, form, r, b) in seen:
                            continue
                        seen.add((key, W, L2, form, r, b))
                        for aaa, z in MASKS:
                            extra.append(Probe(*key, W, L2, form, r, b=b, aaa=aaa, z=z, novvvv=novvvv))
            evex = collections.defaultdict(dict)
            for p, res in zip(extra, run(tool, extra)):
                t = template(p, res)
                if t is None:
                    continue
                entry = evex[(p.key, p.form + ("b" if p.b else ""))].setdefault((p.W, p.L, p.r), [None, set(), p])
                entry[1].add(MASKS[(p.aaa, p.z)])
                if entry[0] is None or (p.aaa, p.z) == (0, 0):
                    entry[0] = strip_mask(t)
            # The disp8*N scale of each memory form, from a displacement of 1.
            scaled = [(k, wlr, p) for k, cases in evex.items() for wlr, (t, ms, p) in cases.items() if p.form == "m"]
            probes = [Probe(*p.key, p.W, p.L, "m", p.r, b=p.b, aaa=p.aaa, z=p.z, novvvv="V" not in p.fields, disp8=True)
                      for k, wlr, p in scaled]
            scale = {}
            for (k, wlr, p), (n, text) in zip(scaled, run(tool, probes)):
                m = re.search(r" \+ (0x[0-9a-f]+)\]", text)
                scale[(k, wlr)] = int(m.group(1), 16) if n and m else 0
            for k, cases in evex.items():
                for wlr, (t, ms, p) in cases.items():
                    flags = "".join(sorted(ms)) + ("V" if "V" not in p.fields else "")
                    if (k, wlr) in scale:
                        mnemonic, sources, details = t
                        details = tuple(d + f"@{scale[(k, wlr)]}" if src == "A" else d
                                        for (src, _), d in zip(sources, details))
                        t = (mnemonic, sources, details)
                    valid[k][wlr] = (t, flags)
        lines.extend(merge(enc, valid))
    imm_names(tool, lines)
    print("# Generated by parse/tools/x86/opcodes.py from LLVM's disassembler; do not edit.")
    print("# enc map pp opcode form W-set L-set reg-set [flags] | mnemonic | operands | immediate names")
    for line in lines:
        print(format_line(line))


def strip_mask(t):
    mnemonic, sources, details = t
    return (mnemonic, tuple((s, f.replace("K", "").replace("Z", "")) for s, f in sources), details)


def merge(enc, valid):
    lengths = LENGTHS[enc]
    out = []
    for (key, form), cases in sorted(valid.items()):
        # Per (W, r): merge across L when the structure agrees.
        groups = collections.defaultdict(dict)
        for (W, L, r), (t, mask) in cases.items():
            if t[0] == "!length":
                print(f"length mismatch {key} {form} W{W} L{L} r{r}: {t}", file=sys.stderr)
                continue
            groups[(W, r, t[0], t[1], mask)][L] = t[2]
        rows = collections.defaultdict(list)
        for (W, r, mnemonic, sources, mask), by_l in groups.items():
            ls = "".join(str(L) for L in lengths if L in by_l)
            details = tuple("|".join(by_l[L][i] for L in lengths if L in by_l) for i in range(len(sources)))
            rows[(mnemonic, sources, mask, ls, details, W)].append(r)
        by_w = collections.defaultdict(list)
        for (mnemonic, sources, mask, ls, details, W), rs in rows.items():
            by_w[(mnemonic, sources, mask, ls, details, "".join(map(str, sorted(rs))))].append(W)
        for (mnemonic, sources, mask, ls, details, rset), ws in by_w.items():
            out.append({"enc": enc, "map": key[1], "pp": key[2], "op": key[3], "form": form,
                        "W": "".join(map(str, sorted(ws))), "L": ls, "reg": rset, "mask": mask,
                        "mnemonic": mnemonic, "sources": sources, "details": details, "imm": ""})
    return out


def imm_names(tool, lines):
    """Finds immediates that LLVM folds into the mnemonic, such as cmpeqps or vpcmpltd."""
    todo = [line for line in lines if any(s == "I" for s, _ in line["sources"])]
    probes = []
    for line in todo:
        W, L, r = int(line["W"][0]), int(line["L"][0]), int(line["reg"][0])
        form = line["form"].rstrip("b")
        b = 1 if line["form"].endswith("b") else 0
        aaa = 1 if line["enc"] == "E" and "0" not in line["mask"] else 0
        for imm in range(256):
            probes.append(Probe(line["enc"], line["map"], line["pp"], line["op"], W, L, form, r, b=b, aaa=aaa, imm=imm,
                                novvvv="V" in line["mask"]))
    results = run(tool, probes)
    for n, line in enumerate(todo):
        names = []
        for imm in range(256):
            length, text = results[n * 256 + imm]
            mnemonic = " ".join(text.split(" ")[:2] if text.startswith("{") else text.split(" ")[:1])
            if length and mnemonic != line["mnemonic"].split("|")[0]:
                names.append(f"{imm:02x}={mnemonic}")
        line["imm"] = ",".join(names)


def format_line(line):
    ops = []
    for (source, flags), detail in zip(line["sources"], line["details"]):
        ops.append(source + (":" + detail if detail else "") + flags)
    return "\t".join([
        f"{line['enc']} {line['map']} {line['pp']} {line['op']:02x} {line['form']} W{line['W']} L{line['L']} R{line['reg']}"
        + (f" {line['mask']}" if line["mask"] else ""),
        line["mnemonic"], " ".join(ops), line["imm"]])


if __name__ == "__main__":
    main()
