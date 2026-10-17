package badspace.service.partition.snapshot;

import badspace.service.partition.slots.CoordinateBuffer;
import badspace.service.partition.slots.CoordinateBufferPool;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The end of the snapshots of a 3D partition and the return of their coordinate buffers.
 * The partition and its acquisitions share one instance. The {@link SnapshotUses} of each
 * snapshot decide when it ends; this component does what follows: it gives the buffer back to
 * the pool and counts the published snapshots that did not end yet.
 * <p>
 * There are two ways for a snapshot to stop being used:
 * <ol>
 *   <li>a published snapshot ends: its buffer goes back and the count of the live snapshots
 *       goes down, once;</li>
 *   <li>a snapshot prepared by a commit is replaced by a later commit in the same tick before
 *       the move of the tick: no reader ever saw it, so its buffer goes back, but it was never
 *       counted as published.</li>
 * </ol>
 * When the last published snapshot ends, the methods return true: the partition is destroyed.
 * It happens once, as the result of the event that ends that snapshot. Any thread can call the
 * methods, except where they say otherwise.
 */
public final class SnapshotLifetime3 {

    // The published snapshots that did not end yet. The move of the tick that publishes a
    // snapshot adds one; the thread that ends a snapshot, any thread, removes one. Only a
    // published snapshot can end, and after the first move of the tick the last snapshot is
    // always in the window until the removal is effective, so the count reaches 0 only
    // after that, and only once.
    private final AtomicInteger liveSnapshots = new AtomicInteger();
    // The pool of the partition, which the writer also uses to prepare the snapshots.
    private final CoordinateBufferPool coordinateBuffers;

    /** Creates a lifetime that returns ended snapshots' buffers to the partition pool. */
    public SnapshotLifetime3(CoordinateBufferPool coordinateBuffers) {
        this.coordinateBuffers = coordinateBuffers;
    }

    /** Counts a snapshot that the move of the tick publishes. Called while holding the lock of the writer. */
    public void published() {
        liveSnapshots.incrementAndGet();
    }

    /**
     * Marks that a published snapshot left the window. Returns true if this ends the last
     * published snapshot: the partition is destroyed.
     */
    public boolean leaveWindow(StorageSnapshot3 snapshot) {
        return snapshot.uses().leaveWindow() && ended(snapshot);
    }

    /**
     * Removes a use of a published snapshot: an acquisition or an admitted query. Returns true
     * if this ends the last published snapshot: the partition is destroyed.
     */
    boolean removeUse(StorageSnapshot3 snapshot) {
        return snapshot.uses().removeUse() && ended(snapshot);
    }

    /**
     * Gives back the buffer of a snapshot that a later commit in the same tick replaced before
     * its publication: no reader ever saw it, and it is not in the count of the live
     * snapshots. Only the writer calls it, while holding its lock.
     */
    public void replaced(StorageSnapshot3 snapshot) {
        recycle(snapshot);
    }

    /**
     * Returns the number of published snapshots that did not end yet: the snapshots in the
     * window and the ones out of it that still have uses.
     */
    public int liveCount() {
        return liveSnapshots.get();
    }

    /**
     * The point where a published snapshot ends: nobody uses it and nobody can ask for it
     * again, so its buffer goes back to the pool. Called once for each snapshot, by the thread
     * that ends it. Returns true if it was the last snapshot: the pool is emptied, because no
     * commit will use it again.
     */
    private boolean ended(StorageSnapshot3 snapshot) {
        // The buffer goes back before the count goes down: the thread that sees 0 empties the
        // pool after all the other snapshots gave back their buffers, so none comes back later.
        recycle(snapshot);
        if (liveSnapshots.decrementAndGet() > 0) {
            return false;
        }
        coordinateBuffers.clear();
        return true;
    }

    /**
     * Removes the snapshot from the users of its coordinate buffer, and gives the buffer back
     * to the pool if no other snapshot uses it.
     */
    private void recycle(StorageSnapshot3 snapshot) {
        CoordinateBuffer buffer = snapshot.slots().coordinateBuffer();
        if (buffer.removeUser()) {
            coordinateBuffers.giveBack(buffer);
        }
    }
}
