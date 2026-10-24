package badspace.benchmark;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the persistence benchmarks write their files. A run creates its own directory in
 * the destination given by the options, and passes it to the JMH forks with the system
 * property {@link #PROPERTY}; each trial creates its own directory inside it. Each one
 * deletes only its own directory, so two runs on the same destination do not touch the
 * files of each other.
 */
final class SaveDestination {

    /** The system property with the directory of the run, for the JMH forks. */
    static final String PROPERTY = "badspace.benchmark.saveDirectory";

    /** The destination when the options do not give one, relative to the working directory. */
    static final Path DEFAULT = Path.of("target", "save-benchmarks");

    private SaveDestination() {
    }

    /**
     * Creates the destination if it does not exist, and a new directory for a run in it,
     * with a unique name that starts with the given prefix.
     */
    static Path createRunDirectory(Path destination, String prefix) throws IOException {
        Files.createDirectories(destination);
        return Files.createTempDirectory(destination, prefix + "-");
    }

    /**
     * Creates a new directory for a trial in the directory of the run, given by the system
     * property {@link #PROPERTY}.
     *
     * @throws IllegalStateException if the property is not set or its directory does not exist
     */
    static Path createTrialDirectory() throws IOException {
        String run = System.getProperty(PROPERTY);
        if (run == null || !Files.isDirectory(Path.of(run))) {
            throw new IllegalStateException("No directory for the saves: set the system property "
                    + PROPERTY + " to an existing directory (the runner does it): " + run);
        }
        return Files.createTempDirectory(Path.of(run), "trial-");
    }

    /** Deletes the directory with all its content. It does nothing if the directory is null or not there. */
    static void delete(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(directory)) {
            // The files before their directories.
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    /**
     * Returns the type and the name of the file system of the directory, for example
     * {@code ext4 /dev/nvme0n1p2} on Linux, or {@code NTFS} and the label of the volume, if
     * it has one, on Windows; {@code unknown} if Java cannot tell them.
     */
    static String fileStore(Path directory) {
        try {
            FileStore store = Files.getFileStore(directory);
            return (store.type() + " " + store.name()).strip();
        } catch (IOException e) {
            return "unknown";
        }
    }
}
