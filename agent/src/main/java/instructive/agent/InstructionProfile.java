package instructive.agent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.ConcurrentModificationException;
import java.util.Optional;

/**
 * A compiled method and its samples: its name and compilation, the samples taken
 * anywhere in it, and the samples per program counter.
 *
 * <p>Profiles come from {@link Sampler#forEachMethod}, which passes a flyweight over the
 * sampler's live native storage:
 *
 * <pre>{@code
 * List<InstructionProfile> hot = new ArrayList<>();
 * Sampler.forEachMethod(profile -> {
 *     if (profile.total() > 100) {
 *         hot.add(profile.snapshot());   // keep a copy, never the flyweight itself
 *     }
 * });
 * for (InstructionProfile method : hot) {
 *     CharBuffer counts = method.counts();
 *     for (int offset = 0; offset < counts.limit(); offset++) {
 *         char count = counts.get(offset);   // samples at method.address() + offset
 *     }
 * }
 * }</pre>
 *
 * <p><b>Ownership.</b> A profile passed to a {@code forEachMethod} consumer belongs to
 * the caller only until the consumer returns. After that it is repositioned on another
 * method, and the same applies to its {@link #counts()} view. Don't store either; store
 * {@link #snapshot()}, an independent copy that the caller owns for good. A snapshot is
 * frozen: its counts don't change, and {@link #isLive()} says whether the method was
 * still loaded when it was taken.
 *
 * <p>Counts are unsigned 16-bit values, read as {@code char}, and saturate at
 * {@link #MAX_COUNT}, after which they are a lower bound while {@link #total()} keeps
 * counting.
 *
 * <p>Record layout, for maintainers. A profile reads one record of the native
 * {@code instructive::CompiledMethod} struct, which this must match exactly. Records
 * are in native byte order and 8-byte aligned:
 * <pre>
 *   0 int   lengths        profileLength | nameLength << 16, one atomic word
 *   4 int   generation     odd while the record is being (re)initialised
 *   8 long  address        start of the method's instructions
 *  16 long  total          samples anywhere in the method's nmethod
 *  24 long  methodId       native only: the jmethodID
 *  32 int   compileId      -1 if unknown
 *  36 int   headerOffset   native only: how far before address the method's nmethod starts
 *  40 int   state          FREE (0), LIVE (1) or RETIRED (2, being unlinked)
 *  44 char  methodSize     bytes of instructions, saturated at 65535
 *  46 byte  compLevel      -1 if unknown
 *  47 byte  reserved
 *  48 char  counts[profileLength], then the name and a NUL, then padding
 * </pre>
 * There is no stored record length: it is {@link #blockLength(int)} of the lengths word,
 * which must match the native {@code CompiledMethod::block_size}. Both lengths are 16
 * bits, so an nmethod is profiled for at most its first 65535 bytes. When a method is
 * unloaded its record becomes RETIRED, then FREE, and the block is later reused for
 * another method of the same size class, rewriting it under an odd generation.
 */
public final class InstructionProfile {

    /** The largest per-instruction count; counts stop there until {@link Sampler#resetCounts()}. */
    public static final char MAX_COUNT = Character.MAX_VALUE;

    static final int LENGTHS = 0;
    static final int GENERATION = 4;
    static final int ADDRESS = 8;
    static final int TOTAL = 16;
    static final int COMPILE_ID = 32;
    static final int STATE = 40;
    static final int METHOD_SIZE = 44;
    static final int COMP_LEVEL = 46;
    static final int HEADER_SIZE = 48;
    static final int ALIGNMENT = 8;
    static final int MIN_BLOCK_SIZE = 128;

