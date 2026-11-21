"""Usage: vectors.py N PREFIX AGREED... writes PREFIX-vectors.tsv and PREFIX-undecodable.txt.

Samples agreed reference lines into test vectors: up to N per instruction shape and source.

A shape is the mnemonic, the encoding class and each operand's kind (register class,
memory size with broadcast/VSIB, immediate), so every form is represented without
keeping millions of near-duplicates."""
import re, sys, random
random.seed(11)
N = int(sys.argv[1])
CLASSES = [(r"^[xyz]mm\d+$", lambda r: r[0] + "mm"), (r"^k\d$", lambda r: "k"), (r"^mm\d$", lambda r: "mm"),
           (r"^tmm\d$", lambda r: "tmm"), (r"^st(\(\d\))?$", lambda r: "st"), (r"^(cr|dr)\d+$", lambda r: r[:2]),
           (r"^[c-gs]s$", lambda r: "seg"), (r"^r\d+b$|^[a-d]l$|^[sd]il$|^[sb]pl$", lambda r: "r8"), (r"^[a-d]h$", lambda r: "r8h"),
           (r"^r\d+w$|^[a-d]x$|^[sd]i$|^[sb]p$", lambda r: "r16"), (r"^r\d+d$|^e[a-d]x$|^e[sd]i$|^e[sb]p$", lambda r: "r32"),
           (r"^r\d+$|^r[a-d]x$|^r[sd]i$|^r[sb]p$", lambda r: "r64")]

def operand(op):
    deco = "".join(sorted(set(re.findall(r"\{(k|z)", op))))
    op = re.sub(r" ?\{k\d\}| ?\{z\}", "", op)
    if "[" in op:
        size = op.split(" ptr")[0] if " ptr" in op else "-"
        vsib = re.search(r"\*?([xyz])mm", op.split("[")[1])
        return "M" + size + ("{b}" if "{1to" in op else "") + ("V" + vsib.group(1) if vsib else "") + deco
    if re.fullmatch(r"-?0x[0-9a-f]+|\d+", op):
        return "I"
    if op.startswith("{"):
        return op
    for pattern, f in CLASSES:
        if re.match(pattern, op):
            return f(op) + deco
    return op + deco

def shape(text, hexbytes):
    parts = text.split(" ")
    i = 0
    while i < len(parts) and parts[i] in ("lock", "rep", "repne", "addr32", "{vex}", "{evex}"):
        i += 1
    mnemonic = " ".join(parts[:i + 1])
    ops = " ".join(parts[i + 1:])
    b = bytes.fromhex(hexbytes)
    j = 0
    while j < len(b) and (b[j] in (0x66, 0x67, 0xF0, 0xF2, 0xF3, 0x2E, 0x3E, 0x26, 0x36, 0x64, 0x65) or b[j] & 0xF0 == 0x40):
        j += 1
    enc = {0x62: "evex", 0xC4: "vex3", 0xC5: "vex2"}.get(b[j] if j < len(b) else 0, "legacy")
    return mnemonic + "|" + enc + "|" + ",".join(operand(o) for o in ops.split(", ") if o)

valid, invalid = [], []
taken = set()
for n, path in enumerate(sys.argv[3:]):
    seen = {}
    for line in open(path):
        f = line.rstrip("\n").split("\t")
        if f[2] == "<invalid>":
            invalid.append(f[1])
            continue
        seen.setdefault(shape(f[2], f[1]), []).append(line.rstrip("\n"))
    new = 0
    for key, lines in seen.items():
        # The first file (code HotSpot compiled) keeps N examples of each shape; the
        # others add one example of each shape not seen yet.
        if n == 0:
            valid.extend(random.sample(lines, min(N, len(lines))))
        elif key not in taken:
            valid.append(random.choice(lines))
            new += 1
        taken.add(key)
    print(path, len(seen), "shapes,", new, "new")
unique = sorted(set(valid), key=lambda l: (l.split("\t")[2], l))
with open(sys.argv[2] + "-vectors.tsv", "w") as out:
    out.write("\n".join(unique) + "\n")
random.shuffle(invalid)
with open(sys.argv[2] + "-undecodable.txt", "w") as out:
    out.write("\n".join(sorted(set(invalid[:3000]))) + "\n")
print(len(unique), "vectors")
