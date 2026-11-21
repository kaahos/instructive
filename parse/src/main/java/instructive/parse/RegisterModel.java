package instructive.parse;

/** Which registers instructions of one architecture read and write. */
public interface RegisterModel {

    /** The registers {@code instruction} reads and writes; {@link RegisterUsage#UNKNOWN} if it wasn't decoded. */
    RegisterUsage usage(Instruction instruction);
}
