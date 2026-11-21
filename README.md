# instructive

instructive is a sampling profiler for the JVM that finds the hottest JIT-compiled
methods and gives you an instruction-level profile of each one: which machine
instructions inside the method the CPU time is spent on.

## Why

Most JVM profilers sample stack traces. On each sample they unwind the stack and
record how the current frame was reached, which answers *"which call paths are
expensive?"*. That is the right question for a lot of performance work, but it stops
at the frame. Once you know a method is hot, a stack trace can't tell you what is
going on inside it: which loop, which load, which branch, or whether the code the JIT
produced is the code you expected.

instructive focuses on what goes on *inside* the frame rather than on how the frame
was reached. It doesn't unwind stacks at all. On each sample it records only the
interrupted program counter, attributed to the compiled method that contains it. That
yields two things:

- **The hottest compiled methods.** Each compilation (method, tier, compile id) is
  counted separately, so a C1 and a C2 version of the same method are distinguished.
- **An instruction profile for each of them.** Samples are counted per instruction, and
  the method's machine code can be fetched and disassembled to read the profile against.

### Goals

- **Lower CPU overhead than a stack trace sampling agent.** The signal handler does no
  unwinding and never reads JVM metadata or the code heap. It does one lookup in a
  lock-free radix tree and two atomic increments.
- **Memory overhead comparable to a stack trace sampling agent.** Instead of storing
  stack traces, instructive keeps a 16-bit counter per possible instruction start in
  live compiled code (every 4 bytes on AArch64, every byte on x86), plus a small header
  and the method's name. Space is reused as methods are unloaded. In a test with heavy
  class churn on AArch64, the storage settled at about 950 KB for 1.2 MB of live
  compiled code.
- **Online use.** This is a similar view to what JMH's `perfasm` profiler gives you
  for a benchmark: hot regions of the generated assembly. JMH does that offline, for
  benchmarks, using external tools. instructive aims to provide it from inside a running
  application, with no external tools, while the application does its real work.

These are design goals. The overheads haven't been benchmarked against other agents yet.

## Example

A tight loop, profiled for a second:

```java
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;

public class Example {
    static long hot(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000, x = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 100_000; i++) {
                x = x * 31 + i;
            }
        }
        return x;
    }

    public static void main(String[] args) {
        for (int i = 0; i < 100; i++) {
            hot(1); // warm up, so the loop is JIT compiled
        }
        Sampler.start();
        long result = hot(1000);
        Sampler.stop();

        InstructionProfileFormatter formatter = new InstructionProfileFormatter().allInstructions();
        Sampler.forEachHottestMethod(1, profile -> formatter.format(profile.snapshot(), System.out));
        if (result == 42) {
            System.out.println(result);
        }
    }
}
```

Run with the agent jar (and the `parse` jar, which has the disassembler) on the class path:

```
java --enable-native-access=ALL-UNNAMED -javaagent:agent.jar -cp agent.jar:parse.jar:. Example
```

The output, on an Apple Silicon Mac, abridged around the inner loop:

```
Example.hot(J)J
  tier 4 (C2), compile id 349, 632 bytes at 0x00000001168d4f00, 88 samples

  pc                  bytes        instruction                   count        %
  ...
  0x00000001168d4fcc  1f 20 03 d5  nop
  0x00000001168d4fd0  eb 03 0e 2a  mov w11, w14                      1    1.14%
  0x00000001168d4fd4  aa ea 7b d3  lsl x10, x21, #5
  0x00000001168d4fd8  6e 7d 40 93  sxtw x14, w11
  0x00000001168d4fdc  4a 01 15 cb  sub x10, x10, x21
  0x00000001168d4fe0  4a 01 0e 8b  add x10, x10, x14
  ...
  0x00000001168d5080  4c e9 7b d3  lsl x12, x10, #5                  1    1.14%
  0x00000001168d5084  8a 01 0a cb  sub x10, x12, x10                 1    1.14%
  0x00000001168d5088  4a 01 0e 8b  add x10, x10, x14                 1    1.14%
  0x00000001168d508c  4c e9 7b d3  lsl x12, x10, #5                  5    5.68%
  0x00000001168d5090  8a 01 0a cb  sub x10, x12, x10                10   11.36%
  0x00000001168d5094  4a 01 0e 8b  add x10, x10, x14                 8    9.09%
  0x00000001168d5098  6e 41 00 11  add w14, w11, #0x10               6    6.82%
  0x00000001168d509c  55 01 17 8b  add x21, x10, x23                 5    5.68%
  0x00000001168d50a0  df 01 0d 6b  cmp w14, w13                     14   15.91%
  0x00000001168d50a4  6b f9 ff 54  b.lt 0x1168d4fd0 <+0xd0>
  0x00000001168d50a8  8c 1b 40 f9  ldr x12, [x28, #0x30]
  0x00000001168d50ac  9f 01 40 b9  ldr wzr, [x12]
  ...
```

