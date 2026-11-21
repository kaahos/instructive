"""Shared by the reference generators: runs LLVM (the lldis tool named by $LLDIS, default
./lldis) and writes reference lines "address<TAB>bytes<TAB>text", or <invalid>."""
import os, re, subprocess
LLDIS = os.environ.get("LLDIS", "./lldis")
BRANCHES = {"jmp", "call", "loop", "loope", "loopne", "jrcxz", "jecxz", "xbegin"} | {"j" + c for c in "o no b ae e ne be a s ns p np l ge le g".split()}
LONE = {"lock", "xacquire", "xrelease", "rep", "repne", "data16", "addr32", "cs", "ds", "es", "fs", "gs", "ss", "rex", "rex64"}

def run(items):
    data = "".join(f"{b.hex()} @{a:x}\n" for a, b in items)
    lines = subprocess.run([LLDIS], input=data, capture_output=True, text=True).stdout.split("\n")
    res = []
    for line in lines[:len(items)]:
        n, _, t = line.partition("\t")
        t = re.sub(r"\s+#.*$", "", t)
        res.append((int(n), re.sub(r"\s+", " ", t).strip()))
    return res

def absolute(text, address, length):
    parts = text.split(" ")
    if len(parts) >= 2 and parts[-2] in BRANCHES and re.fullmatch(r"-?0x[0-9a-f]+", parts[-1]):
        parts[-1] = f"0x{(address + length + int(parts[-1], 16)) & (2**64 - 1):x}"
    return " ".join(parts)

def write(items, out):
    """Writes reference lines; lone prefixes that LLVM decodes as instructions are skipped,
    except lock, which is merged into the instruction after it."""
    results = run(items)
    locks = [i for i, (n, t) in enumerate(results) if n == 1 and t == "lock"]
    follow = run([(items[i][0] + 1, items[i][1][1:]) for i in locks])
    for i, (n, t) in enumerate(results):
        a, b = items[i]
        if n == 0:
            out.write(f"{a:x}\t{b.hex()}\t<invalid>\n")
        elif not all(word in LONE for word in t.split(" ")):
            out.write(f"{a:x}\t{b[:n].hex()}\t{absolute(t, a, n)}\n")
    for i, (n, t) in zip(locks, follow):
        a, b = items[i]
        if n == 0 or all(word in LONE for word in t.split(" ")) or t.startswith("lock"):
            continue
        out.write(f"{a:x}\t{b[:n + 1].hex()}\tlock {absolute(t, a + 1, n)}\n")
