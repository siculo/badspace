package badspace.service.persistence;

import badspace.common.partition.PartitionName;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The destination of the saves of a node: a directory with a subdirectory {@code partitions},
 * which has a directory for each partition, named by the partition name. The save of a tick is
 * the file {@code partitions/<partition name>/<tick>.bsave}; a save in progress writes a
 * temporary file with a unique name and the extension {@code .tmp} in the same directory.
 * Thread-safe: it has no state of its own, only the file system.
 */
public final class SaveDirectory {

    static final String SAVE_EXTENSION = ".bsave";
    static final String TEMPORARY_EXTENSION = ".tmp";
    private static final String PARTITIONS = "partitions";

    private final Path partitions;

    /**
     * Creates the destination in the given directory, which must exist, and creates its
     * subdirectory {@code partitions} if it does not exist.
     *
     * @throws IllegalArgumentException if the directory does not exist
     * @throws IOException if the subdirectory cannot be created
     */
    public SaveDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Save directory does not exist: " + directory);
        }
        this.partitions = directory.resolve(PARTITIONS);
        if (!Files.isDirectory(partitions)) {
            Files.createDirectories(partitions);
            force(directory);
        }
    }

    /** Returns the file of the save of the given tick of a partition, also if it does not exist. */
    public Path saveFile(PartitionName name, long tick) {
        return partitionDirectory(name).resolve(tick + SAVE_EXTENSION);
    }

    /**
     * Removes the temporary files of interrupted saves, in the directories of all the
     * partitions. It does not know which saves are in progress, so it must run when no save
     * of the node is in progress, for example after the node starts: otherwise it can remove
     * the file of a save in progress, which then fails.
     */
    public void removeTemporaryFiles() throws IOException {
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(partitions, Files::isDirectory)) {
            for (Path directory : directories) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + TEMPORARY_EXTENSION)) {
                    for (Path file : files) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    /** Returns the directory of a partition. */
    Path partitionDirectory(PartitionName name) {
        return partitions.resolve(name.value());
    }

    /**
     * Creates the directory of a partition if it does not exist, and returns it. A new directory
     * is forced to the disk with its parent, so a save in it is not lost with it.
     */
    Path createPartitionDirectory(PartitionName name) throws IOException {
        Path directory = partitionDirectory(name);
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
            force(partitions);
        }
        return directory;
    }

    /** Forces the entries of a directory to the disk, so a file created or renamed in it stays. */
    static void force(Path directory) throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(directory, StandardOpenOption.READ);
        } catch (AccessDeniedException e) {
            // Windows cannot open a directory as a channel: there the entries are not forced.
            return;
        }
        try (channel) {
            channel.force(true);
        }
    }
}
