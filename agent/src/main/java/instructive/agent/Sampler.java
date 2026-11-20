package instructive.agent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** Starts and stops the native sampler, loading the native library on first use. */
public final class Sampler {
    static {
        NativeLibraryLoader.load();
    }

    // The native storage holding every compiled method's record, read through
    // InstructionProfile flyweights; read-only and only accessed by absolute index, so
    // one view can be shared by all threads. Null if the storage couldn't be reserved.
    private static final ByteBuffer PROFILES = profileBuffer();

    private static ByteBuffer profileBuffer() {
        ByteBuffer buffer = profileBuffer0();
        return buffer == null ? null : buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private Sampler() {}

    /**
     * Starts sampling; returns {@code false} if it was already running.
     *
     * @throws IllegalStateException if the CPU timer could not be started
     */
    public static boolean start() {
        return start0();
    }

    /** Stops sampling; returns {@code false} if it was not running. */
    public static boolean stop() {
        return stop0();
    }

    public static boolean isRunning() {
        return isRunning0();
    }

    /** Number of CPU timer signals handled while sampling, across all start/stop cycles. */
    public static long cpuTicks() {
        return cpuTicks0();
    }

    /**
     * Whether the JVM's code cache layout could be read from VMStructs. If not, samples
     * outside tracked compiled methods are {@link CodeKind#UNKNOWN} and compiled methods
     * have no compile level or id.
     */
    public static boolean codeCacheAvailable() {
        return codeCacheAvailable0();
    }

    /** Whether compiled methods are being tracked through JVMTI compiled method events. */
    public static boolean methodTrackingAvailable() {
        return methodTracking0();
    }

    /** Samples taken so far, by the kind of code they interrupted. */
    public static Map<CodeKind, Long> kindCounts() {
        long[] counts = kindCounts0();
        Map<CodeKind, Long> result = new EnumMap<>(CodeKind.class);
        for (CodeKind kind : CodeKind.values()) {
            result.put(kind, counts[kind.ordinal()]);
        }
        return result;
    }

    /**
     * Calls {@code consumer} once for each currently loaded compiled method, with an
     * {@link InstructionProfile} describing it: its name, compilation, sample total and
     * per-instruction counts. Safe to call while sampling, and from several threads at
     * once.
     *
     * <p><b>Ownership.</b> The profile passed to the consumer is a flyweight over the
     * sampler's live native storage. It is reused for each method, so the consumer
     * owns it only for the duration of that {@code accept} call. The same goes for
     * anything that reads through it, such as the {@link InstructionProfile#counts()}
     * view. Neither may be used once {@code accept} returns. To keep a method's
     * profile, store {@link InstructionProfile#snapshot()}, an independent copy.
     *
     * <p>While the consumer runs, the profile reflects the sampler's ongoing updates:
     * counts can grow between reads. If the method is unloaded during the call, its
     * data stays readable until the space is reused for another method, which
     * {@code snapshot()} detects.
     *
     * <p>Does nothing if the sampler's native storage couldn't be reserved.
     */
    public static void forEachMethod(Consumer<? super InstructionProfile> consumer) {
        if (PROFILES == null) {
            return;
        }
        InstructionProfile profile = new InstructionProfile();
        for (int position = 0, used = (int) profileBytesUsed0(); position < used; position += profile.blockLength()) {
            profile.wrap(PROFILES, position);
            if (profile.isLiveAtWrap()) {
                consumer.accept(profile);
            }
        }
    }

    /**
     * Calls {@code consumer} for each of the {@code k} methods with the most samples,
     * hottest first, with an {@link InstructionProfile} describing it. Methods with no
     * samples are never included, so there may be fewer than {@code k} calls. Safe to
     * call while sampling.
     *
     * <p><b>Ownership</b> is as for {@link #forEachMethod}: the profile is a flyweight
     * over live native storage, reused for each method and owned by the consumer only
     * until {@code accept} returns. To keep a method's profile, store
     * {@link InstructionProfile#snapshot()}.
     *
     * <p>One pass over the sampler's storage keeps the best {@code k} so far in a
     * min-heap: O(n log k) for n methods, with nothing allocated per method and nothing
     * copied. While sampling, counts change during the pass, so methods are chosen and
     * ordered by the totals seen when each was visited, and {@link
     * InstructionProfile#total()} may have grown a little by the time the consumer reads
     * it. A chosen method that is unloaded and its storage reused before the consumer
     * would see it is skipped.
     *
     * @throws IllegalArgumentException if {@code k} is negative
     */
    public static void forEachHottestMethod(int k, Consumer<? super InstructionProfile> consumer) {
        if (k < 0) {
            throw new IllegalArgumentException("k must not be negative: " + k);
        }
        if (k == 0 || PROFILES == null) {
            return;
        }
        TopK top = new TopK(k);
        InstructionProfile profile = new InstructionProfile();
        for (int position = 0, used = (int) profileBytesUsed0(); position < used; position += profile.blockLength()) {
            profile.wrap(PROFILES, position);
            if (profile.isLiveAtWrap()) {
                long total = profile.total();
                if (total > 0) {
                    top.offer(total, position, profile.wrappedGeneration());
                }
            }
        }
        top.sortDescending();
        for (int i = 0; i < top.size(); i++) {
            profile.wrap(PROFILES, top.position(i));
            // A different generation means the block now holds another method.
            if (profile.wrappedGeneration() == top.generation(i) && profile.isLiveAtWrap()) {
                consumer.accept(profile);
            }
        }
    }

    /**
     * Zeroes all sample counts: per kind, per compiled method and per instruction.
     * Per-instruction counts saturate at {@link InstructionProfile#MAX_COUNT}, about 11
     * minutes of samples at a single PC, so continuous profiling should reset well
     * within that. Safe to call while sampling; samples racing with the reset may be
     * counted on either side of it.
     */
    public static void resetCounts() {
        resetCounts0();
    }

    private static native boolean start0();

    private static native boolean stop0();

    private static native boolean isRunning0();

    private static native long cpuTicks0();

    private static native boolean codeCacheAvailable0();

    private static native long[] kindCounts0();

    private static native boolean methodTracking0();

    private static native ByteBuffer profileBuffer0();

    private static native long profileBytesUsed0();

    private static native void resetCounts0();
}