    static final int LIVE = 1;

    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());

    private ByteBuffer buffer;
    private int position;
    // The record's generation when this flyweight was positioned on it.
    private int wrappedGeneration;

    InstructionProfile() {
    }

    private InstructionProfile(ByteBuffer copy) {
        this.buffer = copy;
        this.position = 0;
        this.wrappedGeneration = copy.getInt(GENERATION);
    }

    /** Points this flyweight at the record starting at {@code position}. */
    void wrap(ByteBuffer buffer, int position) {
        this.buffer = buffer;
        this.position = position;
        this.wrappedGeneration = generation();
    }

    /** The record's generation when this flyweight was positioned on it. */
    int wrappedGeneration() {
        return wrappedGeneration;
    }

    /** The record's size in bytes: the distance to the next record. */
    int blockLength() {
        return blockLength(lengths());
    }

    /**
     * The size of the block holding a record with this lengths word: header, counts,
     * name and NUL, padded to 8 bytes, then rounded up to a size class (four per power
     * of two). Must match the native {@code CompiledMethod::block_size}.
     */
    static int blockLength(int lengths) {
        int bytes = recordLength(lengths);
        if (bytes <= MIN_BLOCK_SIZE) {
            return MIN_BLOCK_SIZE;
        }
        int step = Integer.highestOneBit(bytes) >>> 2;
        return (bytes + step - 1) & -step;
    }

    // Header, counts, name and NUL, padded: the part of the block in use.
    private static int recordLength(int lengths) {
        int bytes = HEADER_SIZE + (lengths & 0xFFFF) * Character.BYTES + (lengths >>> 16) + 1;
        return (bytes + ALIGNMENT - 1) & -ALIGNMENT;
    }

    // Both lengths in one read, so they always come from the same record.
    private int lengths() {
        return buffer.hasArray() ? buffer.getInt(position + LENGTHS) : (int) INT.getAcquire(buffer, position + LENGTHS);
    }

    /** The record's generation, read with acquire semantics; odd while it is being written. */
    int generation() {
        return buffer.hasArray() ? buffer.getInt(position + GENERATION) : (int) INT.getAcquire(buffer, position + GENERATION);
    }

    private int state() {
        return buffer.hasArray() ? buffer.getInt(position + STATE) : (int) INT.getAcquire(buffer, position + STATE);
    }

    /**
     * Whether the method is loaded. Always true for a profile passed to a
     * {@code forEachMethod} consumer, unless the method is unloaded while the consumer
     * runs. For a snapshot, whether it was loaded when the snapshot was taken.
     */
    public boolean isLive() {
        return state() == LIVE && (generation() & 1) == 0;
    }

    /**
     * {@link #isLive()} using the generation read when the flyweight was positioned,
     * saving a load per record when walking the storage.
     */
    boolean isLiveAtWrap() {
        return (wrappedGeneration & 1) == 0 && state() == LIVE;
    }

    /**
     * An independent copy of this profile, owned by the caller: unlike the profile a
     * {@code forEachMethod} consumer receives, it can be stored and used after the
     * consumer returns. Its counts are frozen at the time of the copy, and its
     * {@link #counts()} view is a view of the copy. A snapshot's snapshot is itself.
     *
     * @throws ConcurrentModificationException if the method was unloaded and its storage
     *     reused for another method since this profile was positioned on it, so its data
     *     is gone; this is rare, and the new method is reported separately
     */
    public InstructionProfile snapshot() {
        if (buffer.hasArray()) {
            return this;
        }
        int generation = wrappedGeneration;
        int length = recordLength(lengths());
        if (generation() != generation) {
            throw reused();
        }
        ByteBuffer copy = ByteBuffer.allocate(length).order(ByteOrder.nativeOrder());
        copy.put(0, buffer, position, length);
        // A rewrite during the copy changes the generation, so the copy is consistent if
        // the generation is unchanged after it.
        if (generation() != generation) {
            throw reused();
        }
        return new InstructionProfile(copy);
    }

    private static ConcurrentModificationException reused() {
        return new ConcurrentModificationException("the method was unloaded and its profile reused");
    }

    /** The address of the method's first instruction. */
    public long address() {
        return buffer.getLong(position + ADDRESS);
    }

    /** Samples taken anywhere in the method's compiled code, including its stubs. */
    public long total() {
        return buffer.getLong(position + TOTAL);
    }

    /** The JIT compilation id, or -1 if unknown. */
    public int compileId() {
        return buffer.getInt(position + COMPILE_ID);
    }

    /** The tiered compilation level (1-3 C1, 4 C2), or -1 if unknown. */
    public int compLevel() {
        return buffer.get(position + COMP_LEVEL);
    }

    /** The size of the method's instructions in bytes, saturated at 65535. */
    public int methodSize() {
        return buffer.getChar(position + METHOD_SIZE);
    }

    /**
     * The number of per-PC counts: the instructions and the stubs after them, up to
     * 65535 bytes from {@link #address()}.
     */
    public int profileLength() {
        return lengths() & 0xFFFF;
    }

    /** The samples taken with the PC at {@code offset} bytes from {@link #address()}. */
    public char count(int offset) {
        return buffer.getChar(position + HEADER_SIZE + offset * Character.BYTES);
    }

    /**
     * A view of the per-PC counts: {@code counts().get(offset)} is
     * {@link #count(int) count(offset)}, for offsets in {@code [0, profileLength())}.
     * For a profile passed to a {@code forEachMethod} consumer, it views the live
     * counts, which keep changing while the sampler runs, and like the profile it may
     * not be used after the consumer returns. For a snapshot, it views the copy.
     * Allocates the view on each call.
     */
    public CharBuffer counts() {
        // slice() resets the byte order, so set it before taking the char view.
        return buffer.slice(position + HEADER_SIZE, profileLength() * Character.BYTES)
                .order(ByteOrder.nativeOrder())
                .asCharBuffer();
    }

    /**
     * A copy of the method's machine code, read from the JVM's code cache now: the
     * code from {@link #address()}, instructions then stubs, so byte {@code offset} of
     * the result is the instruction byte that {@link #count(int) count(offset)} counts.
     * Works on snapshots too, as long as the method is still loaded.
     *
     * <p>The code cache changes while the JVM runs, so this is only returned if the
     * compiled method found at {@link #address()} is exactly this one: same compile id,
     * code starting at {@code address()} and {@link #methodSize()} bytes long. That is
     * checked before and after copying. Otherwise, for example once the method has been
     * unloaded, the result is empty.
     *
     * <p>Unlike the rest of this class, this walks the JVM's own code cache structures,
     * which can change underneath the read. The checks make a wrong answer very
     * unlikely, but this is best used on methods that stay loaded, such as hot ones.
     */
    public Optional<byte[]> machineCode() {
        int compileId = compileId();
        if (compileId < 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(machineCode0(address(), methodSize(), compileId));
    }

    // The JNI side of machineCode(); takes the identifying fields rather than this
    // object to keep the call cheap.
    private static native byte[] machineCode0(long address, int methodSize, int compileId);

    /** The sum of the per-PC counts. */
    public long sampled() {
        long sum = 0;
        for (int offset = 0, length = profileLength(); offset < length; offset++) {
            sum += count(offset);
        }
        return sum;
    }

    /** The number of bytes of the method's name, in modified UTF-8. */
    public int nameLength() {
        return lengths() >>> 16;
    }

    /** The method as {@code package.Class.name(signature)}. */
    public String name() {
        return appendName(new StringBuilder(nameLength())).toString();
    }

    /**
     * Appends the method's name to {@code to}, without allocating beyond what
     * {@code to} needs to grow.
     *
     * <p>The name is stored as the JVM reports it, in modified UTF-8: like UTF-8, but NUL
     * is two bytes and supplementary characters are surrogate pairs of three-byte
     * sequences, so every character decodes from one, two or three bytes. A malformed
     * sequence, which can only come from reading a record while it is being reused,
     * decodes as U+FFFD.
     */
    public StringBuilder appendName(StringBuilder to) {
        int start = position + HEADER_SIZE + profileLength() * Character.BYTES;
        int end = start + nameLength();
        for (int i = start; i < end; ) {
            int b = buffer.get(i) & 0xFF;
            if (b < 0x80) {
                to.append((char) b);
                i += 1;
            } else if ((b & 0xE0) == 0xC0 && i + 1 < end) {
                to.append((char) (((b & 0x1F) << 6) | (buffer.get(i + 1) & 0x3F)));
                i += 2;
            } else if ((b & 0xF0) == 0xE0 && i + 2 < end) {
                to.append((char) (((b & 0x0F) << 12) | ((buffer.get(i + 1) & 0x3F) << 6) | (buffer.get(i + 2) & 0x3F)));
                i += 3;
            } else {
                to.append('\uFFFD');
                i += 1;
            }
        }
        return to;
    }

    @Override
    public String toString() {
        return "InstructionProfile[" + name() + " L" + compLevel() + " #" + compileId() + " @" + Long.toHexString(address())
                + " size=" + methodSize() + " total=" + total() + (buffer.hasArray() ? "" : " (live)") + "]";
    }
}
