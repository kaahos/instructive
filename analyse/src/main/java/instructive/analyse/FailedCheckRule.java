package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.RegisterModel;
import instructive.parse.RegisterUsage;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags explicit null and range checks that branch to a throw or an uncommon trap: signs
 * that the check has failed at that location, or that C2 couldn't eliminate it. See
 * {@link FailedCheck}.
 *
 * <p>A check is a conditional branch whose target is a failure path:
 * <ul>
 *   <li>a throw: code that pops the frame ({@code ldp x29, x30, ...}) and jumps out of the
 *       method without returning. The check has failed here;</li>
 *   <li>for a range check in a loop, an uncommon trap with reason {@code range_check}:
 *       {@code mov w1, #request} with a negative request, then a call. The loop kept the
 *       check. Other reasons, such as the loop predicates that stand in for eliminated
 *       range checks, aren't checks that remain; null checks that trap are C2 speculating
 *       a reference is never null where the check can't be folded into a load, and a
 *       range check outside a loop is a single access.</li>
 * </ul>
 * It is a null check if it tests a register for zero ({@code cbz}/{@code cbnz}) that the
 * guarded code uses as an address, directly or once decoded; a range check if it is an
 * unsigned branch after a compare, or a test for zero of a length that a range check
 * compares against. Branches inside failure paths, such as a GC barrier's, are ignored.
 * Checks sharing a throw are one bytecode's, so they are one finding; so are the range
 * checks of one loop trapping with the same request, one per unrolled copy.
 *
 * <p>Findings come hottest first.
 */
public final class FailedCheckRule {

    static final int REASON_RANGE_CHECK = 3;

    private static final Pattern TRAP_REQUEST = Pattern.compile("w1, #(-0x[0-9a-f]+|-\\d+)");
    private static final Pattern COMPARE = Pattern.compile("[wx](\\d+), [wx](\\d+)");
    private static final Pattern BASE = Pattern.compile("\\[([wx]\\d+)\\b");
    private static final Set<String> UNSIGNED = Set.of("b.hs", "b.hi", "b.ls", "b.lo");
    // How far a failure path is followed before giving up on it.
    private static final int FAILURE_PATH_LIMIT = 48;

    /** The failure path at an address: a throw, or a trap with its request. */
    private record FailurePath(FailedCheck.Failure failure, OptionalInt trapRequest, BitSet instructions) {}

    public List<FailedCheck> apply(List<Instruction> instructions, RegisterModel registers) {
        return apply(instructions, registers, instruction -> 0);
    }

    /** The explicit checks in {@code instructions}, which must be in address order. */
    public List<FailedCheck> apply(
            List<Instruction> instructions, RegisterModel registers, ToLongFunction<Instruction> sampleCount) {
        ControlFlowGraph controlFlow = ControlFlowGraph.of(instructions);
        DependencyGraph graph = DependencyGraph.of(controlFlow, registers);
        List<Instruction> code = controlFlow.instructions();
        Map<Long, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < code.size(); i++) {
            indexOf.put(code.get(i).address(), i);
        }

        // Every branch to a failure path, and the instructions on those paths.
        Map<Integer, FailurePath> paths = new HashMap<>();
        BitSet onFailurePaths = new BitSet();
        List<Integer> branches = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            Instruction branch = code.get(i);
            if (branch.kind() != InstructionKind.CONDITIONAL_BRANCH || branch.branchTarget().isEmpty()) {
                continue;
            }
            Integer target = indexOf.get(branch.branchTarget().getAsLong());
            if (target == null || target <= i) {
                continue;
            }
            FailurePath path = paths.computeIfAbsent(target, t -> failurePath(code, indexOf, t));
            if (path != null) {
                branches.add(i);
                onFailurePaths.or(path.instructions());
            }
        }

        // Lengths compared by range checks, so that a test of one for zero is one too.
        Set<Integer> rangeChecks = new HashSet<>();
        for (int i : branches) {
            if (!onFailurePaths.get(i) && isRangeCheck(code, i)) {
                rangeChecks.add(i);
            }
        }

        Map<List<Object>, List<Integer>> groups = new LinkedHashMap<>();
        for (int i : branches) {
            if (onFailurePaths.get(i)) {
                continue;
            }
            int target = indexOf.get(code.get(i).branchTarget().getAsLong());
            FailurePath path = paths.get(target);
            FailedCheck.Kind kind;
            if (rangeChecks.contains(i) || isLengthTest(code, graph, registers, i, rangeChecks)) {
                kind = FailedCheck.Kind.RANGE_CHECK;
            } else if (isNullCheck(code, graph, registers, i)) {
                kind = FailedCheck.Kind.NULL_CHECK;
            } else {
                continue;
            }
            List<Object> key = List.of(kind, target);
            if (path.failure() == FailedCheck.Failure.TRAP) {
                // Only range checks a loop kept: a null check that traps is C2 speculating
                // the reference is never null where it can't fold the check into a load,
                // and a range check outside a loop is one access, nothing to eliminate.
                int reason = (~path.trapRequest().getAsInt() >> 3) & 0x1f;
                int loop = controlFlow.innermostLoop(controlFlow.blockOf(i));
                if (kind != FailedCheck.Kind.RANGE_CHECK || reason != REASON_RANGE_CHECK || loop < 0) {
                    continue;
                }
                // Each unrolled copy traps through a stub of its own: one finding per loop.
                key = List.of(kind, path.trapRequest().getAsInt(), "loop", loop);
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        List<FailedCheck> findings = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Integer>> group : groups.entrySet()) {
            FailedCheck.Kind kind = (FailedCheck.Kind) group.getKey().get(0);
            int target = indexOf.get(code.get(group.getValue().get(0)).branchTarget().getAsLong());
            FailurePath path = paths.get(target);
            BitSet hot = new BitSet();
            boolean inLoop = false;
            List<Instruction> checks = new ArrayList<>();
            for (int i : group.getValue()) {
                checks.add(code.get(i));
                int block = controlFlow.blockOf(i);
                int loop = controlFlow.innermostLoop(block);
                if (loop >= 0) {
                    inLoop = true;
                    hot.or(controlFlow.loops().get(loop).blocks());
                } else {
                    hot.set(block);
                }
            }
            long samples = 0;
            for (int block = hot.nextSetBit(0); block >= 0; block = hot.nextSetBit(block + 1)) {
                ControlFlowGraph.BasicBlock basicBlock = controlFlow.blocks().get(block);
                for (int i = basicBlock.start(); i < basicBlock.end(); i++) {
                    samples += sampleCount.applyAsLong(code.get(i));
                }
            }
            findings.add(new FailedCheck(kind, path.failure(), checks, code.get(target).address(), path.trapRequest(),
                    inLoop, samples));
        }
        findings.sort(Comparator.comparingLong(FailedCheck::samples).reversed()
                .thenComparingLong(finding -> finding.checks().get(0).address()));
        return findings;
    }

    // Follows the code from `start`, through unconditional branches within the method and
    // past conditional ones, until it returns, traps or leaves the method: a failure path
    // if it traps, or leaves after popping the frame.
    private static FailurePath failurePath(List<Instruction> code, Map<Long, Integer> indexOf, int start) {
        BitSet visited = new BitSet();
        boolean teardown = false;
        int i = start;
        for (int steps = 0; steps < FAILURE_PATH_LIMIT && i < code.size() && !visited.get(i); steps++) {
            visited.set(i);
            Instruction instruction = code.get(i);
            Matcher request = TRAP_REQUEST.matcher(instruction.operands());
            if (instruction.mnemonic().equals("mov") && request.matches()) {
                int trap = (int) Long.parseLong(request.group(1).replace("0x", ""), request.group(1).contains("0x") ? 16 : 10);
                // The call follows, after any spills.
                for (int j = i + 1; j < Math.min(code.size(), i + 12); j++) {
                    InstructionKind kind = code.get(j).kind();
                    if (kind == InstructionKind.CALL) {
                        visited.set(i, j + 1);
                        return new FailurePath(FailedCheck.Failure.TRAP, OptionalInt.of(trap), visited);
                    }
                    if (kind.isControlFlow()) {
                        break;
                    }
                }
            }
            switch (instruction.kind()) {
                case RETURN, INDIRECT_BRANCH, EXCEPTION, UNKNOWN -> {
                    return null;
                }
                case BRANCH -> {
                    Integer target = instruction.branchTarget().isPresent()
                            ? indexOf.get(instruction.branchTarget().getAsLong()) : null;
                    if (target == null) {
                        return teardown ? new FailurePath(FailedCheck.Failure.THROW, OptionalInt.empty(), visited) : null;
                    }
                    i = target;
                    continue;
                }
                default -> {
                    if (instruction.mnemonic().equals("ldp") && instruction.operands().startsWith("x29, x30, [sp")) {
                        teardown = true;
                    }
                }
            }
            i++;
        }
        return null;
    }

    // cmp a, b then an unsigned branch: index against length, or length against index.
    private static boolean isRangeCheck(List<Instruction> code, int branch) {
        if (!UNSIGNED.contains(code.get(branch).mnemonic()) || branch == 0) {
            return false;
        }
        Instruction compare = code.get(branch - 1);
        return compare.mnemonic().equals("cmp") && COMPARE.matcher(compare.operands()).matches();
    }

    // cbz of a length that a range check compares with: the array is empty.
    private static boolean isLengthTest(
            List<Instruction> code, DependencyGraph graph, RegisterModel registers, int branch, Set<Integer> rangeChecks) {
        Instruction test = code.get(branch);
        if (!test.mnemonic().equals("cbz") && !test.mnemonic().equals("cbnz")) {
            return false;
        }
        Set<Integer> writers = writersOf(graph, registers, branch, tested(registers, test));
        for (int check : rangeChecks) {
            int compare = check - 1;
            Matcher operands = COMPARE.matcher(code.get(compare).operands());
            if (operands.matches()) {
                for (String register : List.of("x" + operands.group(1), "x" + operands.group(2))) {
                    for (int writer : writersOf(graph, registers, compare, register)) {
                        if (writers.contains(writer)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // cbz/cbnz of a register the code it guards uses as an address, directly or decoded.
    private static boolean isNullCheck(List<Instruction> code, DependencyGraph graph, RegisterModel registers, int branch) {
        Instruction test = code.get(branch);
        if (!test.mnemonic().equals("cbz") && !test.mnemonic().equals("cbnz")) {
            return false;
        }
        String register = tested(registers, test);
        // The value the test reads, and what is decoded from it.
        Set<String> addresses = new HashSet<>(Set.of(register));
        for (int i = branch + 1; i < code.size() && i <= branch + 16; i++) {
            Instruction instruction = code.get(i);
            Set<String> reads = registers.usage(instruction).reads();
            if (instruction.kind() == InstructionKind.LOAD || instruction.kind() == InstructionKind.STORE) {
                Matcher base = BASE.matcher(instruction.operands());
                if (base.find() && addresses.contains("x" + base.group(1).substring(1))) {
                    return true;
                }
            }
            RegisterUsage usage = registers.usage(instruction);
            if ((instruction.mnemonic().equals("lsl") || instruction.mnemonic().equals("add")
                    || instruction.mnemonic().equals("mov")) && reads.stream().anyMatch(addresses::contains)) {
                addresses.addAll(usage.writes());
            } else {
                addresses.removeAll(usage.writes());
            }
            if (instruction.kind().isControlFlow() && instruction.kind() != InstructionKind.CONDITIONAL_BRANCH) {
                break;
            }
        }
        return false;
    }

    private static String tested(RegisterModel registers, Instruction test) {
        return registers.usage(test).reads().iterator().next();
    }

    // The instructions writing `register` that reach instruction `i`.
    private static Set<Integer> writersOf(DependencyGraph graph, RegisterModel registers, int i, String register) {
        Set<Integer> writers = new HashSet<>();
        for (int[] dependencies : new int[][] {graph.dependencies(i), graph.loopCarriedDependencies(i)}) {
            for (int writer : dependencies) {
                if (registers.usage(graph.instruction(writer)).writes().contains(register)) {
                    writers.add(writer);
                }
            }
        }
        return writers;
    }
}
