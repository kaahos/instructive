// Reads one hex byte string per line (optionally "@<hex address>") and prints "<length>\t<text>",
// length 0 if LLVM cannot decode it, using LLVM's C disassembler API in Intel syntax with hex immediates.
// Build: clang++ -O2 lldis.cpp $(llvm-config --cxxflags --ldflags --libs x86disassembler x86desc x86info mc support --system-libs) -o lldis
#include <llvm-c/Disassembler.h>
#include <llvm-c/Target.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
int main(int argc, char** argv) {
    LLVMInitializeX86TargetInfo(); LLVMInitializeX86TargetMC(); LLVMInitializeX86Disassembler();
    const char* features = argc > 1 ? argv[1] : "";
    LLVMDisasmContextRef dc = LLVMCreateDisasmCPUFeatures("x86_64-unknown-linux-gnu", "", features, nullptr, 0, nullptr, nullptr);
    if (!dc) { fprintf(stderr, "no context\n"); return 1; }
    LLVMSetDisasmOptions(dc, LLVMDisassembler_Option_AsmPrinterVariant); LLVMSetDisasmOptions(dc, LLVMDisassembler_Option_PrintImmHex);
    char line[4096]; char out[1024];
    while (fgets(line, sizeof line, stdin)) {
        std::vector<uint8_t> bytes; unsigned v; char* p = line; int n;
        while (sscanf(p, "%2x%n", &v, &n) == 1) { bytes.push_back(v); p += n; while (*p == 32) p++; }
        uint64_t addr = 0; char* sp = strchr(line, 64); if (sp) addr = strtoull(sp + 1, nullptr, 16);
        size_t len = LLVMDisasmInstruction(dc, bytes.data(), bytes.size(), addr, out, sizeof out);
        printf("%zu\t%s\n", len, len ? out : "");
    }
    fflush(stdout);
}
