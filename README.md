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

## Analysis rules

The `analyse` module has rules that read a method's machine code and instruction profile
and explain what makes a hot loop slow. Each takes the decoded instructions, a register
model and the samples per instruction, and returns its findings hottest first.

| Rule | Finds | Section |
|---|---|---|
| `LongDependencyChainRule` | chains of instructions that each wait for the one before, within an iteration or carried from one iteration to the next | [Finding long dependency chains](#finding-long-dependency-chains) |
| `NotUnrolledLoopRule` | hot loops handling one element per iteration, which C2 hasn't unrolled and so can't vectorise | [Finding loops that aren't unrolled](#finding-loops-that-arent-unrolled) |
| `PointerChasingRule` | loops where each load's address comes from the previous iteration's load, as in walking a linked list | [Finding pointer chasing](#finding-pointer-chasing) |
| `FailedCheckRule` | explicit null checks that throw: nulls have been seen there | [Finding null checks that have failed](#finding-null-checks-that-have-failed) |
| `FailedCheckRule` | explicit range checks that throw, or that a loop kept: out-of-bounds indexes have been seen there, or the loop isn't counted | [Finding range checks that have failed](#finding-range-checks-that-have-failed) |

## Finding long dependency chains

An instruction profile shows where the time goes, but not why. One common reason a hot
loop is slow is a long *dependency chain*: each instruction needs the result of the one
before it. A modern core can run several instructions per cycle, but not instructions
that wait on each other, so a loop dominated by one chain runs at the chain's latency
rather than at the core's throughput. The `analyse` module finds such chains
automatically, from the instruction stream and its control flow.

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
add w12, w11, w12       // + a[i]            needs the sub and the ldr: h for the next element
lsl w14, w12, #5        //                   needs the add
ldr w11, [x1, #0x14]    // a[i + 1]
sub w12, w14, w12       //                   needs the lsl
add w12, w12, w11       //                   needs the sub and the ldr
...                     // six more elements
add w4, w12, w11        // h for the next iteration
```

### The instruction profile

One second of `Arrays.hashCode` on a 65,536-element array, printed by
`InstructionProfileFormatter().allInstructions()` and abridged to the unrolled loop, from
its first instruction to the back-edge `b.lt`, then the safepoint poll:

```
jdk.internal.util.ArraysSupport.hashCode([IIII)I
  tier 4 (C2), compile id 366, 544 bytes at 0x0000000116b1a300, 72 samples

  pc                  bytes        instruction                   count        %
  ...
  0x0000000116b1a3c0  41 c9 22 8b  add x1, x10, w2, sxtw #2          4    5.56%
  0x0000000116b1a3c4  8b 68 1b 53  lsl w11, w4, #5
  0x0000000116b1a3c8  2c 10 40 b9  ldr w12, [x1, #0x10]
  0x0000000116b1a3cc  6b 01 04 4b  sub w11, w11, w4
  0x0000000116b1a3d0  6c 01 0c 0b  add w12, w11, w12
  0x0000000116b1a3d4  8e 69 1b 53  lsl w14, w12, #5
  0x0000000116b1a3d8  2b 14 40 b9  ldr w11, [x1, #0x14]
  0x0000000116b1a3dc  cc 01 0c 4b  sub w12, w14, w12
  0x0000000116b1a3e0  8c 01 0b 0b  add w12, w12, w11                 1    1.39%
  0x0000000116b1a3e4  8e 69 1b 53  lsl w14, w12, #5
  0x0000000116b1a3e8  2b 18 40 b9  ldr w11, [x1, #0x18]
  0x0000000116b1a3ec  cc 01 0c 4b  sub w12, w14, w12
  0x0000000116b1a3f0  8c 01 0b 0b  add w12, w12, w11
  0x0000000116b1a3f4  8b 69 1b 53  lsl w11, w12, #5
  0x0000000116b1a3f8  2e 1c 40 b9  ldr w14, [x1, #0x1c]              1    1.39%
  0x0000000116b1a3fc  6c 01 0c 4b  sub w12, w11, w12
  0x0000000116b1a400  8b 01 0e 0b  add w11, w12, w14
  0x0000000116b1a404  6f 69 1b 53  lsl w15, w11, #5
  0x0000000116b1a408  2c 20 40 b9  ldr w12, [x1, #0x20]              2    2.78%
  0x0000000116b1a40c  eb 01 0b 4b  sub w11, w15, w11
  0x0000000116b1a410  6c 01 0c 0b  add w12, w11, w12
  0x0000000116b1a414  8f 69 1b 53  lsl w15, w12, #5
  0x0000000116b1a418  2b 24 40 b9  ldr w11, [x1, #0x24]              1    1.39%
  0x0000000116b1a41c  ec 01 0c 4b  sub w12, w15, w12
  0x0000000116b1a420  8c 01 0b 0b  add w12, w12, w11
  0x0000000116b1a424  8f 69 1b 53  lsl w15, w12, #5
  0x0000000116b1a428  2b 28 40 b9  ldr w11, [x1, #0x28]              4    5.56%
  0x0000000116b1a42c  ec 01 0c 4b  sub w12, w15, w12
  0x0000000116b1a430  8c 01 0b 0b  add w12, w12, w11
  0x0000000116b1a434  8f 69 1b 53  lsl w15, w12, #5
  0x0000000116b1a438  2b 2c 40 b9  ldr w11, [x1, #0x2c]             59   81.94%
  0x0000000116b1a43c  ec 01 0c 4b  sub w12, w15, w12
  0x0000000116b1a440  42 20 00 11  add w2, w2, #0x8
  0x0000000116b1a444  84 01 0b 0b  add w4, w12, w11
  0x0000000116b1a448  5f 00 00 6b  cmp w2, w0
  0x0000000116b1a44c  ab fb ff 54  b.lt 0x116b1a3c0 <+0xc0>
  0x0000000116b1a450  8b 1b 40 f9  ldr x11, [x28, #0x30]
  0x0000000116b1a454  7f 01 40 b9  ldr wzr, [x11]
  ...
```

The samples are on the loop's loads, and most of all on the eighth element's,
`ldr w11, [x1, #0x2c]` at `+0x138`, with 59 of the 72. Each load's value goes straight
into the `add` that computes the next `h`: the load is part of the chain, and sampling
attributes the chain's cost to it.

### Finding the chain

`DependencyGraph` first builds the method's control flow (`ControlFlowGraph`): basic
blocks, the branches between them, and loops. Here that includes the unrolled loop, which
starts at `+0x0c0` and branches back to it from `+0x14c`. For every register an
instruction reads, it then finds the instructions whose writes can reach it along that
control flow. A write that reaches only round the back-edge is carried from one iteration
to the next. Here `+0x144` writes `w4`, the `h` the next iteration starts from, and
`+0x0c4` and `+0x0cc` read it.

`LongDependencyChainRule` takes the instruction that produces the carried value,
`add w4, w12, w11` at `+0x144`, and walks backwards from it. For each instruction it
follows every operand to the instruction that wrote it within the iteration: from
`+0x144` through `w11` to the load at `+0x138` and through `w12` to the `sub` at
`+0x13c`, and so on back to the start of the loop. Each time the walk reaches a sampled
instruction, it adds that instruction's samples to the chain's total. Instructions on the
longest run that each need the one before, the critical path, are marked `*`:

```
  offset  instruction                   count  total  operands written by
* +0x144  add w4, w12, w11                        0  w11 from +0x138, w12 from +0x13c
* +0x13c  sub w12, w15, w12                       0  w12 from +0x130, w15 from +0x134
  +0x138  ldr w11, [x1, #0x2c]            59     59  x1 from +0x0c0
* +0x134  lsl w15, w12, #5                       59  w12 from +0x130
* +0x130  add w12, w12, w11                      59  w11 from +0x128, w12 from +0x12c
* +0x12c  sub w12, w15, w12                      59  w12 from +0x120, w15 from +0x124
  +0x128  ldr w11, [x1, #0x28]             4     63  x1 from +0x0c0
* +0x124  lsl w15, w12, #5                       63  w12 from +0x120
* +0x120  add w12, w12, w11                      63  w11 from +0x118, w12 from +0x11c
* +0x11c  sub w12, w15, w12                      63  w12 from +0x110, w15 from +0x114
  +0x118  ldr w11, [x1, #0x24]             1     64  x1 from +0x0c0
* +0x114  lsl w15, w12, #5                       64  w12 from +0x110
* +0x110  add w12, w11, w12                      64  w11 from +0x10c, w12 from +0x108
* +0x10c  sub w11, w15, w11                      64  w11 from +0x100, w15 from +0x104
  +0x108  ldr w12, [x1, #0x20]             2     66  x1 from +0x0c0
* +0x104  lsl w15, w11, #5                       66  w11 from +0x100
* +0x100  add w11, w12, w14                      66  w12 from +0x0fc, w14 from +0x0f8
* +0x0fc  sub w12, w11, w12                      66  w11 from +0x0f4, w12 from +0x0f0
  +0x0f8  ldr w14, [x1, #0x1c]             1     67  x1 from +0x0c0
* +0x0f4  lsl w11, w12, #5                       67  w12 from +0x0f0
* +0x0f0  add w12, w12, w11                      67  w11 from +0x0e8, w12 from +0x0ec
* +0x0ec  sub w12, w14, w12                      67  w12 from +0x0e0, w14 from +0x0e4
  +0x0e8  ldr w11, [x1, #0x18]                   67  x1 from +0x0c0
* +0x0e4  lsl w14, w12, #5                       67  w12 from +0x0e0
* +0x0e0  add w12, w12, w11                1     68  w11 from +0x0d8, w12 from +0x0dc
* +0x0dc  sub w12, w14, w12                      68  w12 from +0x0d0, w14 from +0x0d4
  +0x0d8  ldr w11, [x1, #0x14]                   68  x1 from +0x0c0
* +0x0d4  lsl w14, w12, #5                       68  w12 from +0x0d0
* +0x0d0  add w12, w11, w12                      68  w11 from +0x0cc, w12 from +0x0c8
* +0x0cc  sub w11, w11, w4                       68  w11 from +0x0c4, w4 from +0x144 on the previous iteration
  +0x0c8  ldr w12, [x1, #0x10]                   68  x1 from +0x0c0
* +0x0c4  lsl w11, w4, #5                        68  w4 from +0x144 on the previous iteration
  +0x0c0  add x1, x10, w2, sxtw #2         4     72

```

The result:

```
chain: 33 instructions, 72 samples (of 72 in the method); critical path (*): 24 instructions
```

All of the method's 72 samples are on the chain: 67 on the loads, 1 on the arithmetic,
and 4 on the address computation the loads share. The critical path is 24
instructions per iteration, or three per element (`lsl`, `sub`, `add`), each waiting for
the one before. That matches the measured speed: on an Apple M2 the loop runs at about
0.88 ns, roughly 3 cycles, per element, whether the array fits in L1 or not.

### Delta decoding

A different chain, from a different loop: delta decoding, where each value is stored as
the difference from the one before. Decoding adds each delta to the previous decoded
value, so every element waits for the one before it:

```java
static void decodeRunning(int[] data) {
    int previous = data[0];
    for (int i = 1; i < data.length; i++) {
        previous += data[i];
        data[i] = previous;
    }
}
```

C2 unrolls this 8 times too, and keeps `previous` in a register. One second of decoding a
65,536-element array, the unrolled loop abridged as before:

```
Delta.decodeRunning([I)V
  tier 4 (C2), compile id 365, 456 bytes at 0x00000001402d6000, 68 samples

  pc                  bytes        instruction                      count        %
  ...
  0x00000001402d6090  4b c9 30 8b  add x11, x10, w16, sxtw #2
  0x00000001402d6094  6d 11 40 b9  ldr w13, [x11, #0x10]
  0x00000001402d6098  cc 01 0d 0b  add w12, w14, w13
  0x00000001402d609c  6c 11 00 b9  str w12, [x11, #0x10]                4    5.88%
  0x00000001402d60a0  6e 15 40 b9  ldr w14, [x11, #0x14]
  0x00000001402d60a4  cc 01 0c 0b  add w12, w14, w12
  0x00000001402d60a8  6c 15 00 b9  str w12, [x11, #0x14]                1    1.47%
  0x00000001402d60ac  6e 19 40 b9  ldr w14, [x11, #0x18]                6    8.82%
  0x00000001402d60b0  cc 01 0c 0b  add w12, w14, w12
  0x00000001402d60b4  6c 19 00 b9  str w12, [x11, #0x18]                5    7.35%
  0x00000001402d60b8  6e 1d 40 b9  ldr w14, [x11, #0x1c]
  0x00000001402d60bc  cc 01 0c 0b  add w12, w14, w12
  0x00000001402d60c0  6c 1d 00 b9  str w12, [x11, #0x1c]                4    5.88%
  0x00000001402d60c4  6e 21 40 b9  ldr w14, [x11, #0x20]
  0x00000001402d60c8  cc 01 0c 0b  add w12, w14, w12
  0x00000001402d60cc  6c 21 00 b9  str w12, [x11, #0x20]                6    8.82%
  0x00000001402d60d0  6e 25 40 b9  ldr w14, [x11, #0x24]                2    2.94%
  0x00000001402d60d4  cd 01 0c 0b  add w13, w14, w12                    2    2.94%
  0x00000001402d60d8  6d 25 00 b9  str w13, [x11, #0x24]                2    2.94%
  0x00000001402d60dc  6e 29 40 b9  ldr w14, [x11, #0x28]                7   10.29%
  0x00000001402d60e0  cc 01 0d 0b  add w12, w14, w13
  0x00000001402d60e4  6c 29 00 b9  str w12, [x11, #0x28]                3    4.41%
  0x00000001402d60e8  6e 2d 40 b9  ldr w14, [x11, #0x2c]                5    7.35%
  0x00000001402d60ec  ce 01 0c 0b  add w14, w14, w12
  0x00000001402d60f0  10 22 00 11  add w16, w16, #0x8                   4    5.88%
  0x00000001402d60f4  6e 2d 00 b9  str w14, [x11, #0x2c]               17   25.00%
  0x00000001402d60f8  1f 02 02 6b  cmp w16, w2
  0x00000001402d60fc  ab fc ff 54  b.lt 0x1402d6090 <+0x90>
  0x00000001402d6100  8b 1b 40 f9  ldr x11, [x28, #0x30]
  ...
```

This time the samples are spread over the loads and stores, with the most, 17 of 68, on
the last store, `str w14, [x11, #0x2c]` at `+0x0f4`. The rule finds one recurrence,
carried through `w14`. It walks back from `add w14, w14, w12` at `+0x0ec`, the
instruction producing the next iteration's `previous`, as before. It also adds the stores
that write out a value the chain produced. A store's operands do different jobs: in
`str w14, [x11, #0x2c]`, `w14` is the value written out, so the store depends on the
chain through it, while `x11` only addresses the destination. So a store joins the chain
through its stored register (marked `(stored)`), not through its address. A store through
`x11` of a value from outside the chain would stay out, even though `x11` comes from the
chain's address computation.

```
  offset  instruction                   count  total  operands written by
  +0x0f4  str w14, [x11, #0x2c]           17     17  x11 from +0x090, w14 (stored) from +0x0ec
* +0x0ec  add w14, w14, w12                      17  w12 from +0x0e0, w14 from +0x0e8
  +0x0e8  ldr w14, [x11, #0x2c]            5     22  x11 from +0x090
  +0x0e4  str w12, [x11, #0x28]            3     25  x11 from +0x090, w12 (stored) from +0x0e0
* +0x0e0  add w12, w14, w13                      25  w13 from +0x0d4, w14 from +0x0dc
  +0x0dc  ldr w14, [x11, #0x28]            7     32  x11 from +0x090
  +0x0d8  str w13, [x11, #0x24]            2     34  x11 from +0x090, w13 (stored) from +0x0d4
* +0x0d4  add w13, w14, w12                2     36  w12 from +0x0c8, w14 from +0x0d0
  +0x0d0  ldr w14, [x11, #0x24]            2     38  x11 from +0x090
  +0x0cc  str w12, [x11, #0x20]            6     44  x11 from +0x090, w12 (stored) from +0x0c8
* +0x0c8  add w12, w14, w12                      44  w12 from +0x0bc, w14 from +0x0c4
  +0x0c4  ldr w14, [x11, #0x20]                  44  x11 from +0x090
  +0x0c0  str w12, [x11, #0x1c]            4     48  x11 from +0x090, w12 (stored) from +0x0bc
* +0x0bc  add w12, w14, w12                      48  w12 from +0x0b0, w14 from +0x0b8
  +0x0b8  ldr w14, [x11, #0x1c]                  48  x11 from +0x090
  +0x0b4  str w12, [x11, #0x18]            5     53  x11 from +0x090, w12 (stored) from +0x0b0
* +0x0b0  add w12, w14, w12                      53  w12 from +0x0a4, w14 from +0x0ac
  +0x0ac  ldr w14, [x11, #0x18]            6     59  x11 from +0x090
  +0x0a8  str w12, [x11, #0x14]            1     60  x11 from +0x090, w12 (stored) from +0x0a4
* +0x0a4  add w12, w14, w12                      60  w12 from +0x098, w14 from +0x0a0
  +0x0a0  ldr w14, [x11, #0x14]                  60  x11 from +0x090
  +0x09c  str w12, [x11, #0x10]            4     64  x11 from +0x090, w12 (stored) from +0x098
* +0x098  add w12, w14, w13                      64  w13 from +0x094, w14 from +0x0ec on the previous iteration
  +0x094  ldr w13, [x11, #0x10]                  64  x11 from +0x090
  +0x090  add x11, x10, w16, sxtw #2             64
```

```
chain: 25 instructions, 64 samples (of 68 in the method); critical path (*): 8 instructions
```

The critical path is one `add` per element, each adding a delta to the previous
element's result: delta decoding's dependency, and nothing else. The loads bring in the
deltas and the stores write out the decoded values, so 64 of the 68 samples are on the
chain. The other 4 are on the loop counter's `add w16, w16, #0x8`, which the chain neither
reads nor feeds.

Written as `data[i] += data[i - 1]` instead, the same decoding isn't found. C2 keeps the
running value in a register within an iteration, but each iteration starts by reloading
the previous element from the array (`ldp w13, w12, [x11, #0xc]`). The dependency from
one iteration to the next goes through a store and a load, which the analysis doesn't
track, and the 8 `add`s within an iteration are below the straight-line threshold.

The rule reports chains like this one as `LOOP_CARRIED` findings when the critical path is
at least 8 instructions. It also reports straight-line chains, of at least 16 instructions
within one pass through the code. Findings are ranked by the samples on their
instructions:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<DependencyChain> chains = new LongDependencyChainRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
DependencyChain chain = chains.get(0);
chain.instructions();     // the 33 instructions the carried h depends on
chain.criticalPath();     // the 24 that must run one after another
chain.samples();          // 72
```

The analysis tracks registers only. Condition flags are left out, so a compare followed
by a branch isn't counted as a data dependency, and so are memory dependencies through
stores and loads. Register usage is modelled for AArch64 so far.

## Finding loops that aren't unrolled

C2 unrolls counted loops: it copies the body several times per iteration, so the loop's
overhead (the counter, the compare and branch, any range check) is paid once for several
elements, and the CPU gets several elements' work to overlap. Unrolling is also where
vectorisation starts. C2's superword pass looks for isomorphic operations in the unrolled
copies (the same operation on adjacent elements) and packs them into vector
instructions. A loop that isn't unrolled can't be vectorised.

C2 only unrolls loops it can treat as counted: an `int` counter stepped by a constant up
to a limit that doesn't change. A method call in the condition that isn't inlined breaks
that, because C2 can't know what it returns next time. (A `long` counter doesn't, from
JDK 17 on: C2 splits the loop into an outer loop around an inner one counted by an `int`,
and unrolls that.)

### Vectorised, and not

The same kernel, `c[i] = a[i] + b[i]`, with two conditions. With `limit` kept out of line
(`-XX:CompileCommand=dontinline`), as a large method would be:

```java
static void add(int[] a, int[] b, int[] c) {
    for (int i = 0; i < a.length; i++) {
        c[i] = a[i] + b[i];
    }
}

static int limit(int[] a) {
    return a.length;
}

static void addWithCallInCondition(int[] a, int[] b, int[] c) {
    for (int i = 0; i < limit(a); i++) {
        c[i] = a[i] + b[i];
    }
}
```

On 4,096-element arrays, on an Apple M2:

| | JDK 17 (Linux) | JDK 21 (Linux) | JDK 25 (Linux) | JDK 25 (macOS) |
|---|---|---|---|---|
| `add` | 0.066 ns/element | 0.067 | 0.066 | 0.063 |
| `addWithCallInCondition` | 3.70 ns/element | 3.78 | 3.62 | 1.86 |

C2 vectorises `add`. Here is its main loop on JDK 25, from the instruction profile:
8 copies of a 4-lane vector add, so 32 elements per iteration (`add w11, w18, #0x20`).
`ldr q` and `str q` move 4 `int`s at a time. The disassembler doesn't print vector
instructions yet; the `.word 0x4eb18610` is `add v16.4s, v16.4s, v17.4s`.

```
Vec.add([I[I[I)V
  tier 4 (C2), compile id 334, 680 bytes at 0x0000ffff73f63a80, 91 samples

  pc                  bytes        instruction                         count        %
  ...
  0x0000ffff73f63b60  f2 03 0b 2a  mov w18, w11
  0x0000ffff73f63b64  4b 7e 7e 93  sbfiz x11, x18, #2, #32                 1    1.10%
  0x0000ffff73f63b68  cf 01 0b 8b  add x15, x14, x11
  0x0000ffff73f63b6c  50 00 0b 8b  add x16, x2, x11
  0x0000ffff73f63b70  f0 05 c0 3d  ldr q16, [x15, #0x10]
  0x0000ffff73f63b74  11 06 c0 3d  ldr q17, [x16, #0x10]
  0x0000ffff73f63b78  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63b7c  6b 00 0b 8b  add x11, x3, x11
  0x0000ffff73f63b80  70 05 80 3d  str q16, [x11, #0x10]
  0x0000ffff73f63b84  10 0a c0 3d  ldr q16, [x16, #0x20]
  0x0000ffff73f63b88  f1 09 c0 3d  ldr q17, [x15, #0x20]
  0x0000ffff73f63b8c  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63b90  70 09 80 3d  str q16, [x11, #0x20]
  0x0000ffff73f63b94  10 0e c0 3d  ldr q16, [x16, #0x30]                   3    3.30%
  0x0000ffff73f63b98  f1 0d c0 3d  ldr q17, [x15, #0x30]                  10   10.99%
  0x0000ffff73f63b9c  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63ba0  70 0d 80 3d  str q16, [x11, #0x30]                   1    1.10%
  0x0000ffff73f63ba4  10 12 c0 3d  ldr q16, [x16, #0x40]                   1    1.10%
  0x0000ffff73f63ba8  f1 11 c0 3d  ldr q17, [x15, #0x40]                   1    1.10%
  0x0000ffff73f63bac  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63bb0  70 11 80 3d  str q16, [x11, #0x40]
  0x0000ffff73f63bb4  10 16 c0 3d  ldr q16, [x16, #0x50]
  0x0000ffff73f63bb8  f1 15 c0 3d  ldr q17, [x15, #0x50]
  0x0000ffff73f63bbc  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63bc0  70 15 80 3d  str q16, [x11, #0x50]                   7    7.69%
  0x0000ffff73f63bc4  10 1a c0 3d  ldr q16, [x16, #0x60]                   5    5.49%
  0x0000ffff73f63bc8  f1 19 c0 3d  ldr q17, [x15, #0x60]                  10   10.99%
  0x0000ffff73f63bcc  10 86 b1 4e  .word 0x4eb18610                        1    1.10%
  0x0000ffff73f63bd0  70 19 80 3d  str q16, [x11, #0x60]
  0x0000ffff73f63bd4  f0 1d c0 3d  ldr q16, [x15, #0x70]
  0x0000ffff73f63bd8  11 1e c0 3d  ldr q17, [x16, #0x70]
  0x0000ffff73f63bdc  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63be0  70 1d 80 3d  str q16, [x11, #0x70]                   5    5.49%
  0x0000ffff73f63be4  f0 21 c0 3d  ldr q16, [x15, #0x80]                   2    2.20%
  0x0000ffff73f63be8  11 22 c0 3d  ldr q17, [x16, #0x80]                  17   18.68%
  0x0000ffff73f63bec  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63bf0  70 21 80 3d  str q16, [x11, #0x80]                  24   26.37%
  0x0000ffff73f63bf4  4b 82 00 11  add w11, w18, #0x20
  0x0000ffff73f63bf8  7f 01 0c 6b  cmp w11, w12
  0x0000ffff73f63bfc  2b fb ff 54  b.lt 0xffff73f63b60 <+0xe0>
  ...
```

`addWithCallInCondition` does one element per iteration, with each array's range checked
every time (`cmp`/`b.hs`), the arrays and their lengths spilled to the stack and reloaded
around the call, and the call itself:

```
Vec.addWithCallInCondition([I[I[I)V
  tier 4 (C2), compile id 323, 536 bytes at 0x0000ffff97f63980, 43 samples

  pc                  bytes        instruction                     count        %
  ...
  0x0000ffff97f639e0  08 00 00 14  b 0xffff97f63a00 <+0x80>
  0x0000ffff97f639e4  1f 20 03 d5  nop
  0x0000ffff97f639e8  1f 20 03 d5  nop
  0x0000ffff97f639ec  1f 20 03 d5  nop
  0x0000ffff97f639f0  ee 3f 40 a9  ldp x14, x15, [sp]
  0x0000ffff97f639f4  f2 0b 40 f9  ldr x18, [sp, #0x10]
  0x0000ffff97f639f8  f1 43 43 29  ldp w17, w16, [sp, #0x18]
  0x0000ffff97f639fc  e2 23 40 b9  ldr w2, [sp, #0x20]                18   41.86%
  0x0000ffff97f63a00  aa 7f 7e 93  sbfiz x10, x29, #2, #32
  0x0000ffff97f63a04  eb 01 0a 8b  add x11, x15, x10
  0x0000ffff97f63a08  cc 01 0a 8b  add x12, x14, x10
  0x0000ffff97f63a0c  bf 03 11 6b  cmp w29, w17
  0x0000ffff97f63a10  82 03 00 54  b.hs 0xffff97f63a80 <+0x100>
  0x0000ffff97f63a14  8d 11 40 b9  ldr w13, [x12, #0x10]               5   11.63%
  0x0000ffff97f63a18  bf 03 10 6b  cmp w29, w16
  0x0000ffff97f63a1c  22 04 00 54  b.hs 0xffff97f63aa0 <+0x120>
  0x0000ffff97f63a20  6c 11 40 b9  ldr w12, [x11, #0x10]              12   27.91%
  0x0000ffff97f63a24  8c 01 0d 0b  add w12, w12, w13
  0x0000ffff97f63a28  bf 03 02 6b  cmp w29, w2
  0x0000ffff97f63a2c  c2 04 00 54  b.hs 0xffff97f63ac4 <+0x144>
  0x0000ffff97f63a30  4a 02 0a 8b  add x10, x18, x10
  0x0000ffff97f63a34  4c 11 00 b9  str w12, [x10, #0x10]
  0x0000ffff97f63a38  ee 3f 00 a9  stp x14, x15, [sp]
  0x0000ffff97f63a3c  f2 0b 00 f9  str x18, [sp, #0x10]
  0x0000ffff97f63a40  f1 43 03 29  stp w17, w16, [sp, #0x18]
  0x0000ffff97f63a44  e2 23 00 b9  str w2, [sp, #0x20]                 1    2.33%
  0x0000ffff97f63a48  bd 07 00 11  add w29, w29, #0x1
  0x0000ffff97f63a4c  e1 03 0e aa  mov x1, x14
  0x0000ffff97f63a50  6c fe ff 97  bl 0xffff97f63400
  0x0000ffff97f63a54  1f 20 03 d5  nop                                 6   13.95%
  0x0000ffff97f63a58  9f 39 80 f2  movk xzr, #0x1cc
  0x0000ffff97f63a5c  1f 20 80 f2  movk xzr, #0x100                    1    2.33%
  0x0000ffff97f63a60  bf 03 00 6b  cmp w29, w0
  0x0000ffff97f63a64  6b fc ff 54  b.lt 0xffff97f639f0 <+0x70>
  ...
```

### The rule

`NotUnrolledLoopRule` reports hot loops that process one element per iteration. For each
innermost loop with at least 10% of the method's samples, it finds the induction
variables: recurrences made only of adding or subtracting a constant and moving between
registers. Here that's `w29`, stepped by `add w29, w29, #0x1` at `+0x0c8`. It then works
out what a step covers from how the variable is used:

- as an index, scaled or extended in an operand (`add x10, x11, w13, sxtw #2`) or by an
  instruction of its own (`sbfiz x10, x29, #2, #32` at `+0x080`), a step counts
  elements;
- as the base register of a load or store, it counts bytes, so a step of one register's
  width is one element.

A loop is reported if every induction variable classified that way covers one element.
In `add`, the vector loop's `w18` is scaled by `sbfiz` too, but steps by 32, so it isn't
reported. Neither are the scalar loops C2 puts before and after it to reach an aligned
start and finish the remainder: they step by one, but have few samples.

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<NotUnrolledLoop> loops = new NotUnrolledLoopRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
NotUnrolledLoop loop = loops.get(0);
loop.header();             // the address of +0x080
loop.inductionVariable();  // x29
loop.step();               // 1
loop.calls();              // the bl at +0x0d0
loop.samples();            // 43, all of the method's
```

The finding lists the loop's calls, since a call that isn't inlined is the usual reason,
and whether the induction variable is 64-bit.

## Finding pointer chasing

Walking a linked structure, each load's address is the result of the load before it:
`node = node.next`. An array walk computes every address from an index, so the CPU can
issue many loads at once and the prefetcher can run ahead; a pointer chase can't start a
load until the previous one has completed. The loop runs at the latency of its loads,
and a cache miss on one node stalls everything after it.

### LinkedList

Summing the same 4,096 `Integer`s, through the same `List` interface:

```java
static int sum(List<Integer> list) {
    int sum = 0;
    for (int value : list) {
        sum += value;
    }
    return sum;
}
```

| | JDK 17 (Linux) | JDK 21 (Linux) | JDK 25 (Linux) | JDK 25 (macOS) |
|---|---|---|---|---|
| `ArrayList` | 0.35 ns/element | 0.36 | 0.35 | 0.35 |
| `LinkedList` | 1.33 ns/element | 1.26 | 1.26 | 1.84 |

C2 inlines the iterator and unrolls the `LinkedList` loop twice, so each iteration follows
`next` twice. Here is the loop from the JDK 25 profile on Linux, where the small heap
uses compressed references that need no decoding:

```
Chase.sum(Ljava/util/List;)I
  tier 4 (C2), compile id 339, 1000 bytes at 0x0000ffffa3f63e80, 99 samples

  pc                  bytes        instruction                         count        %
  ...
  0x0000ffffa3f63f80  e5 03 06 2a  mov w5, w6
  0x0000ffffa3f63f84  11 12 40 b9  ldr w17, [x16, #0x10]
  0x0000ffffa3f63f88  0e 0e 40 b9  ldr w14, [x16, #0xc]                    1    1.01%
  0x0000ffffa3f63f8c  c3 09 40 b9  ldr w3, [x14, #0x8]                    56   56.57%
  0x0000ffffa3f63f90  7f 00 0d 6b  cmp w3, w13                             4    4.04%
  0x0000ffffa3f63f94  21 0c 00 54  b.ne 0xffffa3f64118 <+0x298>
  0x0000ffffa3f63f98  30 12 40 b9  ldr w16, [x17, #0x10]
  0x0000ffffa3f63f9c  ce 0d 40 b9  ldr w14, [x14, #0xc]
  0x0000ffffa3f63fa0  22 0e 40 b9  ldr w2, [x17, #0xc]                    13   13.13%
  0x0000ffffa3f63fa4  c0 01 00 0b  add w0, w14, w0
  0x0000ffffa3f63fa8  62 03 00 34  cbz w2, 0xffffa3f64014 <+0x194>
  0x0000ffffa3f63fac  43 08 40 b9  ldr w3, [x2, #0x8]                      5    5.05%
  0x0000ffffa3f63fb0  7f 00 0a 6b  cmp w3, w10
  0x0000ffffa3f63fb4  ee 03 02 aa  mov x14, x2
  0x0000ffffa3f63fb8  c1 0a 00 54  b.ne 0xffffa3f64110 <+0x290>
  0x0000ffffa3f63fbc  c2 0d 40 b9  ldr w2, [x14, #0xc]                    13   13.13%
  0x0000ffffa3f63fc0  a6 08 00 11  add w6, w5, #0x2                        5    5.05%
  0x0000ffffa3f63fc4  40 00 00 0b  add w0, w2, w0
  0x0000ffffa3f63fc8  df 00 01 6b  cmp w6, w1
  0x0000ffffa3f63fcc  ab fd ff 54  b.lt 0xffffa3f63f80 <+0x100>            1    1.01%
  ...
```

`node.next` is loaded at `+0x104` into `w17`, and loaded again from that node at
`+0x118`, into `w16`: the register the next iteration loads from. Hanging off each node
are the loads of its `item` (`+0x108`, `+0x120`), the `Integer`'s class word, checked
before the cast (`+0x10c`, `+0x12c`), and its `value` (`+0x11c`, `+0x13c`). Each of those
waits on the node, and the first class check alone has 56 of the 99 samples.

With a heap that needs shifted compressed references (macOS here), each reference is
decoded with `lsl #3` before it can be used as an address, and that is on the chase too:

```
Chase.sum(Ljava/util/List;)I
  tier 4 (C2), compile id 372, 1040 bytes at 0x000000011494e340, 86 samples

  pc                  bytes        instruction                      count        %
  ...
  0x000000011494e440  e4 03 02 2a  mov w4, w2
  0x000000011494e444  31 f0 7d d3  lsl x17, x1, #3
  0x000000011494e448  2c 12 40 b9  ldr w12, [x17, #0x10]
  0x000000011494e44c  31 0e 40 b9  ldr w17, [x17, #0xc]
  0x000000011494e450  31 f2 7d d3  lsl x17, x17, #3
  0x000000011494e454  22 0a 40 b9  ldr w2, [x17, #0x8]                 42   48.84%
  0x000000011494e458  5f 00 0f 6b  cmp w2, w15
  0x000000011494e45c  85 f1 7d d3  lsl x5, x12, #3                      1    1.16%
  0x000000011494e460  e1 0c 00 54  b.ne 0x11494e5fc <+0x2bc>            2    2.33%
  0x000000011494e464  a1 10 40 b9  ldr w1, [x5, #0x10]                  4    4.65%
  0x000000011494e468  a2 0c 40 b9  ldr w2, [x5, #0xc]                   1    1.16%
  0x000000011494e46c  27 0e 40 b9  ldr w7, [x17, #0xc]                 10   11.63%
  0x000000011494e470  51 f0 7d d3  lsl x17, x2, #3
  0x000000011494e474  e0 00 00 0b  add w0, w7, w0                       1    1.16%
  0x000000011494e478  71 03 00 b4  cbz x17, 0x11494e4e4 <+0x1a4>
  0x000000011494e47c  27 0a 40 b9  ldr w7, [x17, #0x8]                  9   10.47%
  0x000000011494e480  ff 00 0d 6b  cmp w7, w13                          1    1.16%
  0x000000011494e484  81 0b 00 54  b.ne 0x11494e5f4 <+0x2b4>
  0x000000011494e488  22 0e 40 b9  ldr w2, [x17, #0xc]                 11   12.79%
  0x000000011494e48c  40 00 00 0b  add w0, w2, w0
  0x000000011494e490  82 08 00 11  add w2, w4, #0x2                     1    1.16%
  0x000000011494e494  5f 00 06 6b  cmp w2, w6                           3    3.49%
  0x000000011494e498  4b fd ff 54  b.lt 0x11494e440 <+0x100>
  ...
```

### The rule

`PointerChasingRule` looks for a loop-carried recurrence (see the dependency chains
above) with a load on its cycle. A load only reads registers to form its address, so a
load on the cycle has its address computed from the value the previous iteration carried
in, whether as a base (`node.next`) or an index (`i = next[i]`). Decoding and register
moves on the way are part of the cycle. For each, it reports:

- the cycle and the loads on it, one per step of the chase;
- the dereferences: the other loads in the loop whose address comes from the chase,
  such as the `item`, class word and `value` loads above;
- the loop's samples, and the samples on the chase and its dereferences.

Loops with less than 10% of the method's samples, like the one-node loop C2 adds to
finish an odd count, aren't reported. For the Linux profile above:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<PointerChase> chases = new PointerChasingRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
PointerChase chase = chases.get(0);
chase.carriedRegister();  // x16
chase.cycle();            // ldr w17, [x16, #0x10]; ldr w16, [x17, #0x10]
chase.dereferences();     // 6 loads: each node's item, its class and its value
chase.samples();          // 98, the loop's
chase.chaseSamples();     // 88 of them on the chase and its dereferences
```

The `ArrayList` loop has no finding: its recurrences are the sum and the index, neither
through a load.

## Finding null checks that have failed

C2 doesn't test references for null. It compiles a field access as a plain load and
relies on the load faulting if the reference is null: an *implicit* null check, which
costs nothing until it fails. When it does fail, the signal handler turns the fault into
the `NullPointerException`. If that happens a few times at the same location, or the
profile recorded a null there before the method was compiled, C2 recompiles the method
with an *explicit* test and branch at that location, and compiles the failure as a throw
of a preallocated exception: C2's fast throw, which has no stack trace
(`-XX:+OmitStackTraceInFastThrow`, the default).

So a test and a branch to a throw before a field access says that nulls have been seen
there. And since those exceptions have no stack trace, it says where the
`NullPointerException`s without one come from.

### Summing boxes

```java
static int sumBoxes(Box[] boxes) {
    int sum = 0;
    for (int i = 0; i < boxes.length; i++) {
        sum += boxes[i].value;
    }
    return sum;
}
```

Compiled by C2 on JDK 25 (Linux), the main loop, unrolled 4 times, loads 4 references
and then each `value` (`ldr w14, [x15, #0xc]`), with no tests:

```
Trap.sumBoxes([LTrap$Box;)I
  tier 4 (C2), compile id 305, 376 bytes at 0x0000ffff93f63340, 96 samples

  pc                  bytes        instruction                       count        %
  ...
  0x0000ffff93f633b0  2e c8 2a 8b  add x14, x1, w10, sxtw #2
  0x0000ffff93f633b4  d0 3d 42 29  ldp w16, w15, [x14, #0x10]
  0x0000ffff93f633b8  d2 19 40 b9  ldr w18, [x14, #0x18]
  0x0000ffff93f633bc  10 0e 40 b9  ldr w16, [x16, #0xc]
  0x0000ffff93f633c0  d1 1d 40 b9  ldr w17, [x14, #0x1c]
  0x0000ffff93f633c4  ee 0d 40 b9  ldr w14, [x15, #0xc]                 13   13.54%
  0x0000ffff93f633c8  52 0e 40 b9  ldr w18, [x18, #0xc]                  9    9.38%
  0x0000ffff93f633cc  0f 00 10 0b  add w15, w0, w16
  0x0000ffff93f633d0  31 0e 40 b9  ldr w17, [x17, #0xc]                 63   65.63%
  0x0000ffff93f633d4  ef 01 0e 0b  add w15, w15, w14
  0x0000ffff93f633d8  ee 01 12 0b  add w14, w15, w18
  0x0000ffff93f633dc  4a 11 00 11  add w10, w10, #0x4
  0x0000ffff93f633e0  c0 01 11 0b  add w0, w14, w17
  0x0000ffff93f633e4  5f 01 0d 6b  cmp w10, w13
  0x0000ffff93f633e8  4b fe ff 54  b.lt 0xffff93f633b0 <+0x70>          11   11.46%
  ...
```

The same method after compiled code has thrown 5 `NullPointerException`s from it. Three
were enough on JDK 17, 21 and 25; fewer left the code unchanged. Each reference is now
tested with `cbz` before it is used, in the code before the loop (`+0x03c`), in each of
the 4 copies in the loop (`+0x088` to `+0x0a0`) and in the loop after it (`+0x0f0`),
and every test branches to `+0x10c`. That loads the preallocated exception (`mov x1`,
`movk`), clears one of its fields, pops the frame and jumps out to the exception handler:

```
Trap.sumBoxes([LTrap$Box;)I
  tier 4 (C2), compile id 313, 520 bytes at 0x0000ffff77f63900, 97 samples

  pc                  bytes        instruction                          count        %
  ...
  0x0000ffff77f63938  2d 10 40 b9  ldr w13, [x1, #0x10]
  0x0000ffff77f6393c  8d 06 00 34  cbz w13, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f63940  6c 0c 00 51  sub w12, w3, #0x3
  0x0000ffff77f63944  ea 03 00 32  orr w10, wzr, #0x1
  0x0000ffff77f63948  a0 0d 40 b9  ldr w0, [x13, #0xc]
  0x0000ffff77f6394c  9f 05 00 71  cmp w12, #0x1
  0x0000ffff77f63950  8d 04 00 54  b.le 0xffff77f639e0 <+0xe0>
  0x0000ffff77f63954  0d f4 81 52  mov w13, #0xfa0
  0x0000ffff77f63958  6e 00 0a 4b  sub w14, w3, w10
  0x0000ffff77f6395c  9f 01 0a 6b  cmp w12, w10
  0x0000ffff77f63960  cb 0d 00 51  sub w11, w14, #0x3
  0x0000ffff77f63964  eb b3 8b 1a  csel w11, wzr, w11, lt
  0x0000ffff77f63968  7f 81 3e 71  cmp w11, #0xfa0
  0x0000ffff77f6396c  ab 81 8b 1a  csel w11, w13, w11, hi
  0x0000ffff77f63970  6e 01 0a 0b  add w14, w11, w10
  0x0000ffff77f63974  1f 20 03 d5  nop
  0x0000ffff77f63978  1f 20 03 d5  nop
  0x0000ffff77f6397c  1f 20 03 d5  nop
  0x0000ffff77f63980  2b c8 2a 8b  add x11, x1, w10, sxtw #2
  0x0000ffff77f63984  6f 11 40 b9  ldr w15, [x11, #0x10]                   20   20.62%
  0x0000ffff77f63988  2f 04 00 34  cbz w15, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f6398c  71 15 40 b9  ldr w17, [x11, #0x14]                   13   13.40%
  0x0000ffff77f63990  f1 03 00 34  cbz w17, 0xffff77f63a0c <+0x10c>         2    2.06%
  0x0000ffff77f63994  70 19 40 b9  ldr w16, [x11, #0x18]                   13   13.40%
  0x0000ffff77f63998  b0 03 00 34  cbz w16, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f6399c  72 1d 40 b9  ldr w18, [x11, #0x1c]                    9    9.28%
  0x0000ffff77f639a0  72 03 00 34  cbz w18, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f639a4  eb 0d 40 b9  ldr w11, [x15, #0xc]
  0x0000ffff77f639a8  31 0e 40 b9  ldr w17, [x17, #0xc]
  0x0000ffff77f639ac  0f 0e 40 b9  ldr w15, [x16, #0xc]                     2    2.06%
  0x0000ffff77f639b0  10 00 0b 0b  add w16, w0, w11
  0x0000ffff77f639b4  10 02 11 0b  add w16, w16, w17
  0x0000ffff77f639b8  4b 0e 40 b9  ldr w11, [x18, #0xc]                    34   35.05%
  0x0000ffff77f639bc  10 02 0f 0b  add w16, w16, w15
  0x0000ffff77f639c0  4a 11 00 11  add w10, w10, #0x4                       2    2.06%
  0x0000ffff77f639c4  00 02 0b 0b  add w0, w16, w11
  0x0000ffff77f639c8  5f 01 0e 6b  cmp w10, w14
  0x0000ffff77f639cc  ab fd ff 54  b.lt 0xffff77f63980 <+0x80>
  0x0000ffff77f639d0  8b 1b 40 f9  ldr x11, [x28, #0x30]
  0x0000ffff77f639d4  7f 01 40 b9  ldr wzr, [x11]
  0x0000ffff77f639d8  5f 01 0c 6b  cmp w10, w12
  0x0000ffff77f639dc  eb fb ff 54  b.lt 0xffff77f63958 <+0x58>
  0x0000ffff77f639e0  5f 01 03 6b  cmp w10, w3
  0x0000ffff77f639e4  ca 02 00 54  b.ge 0xffff77f63a3c <+0x13c>
  0x0000ffff77f639e8  2b c8 2a 8b  add x11, x1, w10, sxtw #2
  0x0000ffff77f639ec  6b 11 40 b9  ldr w11, [x11, #0x10]
  0x0000ffff77f639f0  eb 00 00 34  cbz w11, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f639f4  6c 0d 40 b9  ldr w12, [x11, #0xc]
  0x0000ffff77f639f8  4a 05 00 11  add w10, w10, #0x1
  0x0000ffff77f639fc  00 00 0c 0b  add w0, w0, w12
  0x0000ffff77f63a00  5f 01 03 6b  cmp w10, w3
  0x0000ffff77f63a04  2b ff ff 54  b.lt 0xffff77f639e8 <+0xe8>
  0x0000ffff77f63a08  0d 00 00 14  b 0xffff77f63a3c <+0x13c>
  0x0000ffff77f63a0c  eb 03 1f aa  mov x11, xzr
  0x0000ffff77f63a10  01 a2 83 d2  mov x1, #0x1d10
  0x0000ffff77f63a14  a1 fd bf f2  movk x1, #0xffed, lsl #16
  0x0000ffff77f63a18  01 00 c0 f2  movk x1, #0x0, lsl #32
  0x0000ffff77f63a1c  2a 50 00 91  add x10, x1, #0x14
  0x0000ffff77f63a20  8e 23 41 39  ldrb w14, [x28, #0x48]
  0x0000ffff77f63a24  0e 03 00 35  cbnz w14, 0xffff77f63a84 <+0x184>
  0x0000ffff77f63a28  4b 01 00 b9  str w11, [x10]
  0x0000ffff77f63a2c  fd 7b 42 a9  ldp x29, x30, [sp, #0x20]
  0x0000ffff77f63a30  ff c3 00 91  add sp, sp, #0x30
  0x0000ffff77f63a34  83 04 ee 17  b 0xffff77ae4c40
  ...
```

The tests cost little here: they are predicted, and the loop ran at the same speed. The
finding is the evidence that nulls reach this code.

### The rule

`FailedCheckRule` looks for conditional branches to a failure path: a throw (code that
pops the frame and jumps out of the method without returning) or an uncommon trap
(`mov w1, #request` with a negative request, then a call). A `cbz` or `cbnz` is a null
check if the register it tests is then used as an address, directly or once decoded.
Branches inside the failure paths, like the GC barrier's `cbnz` on the throw path, are
ignored. Checks that share a throw are one bytecode's check, copied by unrolling, so
they are one finding:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<FailedCheck> checks = new FailedCheckRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
FailedCheck check = checks.get(0);
check.kind();         // NULL_CHECK
check.failure();      // THROW
check.checks();       // the 6 cbz, at +0x03c, +0x088, +0x090, +0x098, +0x0a0 and +0x0f0
check.failurePath();  // the address of +0x10c
check.inLoop();       // true
check.samples();      // 95 of the method's 97, on the loops containing the checks
```

Null checks that branch to an uncommon trap rather than a throw aren't reported: C2
emits those where it can't fold the check into a load, such as before a cast, which lets
null through, speculating that the reference is never null. The `LinkedList` iteration
above has one, before each `item`'s class check; no null was ever seen there.

## Finding range checks that have failed

C2 removes the range checks from a counted loop: it checks once, before the loop, that
every index the loop will use is in bounds (loop predication), or splits the loop so the
main part needs no checks. When a check has failed, C2 recompiles the method with
explicit checks where it can't rule out failure, branching to a throw like the null
check's. Where it can't eliminate the checks from a loop at all, each iteration checks,
branching to an uncommon trap.

### Summing a prefix

```java
static int sumRange(int[] a, int n) {
    int sum = 0;
    for (int i = 0; i < n; i++) {
        sum += a[i];
    }
    return sum;
}
```

Called only with `n == a.length`, C2 on JDK 25 checks the bounds before the loop with
predicates that trap if they fail, and the loop has no checks. After one call with
`n == a.length + 1` has thrown `ArrayIndexOutOfBoundsException` from compiled code, it
compiles the method differently. The main loop, unrolled 16 times, is still unchecked,
but it now runs to the smaller of `n` and the length (the `csel` at `+0x03c`). An empty
array is tested before it (`cbz w15` at `+0x05c`), and the loop after it, which finishes
the remainder one element at a time, checks every index (`cmp w16, w15` and `b.hs` at
`+0x13c`). Both branch to the throw at `+0x174`:

```
Trap.sumRange([II)I
  tier 4 (C2), compile id 313, 568 bytes at 0x0000ffff8bf63d80, 98 samples

  pc                  bytes        instruction                          count        %
  ...
  0x0000ffff8bf63dac  2f 0c 40 b9  ldr w15, [x1, #0xc]
  0x0000ffff8bf63db0  ea 7d 40 93  sxtw x10, w15
  0x0000ffff8bf63db4  4b 7c 40 93  sxtw x11, w2
  0x0000ffff8bf63db8  7f 01 0a eb  cmp x11, x10
  0x0000ffff8bf63dbc  6a b1 8a 9a  csel x10, x11, x10, lt
  0x0000ffff8bf63dc0  4a 3d 00 d1  sub x10, x10, #0xf
  0x0000ffff8bf63dc4  e8 83 61 b2  mov x8, #-0x80000000
  0x0000ffff8bf63dc8  5f 01 08 eb  cmp x10, x8
  0x0000ffff8bf63dcc  eb 83 61 b2  mov x11, #-0x80000000
  0x0000ffff8bf63dd0  6a b1 8a 9a  csel x10, x11, x10, lt
  0x0000ffff8bf63dd4  f0 03 00 32  orr w16, wzr, #0x1
  0x0000ffff8bf63dd8  f2 03 0a 2a  mov w18, w10
  0x0000ffff8bf63ddc  cf 08 00 34  cbz w15, 0xffff8bf63ef4 <+0x174>
  0x0000ffff8bf63de0  20 10 40 b9  ldr w0, [x1, #0x10]
  0x0000ffff8bf63de4  5f 06 00 71  cmp w18, #0x1
  0x0000ffff8bf63de8  2d 06 00 54  b.le 0xffff8bf63eac <+0x12c>
  0x0000ffff8bf63dec  0e d0 87 52  mov w14, #0x3e80
  0x0000ffff8bf63df0  5f 02 10 6b  cmp w18, w16
  0x0000ffff8bf63df4  4b 02 10 4b  sub w11, w18, w16
  0x0000ffff8bf63df8  eb b3 8b 1a  csel w11, wzr, w11, lt
  0x0000ffff8bf63dfc  08 d0 87 52  mov w8, #0x3e80
  0x0000ffff8bf63e00  7f 01 08 6b  cmp w11, w8
  0x0000ffff8bf63e04  cb 81 8b 1a  csel w11, w14, w11, hi
  0x0000ffff8bf63e08  63 01 10 0b  add w3, w11, w16
  0x0000ffff8bf63e0c  1f 20 03 d5  nop
  0x0000ffff8bf63e10  31 c8 30 8b  add x17, x1, w16, sxtw #2
  0x0000ffff8bf63e14  2a 32 42 29  ldp w10, w12, [x17, #0x10]
  0x0000ffff8bf63e18  0a 00 0a 0b  add w10, w0, w10
  ...                                          (16 loads and adds, no checks)
  0x0000ffff8bf63e7c  2d 4a 40 b9  ldr w13, [x17, #0x48]
  0x0000ffff8bf63e80  4a 01 00 0b  add w10, w10, w0
  0x0000ffff8bf63e84  2c 4e 40 b9  ldr w12, [x17, #0x4c]                   93   94.90%
  0x0000ffff8bf63e88  4b 01 0d 0b  add w11, w10, w13
  0x0000ffff8bf63e8c  10 42 00 11  add w16, w16, #0x10
  0x0000ffff8bf63e90  60 01 0c 0b  add w0, w11, w12
  0x0000ffff8bf63e94  1f 02 03 6b  cmp w16, w3
  0x0000ffff8bf63e98  cb fb ff 54  b.lt 0xffff8bf63e10 <+0x90>
  0x0000ffff8bf63e9c  8a 1b 40 f9  ldr x10, [x28, #0x30]                    1    1.02%
  0x0000ffff8bf63ea0  5f 01 40 b9  ldr wzr, [x10]
  0x0000ffff8bf63ea4  1f 02 12 6b  cmp w16, w18
  0x0000ffff8bf63ea8  4b fa ff 54  b.lt 0xffff8bf63df0 <+0x70>
  0x0000ffff8bf63eac  1f 02 02 6b  cmp w16, w2
  0x0000ffff8bf63eb0  2a 01 00 54  b.ge 0xffff8bf63ed4 <+0x154>
  0x0000ffff8bf63eb4  2a c8 30 8b  add x10, x1, w16, sxtw #2                1    1.02%
  0x0000ffff8bf63eb8  1f 02 0f 6b  cmp w16, w15
  0x0000ffff8bf63ebc  c2 01 00 54  b.hs 0xffff8bf63ef4 <+0x174>
  0x0000ffff8bf63ec0  4b 11 40 b9  ldr w11, [x10, #0x10]
  0x0000ffff8bf63ec4  10 06 00 11  add w16, w16, #0x1
  0x0000ffff8bf63ec8  00 00 0b 0b  add w0, w0, w11
  0x0000ffff8bf63ecc  1f 02 02 6b  cmp w16, w2
  0x0000ffff8bf63ed0  2b ff ff 54  b.lt 0xffff8bf63eb4 <+0x134>
  0x0000ffff8bf63ed4  fd 7b 41 a9  ldp x29, x30, [sp, #0x10]
  0x0000ffff8bf63ed8  ff 83 00 91  add sp, sp, #0x20
  0x0000ffff8bf63edc  88 17 40 f9  ldr x8, [x28, #0x28]
  0x0000ffff8bf63ee0  ff 63 28 eb  cmp sp, x8
  0x0000ffff8bf63ee4  08 05 00 54  b.hi 0xffff8bf63f84 <+0x204>
  0x0000ffff8bf63ee8  c0 03 5f d6  ret
  0x0000ffff8bf63eec  e0 03 1f 2a  mov w0, wzr
  0x0000ffff8bf63ef0  f9 ff ff 17  b 0xffff8bf63ed4 <+0x154>
  0x0000ffff8bf63ef4  eb 03 1f aa  mov x11, xzr
  0x0000ffff8bf63ef8  01 c8 83 d2  mov x1, #0x1e40
  0x0000ffff8bf63efc  a1 fd bf f2  movk x1, #0xffed, lsl #16
  0x0000ffff8bf63f00  01 00 c0 f2  movk x1, #0x0, lsl #32
  0x0000ffff8bf63f04  2a 50 00 91  add x10, x1, #0x14
  0x0000ffff8bf63f08  8e 23 41 39  ldrb w14, [x28, #0x48]
  0x0000ffff8bf63f0c  4e 01 00 35  cbnz w14, 0xffff8bf63f34 <+0x1b4>
  0x0000ffff8bf63f10  4b 01 00 b9  str w11, [x10]
  0x0000ffff8bf63f14  fd 7b 41 a9  ldp x29, x30, [sp, #0x10]
  0x0000ffff8bf63f18  ff 83 00 91  add sp, sp, #0x20
  0x0000ffff8bf63f1c  c9 d9 ed 17  b 0xffff8bada640
  ...
```

Here the checks are outside the hot loop, so they cost almost nothing (1 of 98 samples);
again the finding is the evidence that out-of-bounds indexes reach this code.

### Range checks a loop kept

A loop C2 can't treat as counted keeps its range checks, and each fails to an uncommon
trap with reason `range_check`. `addWithCallInCondition`, in
[Finding loops that aren't unrolled](#finding-loops-that-arent-unrolled), checks each of
its 3 arrays on every iteration (the `b.hs` at `+0x090`, `+0x09c` and `+0x0ac`), and so
does a loop counted by a `long` that indexes with `(int) i`, after each of its unrolled
copies. `FailedCheckRule` reports these too, with the trap request:

```java
check.kind();         // RANGE_CHECK
check.failure();      // TRAP
check.trapRequest();  // -0x1c
check.trapReason();   // 3, range_check
check.trapAction();   // 3, make_not_entrant
check.checks();       // the 3 b.hs
```

A range check is a compare followed by an unsigned branch (`b.hs`, `b.hi`, `b.ls` or
`b.lo`), or a `cbz` of a length such a compare uses. Range checks that trap are only
reported in loops: one outside a loop is a single access that nothing could eliminate.
Traps for other reasons, such as the predicates that stand in for eliminated checks,
aren't reported, and neither are the reasons' numbers above 3, which change between JDK
versions.

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
    // machineCode(), hasMissingCounts(), classSignatureCode(), methodNameCode() ...
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
  method. Its leaf records are 64 bytes each: the compile tier and id, a total sample
  count and where the method's per-PC counters are. The handler looks the PC up there,
  without locks or allocation. Freed records are reclaimed safely using epochs.
- **Counters only for sampled methods:** most compiled methods are never sampled, so a
  method's per-PC counters are claimed from a separate region on its first sample, by
  the signal handler, with a compare-and-swap, and all of them are released when
  `resetCounts()` starts a new window. Records and counters together come to about
  5-8% of the code cache's size on AArch64. If the region fills up, a method's total
  still counts, and `hasMissingCounts()` says its per-instruction counts are
  incomplete.
- **Names, once each:** a record doesn't hold its method's name. Each distinct class
  signature, method name and method signature is kept once, in a concurrent dictionary
  that numbers strings in the order it first sees them, and records hold the three
  codes. `name()` looks them up and formats `package.Class.name(signature)` only when
  asked, which is usually just for the hottest methods.
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
- **Analysis rules** (`LongDependencyChainRule`, `NotUnrolledLoopRule`, `PointerChasingRule`, `FailedCheckRule`) work on AArch64
  only so far: they need a register model, and only `Aarch64RegisterModel` exists. They
  track registers, not memory.
