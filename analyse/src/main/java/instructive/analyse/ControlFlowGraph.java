package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The basic blocks of a method's instructions and the control flow between them, with
 * its loops.
 *
 * <p>A block starts at the first instruction, at every branch target inside the method
 * and after every branch. A conditional branch continues at its target and at the next
 * instruction; an unconditional branch only at its target. Returns, indirect branches,
 * traps ({@link InstructionKind#EXCEPTION}) and undecodable words end a path; a branch
 * out of the method leaves it. Calls return, so they don't end a block.
 *
 * <p>Blocks are numbered in address order. {@link #reversePostorder()} orders them so
 * that, apart from loop back-edges, control only flows forward. Loops are the natural
 * loops of back-edges: edges to a block that dominates the edge's source.
 */
public final class ControlFlowGraph {

    /**
     * A straight run of instructions [{@code start}, {@code end}).
     *
     * @param successors   blocks control can continue to, in no particular order
     * @param predecessors blocks control can come from
     */
    public record BasicBlock(int index, int start, int end, List<Integer> successors, List<Integer> predecessors) {
        public BasicBlock {
            successors = List.copyOf(successors);
            predecessors = List.copyOf(predecessors);
        }
    }

    /**
     * A natural loop.
     *
     * @param header the block every iteration starts at
     * @param blocks the blocks in the loop, header included
     */
    public record Loop(int header, BitSet blocks) {
        public Loop {
            blocks = (BitSet) blocks.clone();
        }

        @Override
        public BitSet blocks() {
            return (BitSet) blocks.clone();
        }

        public boolean contains(int block) {
            return blocks.get(block);
        }

        public int size() {
            return blocks.cardinality();
        }
    }

    private final List<Instruction> instructions;
    private final List<BasicBlock> blocks;
    private final int[] blockOf;
    private final int[] reversePostorder;
    private final int[] orderOf;
    private final List<Loop> loops;
    private final int[] innermostLoop;

    private ControlFlowGraph(List<Instruction> instructions, List<BasicBlock> blocks, int[] blockOf) {
        this.instructions = instructions;
        this.blocks = blocks;
        this.blockOf = blockOf;
        this.reversePostorder = computeReversePostorder(blocks);
        this.orderOf = new int[blocks.size()];
        for (int i = 0; i < reversePostorder.length; i++) {
            orderOf[reversePostorder[i]] = i;
        }
        this.loops = findLoops();
        this.innermostLoop = new int[blocks.size()];
        Arrays.fill(innermostLoop, -1);
        for (int block = 0; block < blocks.size(); block++) {
            for (int loop = 0; loop < loops.size(); loop++) {
                if (loops.get(loop).contains(block)
                        && (innermostLoop[block] < 0 || loops.get(loop).size() < loops.get(innermostLoop[block]).size())) {
                    innermostLoop[block] = loop;
                }
            }
        }
    }

    /** The control flow graph of {@code instructions}, which must be in address order. */
    public static ControlFlowGraph of(List<Instruction> instructions) {
        List<Instruction> code = List.copyOf(instructions);
        int size = code.size();
        Map<Long, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < size; i++) {
            indexOf.put(code.get(i).address(), i);
        }
        boolean[] leader = new boolean[size];
        if (size > 0) {
            leader[0] = true;
        }
        for (int i = 0; i < size; i++) {
            Instruction instruction = code.get(i);
            Integer target = target(instruction, indexOf);
            if (target != null) {
                leader[target] = true;
            }
            if (endsBlock(instruction) && i + 1 < size) {
                leader[i + 1] = true;
            }
        }
        int[] blockOf = new int[size];
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (leader[i]) {
                ranges.add(new int[] {i, i});
            }
            blockOf[i] = ranges.size() - 1;
            ranges.get(ranges.size() - 1)[1] = i + 1;
        }
        List<List<Integer>> successors = new ArrayList<>();
        List<List<Integer>> predecessors = new ArrayList<>();
        for (int b = 0; b < ranges.size(); b++) {
            successors.add(new ArrayList<>());
            predecessors.add(new ArrayList<>());
        }
        for (int b = 0; b < ranges.size(); b++) {
            int last = ranges.get(b)[1] - 1;
            Instruction instruction = code.get(last);
            Integer target = target(instruction, indexOf);
            if (target != null) {
                addEdge(successors, predecessors, b, blockOf[target]);
            }
            if (fallsThrough(instruction) && last + 1 < size) {
                addEdge(successors, predecessors, b, blockOf[last + 1]);
            }
        }
        List<BasicBlock> blocks = new ArrayList<>();
        for (int b = 0; b < ranges.size(); b++) {
            blocks.add(new BasicBlock(b, ranges.get(b)[0], ranges.get(b)[1], successors.get(b), predecessors.get(b)));
        }
        return new ControlFlowGraph(code, List.copyOf(blocks), blockOf);
    }

    private static void addEdge(List<List<Integer>> successors, List<List<Integer>> predecessors, int from, int to) {
        if (!successors.get(from).contains(to)) {
            successors.get(from).add(to);
            predecessors.get(to).add(from);
        }
    }

    // The index of a direct branch's target, if it is inside the method.
    private static Integer target(Instruction instruction, Map<Long, Integer> indexOf) {
        if (instruction.kind() != InstructionKind.BRANCH && instruction.kind() != InstructionKind.CONDITIONAL_BRANCH) {
            return null;
        }
        return instruction.branchTarget().isPresent() ? indexOf.get(instruction.branchTarget().getAsLong()) : null;
    }

    private static boolean endsBlock(Instruction instruction) {
        return switch (instruction.kind()) {
            case BRANCH, CONDITIONAL_BRANCH, INDIRECT_BRANCH, RETURN, EXCEPTION, UNKNOWN -> true;
            default -> false;
        };
    }

    private static boolean fallsThrough(Instruction instruction) {
        return switch (instruction.kind()) {
            case BRANCH, INDIRECT_BRANCH, RETURN, EXCEPTION, UNKNOWN -> false;
            default -> true;
        };
    }

    // Depth-first from the entry; blocks it can't reach follow, as further roots.
    private static int[] computeReversePostorder(List<BasicBlock> blocks) {
        int size = blocks.size();
        boolean[] visited = new boolean[size];
        List<Integer> postorder = new ArrayList<>(size);
        for (int root = 0; root < size; root++) {
            if (visited[root]) {
                continue;
            }
            Deque<int[]> stack = new ArrayDeque<>();
            stack.push(new int[] {root, 0});
            visited[root] = true;
            while (!stack.isEmpty()) {
                int[] frame = stack.peek();
                List<Integer> successors = blocks.get(frame[0]).successors();
                if (frame[1] < successors.size()) {
                    int next = successors.get(frame[1]++);
                    if (!visited[next]) {
                        visited[next] = true;
                        stack.push(new int[] {next, 0});
                    }
                } else {
                    postorder.add(stack.pop()[0]);
                }
            }
        }
        Collections.reverse(postorder);
        return postorder.stream().mapToInt(Integer::intValue).toArray();
    }

    // Dominators (Cooper, Harvey and Kennedy) over the blocks reachable from the entry.
    private int[] dominators() {
        int size = blocks.size();
        int[] dominator = new int[size];
        Arrays.fill(dominator, -1);
        if (size == 0) {
            return dominator;
        }
        dominator[0] = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int block : reversePostorder) {
                if (block == 0) {
                    continue;
                }
                int idom = -1;
                for (int predecessor : blocks.get(block).predecessors()) {
                    if (dominator[predecessor] >= 0) {
                        idom = idom < 0 ? predecessor : intersect(dominator, predecessor, idom);
                    }
                }
                if (idom >= 0 && dominator[block] != idom) {
                    dominator[block] = idom;
                    changed = true;
                }
            }
        }
        return dominator;
    }

    private int intersect(int[] dominator, int a, int b) {
        while (a != b) {
            while (orderOf[a] > orderOf[b]) {
                a = dominator[a];
            }
            while (orderOf[b] > orderOf[a]) {
                b = dominator[b];
            }
        }
        return a;
    }

    private boolean dominates(int[] dominator, int a, int b) {
        if (dominator[b] < 0) {
            return false;
        }
        for (int block = b; ; block = dominator[block]) {
            if (block == a) {
                return true;
            }
            if (block == 0 || dominator[block] == block) {
                return false;
            }
        }
    }

    private List<Loop> findLoops() {
        int[] dominator = dominators();
        Map<Integer, BitSet> bodies = new HashMap<>();
        for (BasicBlock block : blocks) {
            for (int header : block.successors()) {
                if (!dominates(dominator, header, block.index())) {
                    continue;
                }
                // The natural loop: the header and every block that reaches this
                // back-edge's source without passing through the header. Only blocks
                // the header dominates: not, say, unreachable padding that falls into
                // a loop entered by a branch to its middle.
                BitSet body = bodies.computeIfAbsent(header, h -> new BitSet());
                body.set(header);
                Deque<Integer> work = new ArrayDeque<>();
                if (!body.get(block.index())) {
                    body.set(block.index());
                    work.push(block.index());
                }
                while (!work.isEmpty()) {
                    for (int predecessor : blocks.get(work.pop()).predecessors()) {
                        if (!body.get(predecessor) && dominates(dominator, header, predecessor)) {
                            body.set(predecessor);
                            work.push(predecessor);
                        }
                    }
                }
            }
        }
        List<Loop> loops = new ArrayList<>();
        bodies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> loops.add(new Loop(entry.getKey(), entry.getValue())));
        return List.copyOf(loops);
    }

    public List<Instruction> instructions() {
        return instructions;
    }

    public List<BasicBlock> blocks() {
        return blocks;
    }

    /** The block instruction {@code index} is in. */
    public int blockOf(int index) {
        return blockOf[index];
    }

    /** The blocks in reverse postorder, the entry first. */
    public int[] reversePostorder() {
        return reversePostorder.clone();
    }

    /** The position of {@code block} in {@link #reversePostorder()}. */
    public int order(int block) {
        return orderOf[block];
    }

    /**
     * Whether the edge from {@code from} to {@code to} goes back in reverse postorder:
     * a loop back-edge, or in irreducible control flow another edge that closes a cycle.
     */
    public boolean isRetreating(int from, int to) {
        return orderOf[to] <= orderOf[from];
    }

    public List<Loop> loops() {
        return loops;
    }

    /** The index in {@link #loops()} of the innermost loop containing {@code block}, or -1. */
    public int innermostLoop(int block) {
        return innermostLoop[block];
    }
}
