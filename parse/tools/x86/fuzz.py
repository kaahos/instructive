"""Random x86-64 encodings, one prefix per group at most, decoded by LLVM.

Usage: fuzz.py COUNT SEED [simd] > fuzz.tsv"""
import os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import write
random.seed(int(sys.argv[2]) if len(sys.argv) > 2 else 1)
N = int(sys.argv[1])
SIMD = len(sys.argv) > 3 and sys.argv[3] == "simd"
GROUPS = [[0xF0], [0xF2, 0xF3], [0x2E, 0x3E, 0x26, 0x36, 0x64, 0x65], [0x66], [0x67]]

def sample():
    out = []
    kind = random.random() if not SIMD else 0.7 + 0.3 * random.random()
    vexlike = kind >= 0.7
    if not vexlike or random.random() < 0.05:
        chosen = [g for g in GROUPS if random.random() < 0.15]
        if any(0xF2 in g for g in chosen) and any(0x66 in g for g in chosen):
            chosen = [g for g in chosen if 0x66 not in g]
        random.shuffle(chosen)
        out += [random.choice(g) for g in chosen]
    if random.random() < (0.4 if not vexlike else 0.03):
        # Compilers put 66, F2 and F3 right before REX; LLVM is erratic otherwise.
        out = [p for p in out if p not in (0x66, 0xF2, 0xF3)] + [p for p in out if p in (0x66, 0xF2, 0xF3)]
        out.append(0x40 | random.randrange(16))
    if kind < 0.35:
        out.append(random.randrange(256))
    elif kind < 0.55:
        out += [0x0F, random.randrange(256)]
    elif kind < 0.63:
        out += [0x0F, 0x38, random.randrange(256)]
    elif kind < 0.7:
        out += [0x0F, 0x3A, random.randrange(256)]
    elif kind < 0.85:
        if random.random() < 0.3:
            out += [0xC5, random.randrange(256)]
        else:
            m = random.choice([1, 2, 3]) if random.random() < 0.9 else random.randrange(32)
            out += [0xC4, (random.randrange(8) << 5) | m, random.randrange(256)]
        out.append(random.randrange(256))
    else:
        m = random.choice([1, 2, 3, 5, 6]) if random.random() < 0.9 else random.randrange(8)
        p0 = (random.randrange(16) << 4) | m | (8 if random.random() < 0.05 else 0)
        p1 = random.randrange(256) | (4 if random.random() < 0.95 else 0)
        out += [0x62, p0, p1, random.randrange(256), random.randrange(256)]
    out += [random.randrange(256) for _ in range(12)]
    return bytes(out[:15])

items = [(random.randrange(1 << 47), sample()) for _ in range(N)]
write(items, sys.stdout)
