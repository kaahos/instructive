# x86-64 decoder tools

These scripts generate `X86Disassembler`'s opcode table and its test vectors from LLVM,
and check the decoder against LLVM at scale. They need an x86-64 machine with LLVM
(headers and static libraries, for `llvm-config`), Python 3 and JDK 25.

Build the probe tool, which decodes one hex byte string per line with LLVM's C API
(Intel syntax, hex immediates):

```
clang++ -O2 lldis.cpp $(llvm-config --cxxflags --ldflags --libs x86disassembler x86desc x86info mc support --system-libs) -o lldis
export LLDIS=$PWD/lldis
```

## Opcode table

```
python3 opcodes.py $LLDIS > ../../src/main/resources/instructive/parse/x86/opcodes.txt
```

`opcodes.py` probes every SSE/VEX/EVEX opcode, prefix, W, vector length, ModRM.reg and
operand form, then masking, broadcast, rounding, disp8×N and the immediates LLVM folds
into mnemonics. It takes a few minutes. The general purpose and x87 instructions are
decoded by hand in `X86Disassembler`; `GENERAL_0F` in `opcodes.py` lists the 0F opcodes
left to it.

## Reference data

- **HotSpot code:** run `HotSpotCode.java` under the agent with several flag sets
  (`-XX:UseAVX=0..3`, `-XX:TieredStopAtLevel=1`, different GCs...). Concatenate the
  unique methods into a blob, then disassemble it:
  `llvm-objcopy -I binary -O elf64-x86-64 --rename-section=.data=.text,code,alloc,load,readonly blob.bin blob.o`,
  `llvm-objdump -d -M intel --no-leading-headers blob.o | python3 objdump_ref.py > ref.tsv`.
- **Sweep:** `python3 sweep.py > sweep.tsv`, every one-byte and 0F opcode with common
  prefixes and all ModRM values.
- **Random:** `python3 fuzz.py 2000000 SEED > fuzz.tsv`, and with `simd` as the third
  argument for VEX/EVEX only.
- **Table:** `python3 fuzz_table.py 100 SEED opcodes.txt > table.tsv`, random encodings of
  every table entry.

## Checking and vectors

```
java -cp parse.jar RefCheck.java ref.tsv 2 40 agreed-ref.tsv
```

prints the mismatches by mnemonic, with examples, and writes the lines both decoders agree
on. `vectors.py 3 x86 agreed-ref.tsv agreed-table.tsv agreed-sweep.tsv ...` samples the
agreed lines into `x86-vectors.tsv` (every instruction shape once, three for HotSpot code)
and `x86-undecodable.txt`, the test resources in
`src/test/resources/instructive/parse/x86/`.
