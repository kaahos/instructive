"""Random encodings of every opcode-table entry, with random registers, memory forms, masks and immediates.

Usage: fuzz_table.py PER_ENTRY SEED OPCODES > fuzz-table.tsv"""
import os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import write
random.seed(int(sys.argv[2]))
K = int(sys.argv[1])

def memory():
    mod = random.choice([0, 1, 2])
    rm = random.choice([0, 1, 2, 3, 4, 4, 4, 5, 6, 7])
    out = []
    if rm == 4:
        out.append(random.randrange(256))
        if mod == 0 and (out[0] & 7) == 5:
            out += [random.randrange(256) for _ in range(4)]
    if mod == 0 and rm == 5:
        out += [random.randrange(256) for _ in range(4)]
    out += [random.randrange(256) for _ in range({0: 0, 1: 1, 2: 4}[mod])]
    return mod, rm, out

items = []
for line in open(sys.argv[3]):
    if line.startswith("#"):
        continue
    key = line.split("\t")[0].split(" ")
    enc, m, pp, op, form = key[0], int(key[1]), int(key[2]), int(key[3], 16), key[4]
    ws, ls, regs = key[5][1:], key[6][1:], key[7][1:]
    flags = key[8] if len(key) > 8 else ""
    for _ in range(K):
        W, L, reg = int(random.choice(ws)), int(random.choice(ls)), int(random.choice(regs))
        if form.startswith("r"):
            modrm = [0xC0 | reg << 3 | random.randrange(8)]
        else:
            mod, rm, rest = memory()
            modrm = [mod << 6 | reg << 3 | rm] + rest
        R, X, B, R2, V2 = (random.random() < 0.5 for _ in range(5))
        vvvv = 0 if "V" in flags and random.random() < 0.9 else random.randrange(16)
        if enc == "L":
            pfx = [[], [0x66], [0xF3], [0xF2]][pp]
            rex = [0x40 | W << 3 | R << 2 | X << 1 | B] if (W or random.random() < 0.5) else []
            body = pfx + rex + {1: [0x0F], 2: [0x0F, 0x38], 3: [0x0F, 0x3A]}[m] + [op]
        elif enc == "V":
            if m == 1 and not W and not X and not B and random.random() < 0.5:
                body = [0xC5, (not R) << 7 | (~vvvv & 15) << 3 | L << 2 | pp, op]
            else:
                body = [0xC4, (not R) << 7 | (not X) << 6 | (not B) << 5 | m, W << 7 | (~vvvv & 15) << 3 | L << 2 | pp, op]
        else:
            masks = [c for c in flags if c in "0kzZ"] or ["0"]
            mk = random.choice(masks)
            aaa = 0 if mk in "0Z" else random.randrange(1, 8)
            z = 1 if mk in "zZ" else 0
            b = 1 if form.endswith("b") else 0
            body = [0x62, (not R) << 7 | (not X) << 6 | (not B) << 5 | (not R2) << 4 | m,
                    W << 7 | (~vvvv & 15) << 3 | 4 | pp, z << 7 | L << 5 | b << 4 | (not V2) << 3 | aaa, op]
        b = bytes(body + modrm + [random.randrange(256) for _ in range(3)])
        items.append((random.randrange(1 << 47), b[:15]))
write(items, sys.stdout)
