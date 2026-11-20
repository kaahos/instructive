package instructive.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads the native library packaged at {@code META-INF/native-libs/<os>-<arch>/} for the
 * operating system and architecture of the running JVM.
 */
public final class NativeLibraryLoader {
    private static final String LIBRARY_NAME = "instructive";

    private static volatile boolean loaded;

    private NativeLibraryLoader() {}

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        String resource = "/META-INF/native-libs/" + platform() + "/" + System.mapLibraryName(LIBRARY_NAME);
        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("No native library at " + resource);
            }
            Path file = Files.createTempFile(LIBRARY_NAME, System.mapLibraryName(""));
            file.toFile().deleteOnExit();
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            System.load(file.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract " + resource, e);
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * The {@code <os>-<arch>} directory name, e.g. {@code linux-x86_64}. Must agree with
     * normaliseOperatingSystem and normaliseArchitecture in agent/build.gradle.kts.
     */
    static String platform() {
        return operatingSystem() + "-" + architecture();
    }

    static String operatingSystem() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.startsWith("linux")) {
            return "linux";
        } else if (os.startsWith("mac")) {
            return "macos";
        } else if (os.startsWith("windows")) {
            return "windows";
        }
        throw new UnsatisfiedLinkError("Unsupported operating system: " + os);
    }

    static String architecture() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new UnsatisfiedLinkError("Unsupported architecture: " + arch);
        };
    }
}
