"""Turns llvm-objdump -M intel output into offset<TAB>bytes<TAB>text, merging lone lock prefixes.

Usage: llvm-objdump -d -M intel --no-leading-headers blob.o | objdump_ref.py > ref.tsv"""
import re, sys
line_re = re.compile(r"^\s*([0-9a-f]+):\s((?:[0-9a-f]{2} )*[0-9a-f]{2})\s*\t(.*)$")
def norm(text):
    text = re.sub(r"\s+<[^>]*>$", "", text)      # symbolic branch annotation
    text = re.sub(r"\s+#.*$", "", text)          # comments such as rip-relative targets
    return re.sub(r"\s+", " ", text).strip()
pending = None
out = sys.stdout
for line in sys.stdin:
    m = line_re.match(line)
    if not m:
        continue
    off, hx, text = int(m.group(1), 16), m.group(2).replace(" ", ""), norm(m.group(3))
    if pending is not None:
        poff, phx = pending
        pending = None
        if poff + len(phx) // 2 == off and text != "<unknown>":
            out.write(f"{poff:x}\t{phx}{hx}\tlock {text}\n")
            continue
        out.write(f"{poff:x}\t{phx}\tlock\n")
    if text == "lock":
        pending = (off, hx)
        continue
    out.write(f"{off:x}\t{hx}\t{text}\n")
