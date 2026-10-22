package badspace.service.persistence;

import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The saves of a node in its {@link SaveDirectory}: it runs the saves and lists, deletes and
 * cleans up the files. It does not depend on the number of dimensions: the node gives the part
 * of a save that does, the copy of the snapshot and its write, as a {@link SnapshotWriter}. The
 * errors of the file system become UncheckedIOException, with a message that says what failed.
 * Thread-safe: it has no state of its own, only the file system.
 */
public final class NodeSaves {

    private final SaveDirectory saveDirectory;

    /**
     * Creates the saves of a node in the given directory, which must exist.
     *
     * @throws IllegalArgumentException if the directory does not exist
     * @throws UncheckedIOException if the directory cannot be prepared for the saves
     */
    public NodeSaves(Path directory) {
        try {
            this.saveDirectory = new SaveDirectory(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare the save directory: " + directory, e);
        }
    }

    /**
     * Saves the snapshot of the given tick of a partition with the writer, and returns the
     * reference to the save. Fails with UncheckedIOException if the writer cannot write the save,
     * and with the exceptions of the writer.
     */
    public SavedPartition save(PartitionName name, long tick, SnapshotWriter writer) {
        Objects.requireNonNull(writer);
        SavedPartition saved = new SavedPartition(name, tick);
        try {
            writer.write(saveDirectory);
            return saved;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save partition " + name.value() + " at tick " + tick, e);
        }
    }

    /** Returns the saves of all the partitions, sorted by name and then by tick. */
    public List<SavedPartition> saves() {
        try {
            return saveDirectory.saves();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the saves", e);
        }
    }

    /** Returns the saves of a partition name, sorted by tick; empty if it has none. */
    public List<SavedPartition> saves(PartitionName name) {
        Objects.requireNonNull(name);
        try {
            return saveDirectory.saves(name);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the saves of partition " + name.value(), e);
        }
    }

    /**
     * Deletes the given saves, in the order of the list; a save that is not there is skipped.
     * If a save cannot be deleted, the saves before it in the list are already deleted.
     */
    public void deleteSaves(List<SavedPartition> saves) {
        Objects.requireNonNull(saves);
        try {
            saveDirectory.delete(saves);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete the saves", e);
        }
    }

    /**
     * Removes the temporary files of interrupted saves. It must run when no save is in progress
     * (see {@link SaveDirectory#removeTemporaryFiles()}).
     */
    public void removeTemporaryFiles() {
        try {
            saveDirectory.removeTemporaryFiles();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot remove the temporary files of the saves", e);
        }
    }

    /**
     * The part of a save that depends on the number of dimensions: it copies the snapshot and
     * writes the copy to the save directory, for example with {@link PartitionSave2}.
     */
    @FunctionalInterface
    public interface SnapshotWriter {

        void write(SaveDirectory saveDirectory) throws IOException;
    }
}
