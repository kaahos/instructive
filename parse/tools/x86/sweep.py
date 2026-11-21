"""Every one-byte and 0F opcode with common prefixes and all ModRM values, decoded by LLVM."""
import os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import run, absolute, write
random.seed(7)
PREFIXES = ["", "66", "f3", "f2", "48", "41", "4c", "6648", "f348", "f248", "67", "f0", "6641", "2e"]
items = []
for esc in ["", "0f"]:
    for op in range(256):
        if esc == "0f" and op in (0x38, 0x3A):
            continue
        for p in PREFIXES:
            for modrm in range(256):
                b = bytes.fromhex(p + esc) + bytes([op, modrm]) + bytes(random.randrange(256) for _ in range(10))
                items.append((random.randrange(1 << 47), b))
write(items, sys.stdout)