How to read it:
- **The inner loop** runs from `+0xd0` to the back-edge `b.lt ... <+0xd0>`. C2 has
  unrolled it 16 times (`add w14, w11, #0x10` steps `i` by 16), and each `x * 31 + i` is
  a `lsl #5`, a `sub` and an `add`.
- **The samples** fall inside the loop, heaviest on the compare at its end.
- **After the loop,** `ldr wzr, [x12]` is HotSpot's safepoint poll.

On x86-64 Linux the listing is in Intel syntax. Here is a similar loop on an Intel Xeon
(only sampled instructions, abridged):

```
Demo.sum([I)J
  tier 4 (C2), compile id 352, 624 bytes at 0x00007f60dba92d00, 297 samples

  pc                  bytes           instruction        count        %
  0x00007f60dba92dbd  49 c1 e0 05     shl r8, 0x5           14    4.71%
  0x00007f60dba92ddb  4c 03 f2        add r14, rdx          12    4.04%
  ...
  0x00007f60dba92e4a  4d 8b d8        mov r11, r8           14    4.71%
  0x00007f60dba92e4d  49 c1 eb 03     shr r11, 0x3           2    0.67%
  0x00007f60dba92e51  49 33 c3        xor rax, r11          14    4.71%
  0x00007f60dba92e54  49 03 c0        add rax, r8           14    4.71%
  0x00007f60dba92e57  48 8b f8        mov rdi, rax          16    5.39%
  0x00007f60dba92e5a  48 c1 ef 03     shr rdi, 0x3           1    0.34%
  0x00007f60dba92e5e  41 83 c2 08     add r10d, 0x8         18    6.06%
```

## Finding long dependency chains

An instruction profile shows where the time goes, but not why. One common reason a hot
loop is slow is a long *dependency chain*: each instruction needs the result of the one
before it. A modern core can run several instructions per cycle, but not instructions
that wait on each other, so a loop dominated by one chain runs at the chain's latency
rather than at the core's throughput. The `analyse` module finds such chains
automatically, from the instruction stream alone.

### The hash loop

`Arrays.hashCode(int[])` computes `h = 31 * h + a[i]`. Each element's hash depends on
the previous element's, so however many elements C2 handles per loop iteration, they
form a single chain. With the vectorised intrinsic disabled
(`-XX:-UseVectorizedHashCodeIntrinsic`), C2 on AArch64 unrolls the loop 8 times and
computes `31 * h` as `(h << 5) - h`. The first two of the eight elements look like this:

```
lsl w11, w4, #5         // h << 5            (h is in w4)
ldr w12, [x1, #0x10]    // a[i]
sub w11, w11, w4        // (h << 5) - h      needs the lsl
add w12, w11, w12       // + a[i]            needs the sub: h for the next element
lsl w14, w12, #5        //                   needs the add
ldr w11, [x1, #0x14]    // a[i + 1]
sub w12, w14, w12       //                   needs the lsl
add w12, w12, w11       //                   needs the sub
...                     // six more elements
add w4, w12, w11        // h for the next iteration
```

The loads don't depend on `h` and can run ahead. The `lsl`, `sub` and `add` of each
element can't: each waits for the one before.

### Turning the stream into chains

`DependencyGraph` walks the method's instructions in address order and keeps track of
the last instruction to write each register. For every instruction it links each
register it reads to that register's most recent writer: the closest predecessor that
produced its input. Applied recursively, the links form chains back through the code.
An instruction's chain length is one more than the longest chain among the instructions
it depends on.

Two things make this work:
- **Register usage.** `Aarch64RegisterModel` derives which registers each decoded
  instruction reads and writes, and treats `w4` and `x4` as the same register.
- **Undecoded instructions.** An instruction the decoder can't read might write any
  register, so it breaks every chain through it.

In the hash loop each `add` links back to its `sub`, each `sub` to its `lsl`, and each
`lsl` to the previous element's `add`. That gives a chain three instructions longer for
every element, 24 across the unrolled body.

### Flagging the chain

`LongDependencyChainRule` reports every chain of at least 16 instructions that no later
instruction extends. Here is its top finding for the compiled
`ArraysSupport.hashCode([IIII)I`, abridged, with offsets from the start of the method:

```
hottest chain: 32 instructions, 0 samples on it, 71 in the code it spans
  +0x028  mov x10, x1
  +0x078  add x11, x10, w2, sxtw #2
  +0x07c  ldr w12, [x11, #0x10]
  +0x08c  add w4, w13, w12
  +0x0c4  lsl w11, w4, #5        <- the unrolled loop body starts here
  +0x0cc  sub w11, w11, w4
  +0x0d0  add w12, w11, w12
  ...                             24 instructions: lsl, sub, add for each of 8 elements
  +0x144  add w4, w12, w11       <- and ends here, carrying h round the loop
  +0x170  lsl w11, w4, #5
  +0x174  sub w13, w11, w4
  +0x17c  add w4, w13, w12
  +0x188  mov w0, w4
```

