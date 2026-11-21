package instructive.parse;

import java.util.Set;

/**
 * The registers an instruction reads and writes, by canonical name: views of the same
 * register share a name ({@code w5} and {@code x5} are both {@code x5}), and the zero
 * register, which carries no data, is left out. Condition flags are not included.
 *
 * @param reads  registers whose values the instruction uses
 * @param writes registers the instruction changes
 * @param stored the registers among {@code reads} whose values the instruction writes to
 *               memory: a store's data, not the registers addressing it. In
 *               {@code str w14, [x11, #0x2c]}, {@code x14} but not {@code x11}.
 * @param known  false if the instruction wasn't decoded, so which registers it uses is
 *               unknown and it may write any of them
 */
public record RegisterUsage(Set<String> reads, Set<String> writes, Set<String> stored, boolean known) {

    public static final RegisterUsage UNKNOWN = new RegisterUsage(Set.of(), Set.of(), Set.of(), false);

    public RegisterUsage {
        reads = Set.copyOf(reads);
        writes = Set.copyOf(writes);
        stored = Set.copyOf(stored);
    }

    /** The usage of an instruction that stores no registers. */
    public RegisterUsage(Set<String> reads, Set<String> writes, boolean known) {
        this(reads, writes, Set.of(), known);
    }
}
