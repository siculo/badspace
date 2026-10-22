package badspace.service.persistence;

import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The saves of a node in its {@link SaveDirectory}: it runs the saves and lists, deletes and
 * cleans up the files. It does not depend on the number of dimensions: the node gives the part
 * of a save that does, the copy of the snapshot and its write, as a {@link SnapshotWriter}. The
 * errors of the file system become UncheckedIOException, with a message that says what failed.
 * <p>
 * It keeps track of the saves in progress: a save of a snapshot that another thread is already
 * saving does not copy and write it again, but waits for the other save and gets its result.
 * Thread-safe.
 */
public final class NodeSaves {

    private final SaveDirectory saveDirectory;
    // Guards inProgress and the state of its entries; finished signals the end of a save.
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition finished = lock.newCondition();
    // The saves in progress, one for each snapshot. The thread that writes a save adds it and
    // removes it at the end; the threads that save the same snapshot meanwhile wait for it.
    // Read and written while holding lock.
    private final Map<SavedPartition, SaveInProgress> inProgress = new HashMap<>();

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
     * reference to the save. If another thread is already saving the same snapshot, it does not
     * call the writer: it runs release, so the caller does not keep the snapshot while it waits,
     * waits for the other save without reacting to interrupts, and returns the same reference.
     * Fails with UncheckedIOException if the writer cannot write the save, or if the other save
     * that it waited for failed; fails with the exceptions of the writer.
     */
    public SavedPartition save(PartitionName name, long tick, Runnable release, SnapshotWriter writer) {
        Objects.requireNonNull(release);
        Objects.requireNonNull(writer);
        SavedPartition saved = new SavedPartition(name, tick);
        SaveInProgress other;
        SaveInProgress save = null;
        lock.lock();
        try {
            other = inProgress.get(saved);
            if (other == null) {
                save = new SaveInProgress();
                inProgress.put(saved, save);
            }
        } finally {
            lock.unlock();
        }
        if (other != null) {
            // Outside the lock: the release runs code of the node.
            release.run();
            return waitFor(saved, other);
        }
        boolean written = false;
        try {
            writer.write(saveDirectory);
            written = true;
            return saved;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save partition " + name.value() + " at tick " + tick, e);
        } finally {
            finish(saved, save, written);
        }
    }

    /**
     * Waits for the other save of the same snapshot and returns its reference, or fails if it
     * failed. The other save can be already finished: its entry keeps the result.
     */
    private SavedPartition waitFor(SavedPartition saved, SaveInProgress other) {
        boolean written;
        lock.lock();
        try {
            while (!other.finished) {
                finished.awaitUninterruptibly();
            }
            written = other.written;
        } finally {
            lock.unlock();
        }
        if (!written) {
            throw new UncheckedIOException("Cannot save partition " + saved.name().value() + " at tick "
                    + saved.tick() + ": another save of the same snapshot failed",
                    new IOException("Save of the same snapshot failed"));
        }
        return saved;
    }

    /** Ends a save: removes it from the saves in progress and wakes the threads that wait for it. */
    private void finish(SavedPartition saved, SaveInProgress save, boolean written) {
        lock.lock();
        try {
            save.finished = true;
            save.written = written;
            inProgress.remove(saved);
            finished.signalAll();
        } finally {
            lock.unlock();
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

    /** A save in progress, with its result once it is finished. Read and written while holding lock. */
    private static final class SaveInProgress {

        private boolean finished;
        private boolean written;
    }
}
