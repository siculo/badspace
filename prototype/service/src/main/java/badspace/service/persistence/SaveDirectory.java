package badspace.service.persistence;

import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    // The result of tickOf for a file that is not a save: ticks are never negative.
    private static final long NOT_A_SAVE = -1;

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
     * Returns the saves of all the partitions, sorted by name and then by tick. It looks only at
     * the names of the directories and of the files: it skips the directories whose name is not a
     * partition name, the temporary files and the files whose name is not {@code <tick>.bsave}.
     */
    public List<SavedPartition> saves() throws IOException {
        List<SavedPartition> saves = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(partitions, Files::isDirectory)) {
            for (Path directory : directories) {
                String directoryName = directory.getFileName().toString();
                if (PartitionName.isValid(directoryName)) {
                    addSaves(new PartitionName(directoryName), directory, saves);
                }
            }
        }
        saves.sort(Comparator.comparing((SavedPartition saved) -> saved.name().value())
                .thenComparingLong(SavedPartition::tick));
        return saves;
    }

    /**
     * Returns the saves of a partition, sorted by tick; empty if it has no directory. It skips
     * the files like {@link #saves()}.
     */
    public List<SavedPartition> saves(PartitionName name) throws IOException {
        List<SavedPartition> saves = new ArrayList<>();
        Path directory = partitionDirectory(name);
        if (Files.isDirectory(directory)) {
            addSaves(name, directory, saves);
        }
        saves.sort(Comparator.comparingLong(SavedPartition::tick));
        return saves;
    }

    /**
     * Deletes the files of the given saves, in the order of the list, and forces the directories
     * to the disk, so a deleted save does not come back after a crash. A save that is not there
     * is skipped. If a file cannot be deleted, the saves before it in the list are already
     * deleted. The directories of the partitions stay, also without saves: a save at the same
     * time can need them.
     */
    public void delete(List<SavedPartition> saves) throws IOException {
        List<Path> changed = new ArrayList<>();
        for (SavedPartition saved : saves) {
            Path file = saveFile(saved.name(), saved.tick());
            if (Files.deleteIfExists(file) && !changed.contains(file.getParent())) {
                changed.add(file.getParent());
            }
        }
        for (Path directory : changed) {
            force(directory);
        }
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

    /** Adds the saves in the directory of a partition to the list, in no order. */
    private static void addSaves(PartitionName name, Path directory, List<SavedPartition> saves) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SAVE_EXTENSION)) {
            for (Path file : files) {
                long tick = tickOf(file);
                if (tick != NOT_A_SAVE) {
                    saves.add(new SavedPartition(name, tick));
                }
            }
        }
    }

    /**
     * Returns the tick in the name of a save file, or {@link #NOT_A_SAVE} if the name is not the
     * one that saveFile gives to a tick: only digits, without leading zeros, at most Long.MAX_VALUE.
     */
    private static long tickOf(Path file) {
        String fileName = file.getFileName().toString();
        int length = fileName.length() - SAVE_EXTENSION.length();
        if (length == 0 || (length > 1 && fileName.charAt(0) == '0')) {
            return NOT_A_SAVE;
        }
        long tick = 0;
        for (int i = 0; i < length; i++) {
            char c = fileName.charAt(i);
            if (c < '0' || c > '9') {
                return NOT_A_SAVE;
            }
            int digit = c - '0';
            if (tick > (Long.MAX_VALUE - digit) / 10) {
                return NOT_A_SAVE;
            }
            tick = tick * 10 + digit;
        }
        return tick;
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