The chain's core is the 24-instruction loop body. Because links follow the code's
layout rather than its control flow, the chain also takes in the peeled iteration
before the loop and the tail after it.

None of the method's 71 samples landed on the chain's own instructions. 84% of them
landed on one of the loads instead. That's *skid*: a sample lands on an instruction
waiting near the stall, not on the instructions causing it. So the rule ranks chains by
`spanSamples`, the samples on every instruction between a chain's first and last. This
chain spans all 71, which puts it first.

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<DependencyChain> chains = new LongDependencyChainRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
```

The analysis tracks registers only. Condition flags are left out, so a compare followed
by a branch isn't counted as a data dependency, and so are memory dependencies through
stores and loads.

## Using it

Load the native library, either with the Java agent (`-javaagent:agent.jar`) or by
using `instructive.agent.Sampler` directly, which loads it on first use. Then:

```java
Sampler.start();                       // start CPU sampling
// ... run the workload ...
Sampler.stop();

// The k hottest compiled methods, hottest first.
Sampler.forEachHottestMethod(10, profile -> {
    // `profile` is a flyweight over live native memory, valid only inside this lambda.
    // Keep a snapshot if you need it afterwards.
    InstructionProfile snapshot = profile.snapshot();
    // name(), compLevel(), compileId(), address(), total(), count(offset), counts(),
    // machineCode() ...
});

Sampler.forEachMethod(profile -> { /* every loaded compiled method */ });
Sampler.kindCounts();                  // samples in compiled code, the interpreter, stubs, native code
Sampler.resetCounts();                 // start a new window, e.g. once a minute
```

Per-instruction counts are 16 bits and saturate after about 11 minutes of samples on
a single instruction, so continuous profiling should reset regularly.

## How it works

- **Sampling:** a CPU-time timer delivers `SIGPROF` every 10 ms of CPU time: one POSIX
  timer per thread on Linux, and a process-wide `setitimer` on macOS. The signal
  handler takes the program counter from the signal context.
- **Tracking compiled code:** JVMTI `CompiledMethodLoad`/`CompiledMethodUnload` events
  keep a concurrent radix tree, keyed by code address, of every loaded compiled
  method. Its leaf records hold the name, compile tier and id, a total sample count
  and a dense array of per-PC counters. The handler looks the PC up there, without
  locks or allocation. Freed records are reclaimed safely using epochs.
- **Zero-copy reads:** the records live in one native memory region that Java reads
  in place, through the `InstructionProfile` flyweight. Selecting the top k is a
  single pass with a bounded heap.
- **Machine code on demand:** `machineCode()` copies a method's code from the JVM's
  code cache, using layout information from HotSpot's VMStructs. It only does so
  after checking that the nmethod found there has exactly the profile's address, size
  and compile id.
- **Disassembly:** the `parse` module has AArch64 and x86-64 decoders, written in Java,
  whose output matches `llvm-objdump` (Intel syntax on x86-64). The x86-64 one covers
  the general purpose, x87, SSE, AVX and AVX-512 instructions; its vector instruction
  table is generated from LLVM. `InstructionProfileFormatter` uses them to print the
  listings above.

## Building

```
./gradlew build                  # builds everything and runs the tests
scripts/test-linux.sh            # the same on Linux x86-64 and AArch64, in Docker
DISTRO=alpine JDK=17 scripts/test-linux.sh arm64   # musl, JDK 17, AArch64
```

Requires JDK 17 or newer and a C++17 compiler. The native library is packaged into the
agent jar under `META-INF/native-libs/<os>-<arch>/`. CI tests on Ubuntu (glibc) and
Alpine (musl), on JDK 17, 21 and 25, on x86-64 and AArch64. It publishes a jar with the
Linux libraries for all four combinations of libc and architecture. macOS isn't
published: build it locally.

## Status and limitations


- **Platforms:** developed on macOS on Apple Silicon. Linux is tested on x86-64 and
  AArch64, with glibc and musl, in Docker (`scripts/test-linux.sh`), both locally and in
  CI. Locally on an Apple Silicon host the x86-64 runs are emulated; in CI they run on
  x86 hardware.
- **Disassembly** covers AArch64 (scalar; vector SIMD is only classified) and x86-64
  (APX, XOP and 3DNow! aren't decoded).
- **JDK versions:** 17, 21 and 25 are tested. The code cache layout comes from
  VMStructs, whose entries change between releases, and the reader handles the
  differences between these versions. On macOS, JDKs before 25 don't export the
  VMStructs symbols at all.
- **Interpreted code** is counted, but not attributed to methods.
