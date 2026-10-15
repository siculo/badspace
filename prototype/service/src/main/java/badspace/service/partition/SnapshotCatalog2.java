package badspace.service.partition;

import badspace.common.partition.RetentionWindow;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import java.util.Arrays;

/**
 * The tick of a 2D partition and its published snapshots in the retention window. With
 * the tick T, the creation tick C and k retained ticks, the window has the ticks from
 * max(T - k, C) to T - 1, and each of them has a snapshot. The catalog keeps each
 * published snapshot with the first tick it is valid for: a snapshot is valid until the
 * first tick of the next one, or until T - 1 for the last one. So the ticks without a
 * commit share the snapshot of the tick before, without copies.
 * <p>
 * A catalog is immutable. Each move of the tick makes a new catalog, and the storage
 * publishes it with a single write, so a reader sees the tick and the window together.
 */
final class SnapshotCatalog2 {

    private static final StorageSnapshot2[] NO_SNAPSHOTS = new StorageSnapshot2[0];
    private static final long[] NO_TICKS = new long[0];

    /** k: the number of retained ticks. */
    private final int retainedTicks;
    private final long creationTick;
    /** The tick of the partition. */
    private final long tick;
    // Entry: a published snapshot and the first tick it is valid for. The two arrays have the
    // same length and the same index for the same entry, from the oldest entry to the last
    // one, and the first ticks grow. Empty before the first move of the tick.
    private final StorageSnapshot2[] snapshots;
    private final long[] firstTicks;

    private SnapshotCatalog2(int retainedTicks, long creationTick, long tick, StorageSnapshot2[] snapshots,
            long[] firstTicks) {
        this.retainedTicks = retainedTicks;
        this.creationTick = creationTick;
        this.tick = tick;
        this.snapshots = snapshots;
        this.firstTicks = firstTicks;
    }

    /**
     * Returns the catalog of a new partition, at its creation tick and without published
     * snapshots. Fails with IllegalArgumentException if retainedTicks is less than 1.
     */
    static SnapshotCatalog2 empty(long creationTick, int retainedTicks) {
        if (retainedTicks < RetentionWindow.MIN_RETAINED_TICKS) {
            throw new IllegalArgumentException("Number of retained ticks is less than "
                    + RetentionWindow.MIN_RETAINED_TICKS + ": " + retainedTicks);
        }
        return new SnapshotCatalog2(retainedTicks, creationTick, creationTick, NO_SNAPSHOTS, NO_TICKS);
    }

    /**
     * Returns the catalog after the move of the tick to newTick, which must be greater than
     * the tick of this catalog: the storage checks it. If committed is not null, it is the
     * snapshot of the last commit of the current tick, valid from the current tick; otherwise
     * the last snapshot stays valid also for the ticks that end now. The snapshots whose
     * ticks are all before the new window leave it.
     */
    SnapshotCatalog2 advance(long newTick, StorageSnapshot2 committed) {
        int count = snapshots.length + (committed != null ? 1 : 0);
        StorageSnapshot2[] allSnapshots = Arrays.copyOf(snapshots, count);
        long[] allFirstTicks = Arrays.copyOf(firstTicks, count);
        if (committed != null) {
            allSnapshots[count - 1] = committed;
            allFirstTicks[count - 1] = tick;
        }
        long windowStart = windowStart(newTick);
        // A snapshot is still in the window if the next one starts after the start of the
        // window. The last one is valid until newTick - 1, so it always stays.
        int first = 0;
        while (first < count - 1 && allFirstTicks[first + 1] <= windowStart) {
            first++;
        }
        return new SnapshotCatalog2(retainedTicks, creationTick, newTick,
                Arrays.copyOfRange(allSnapshots, first, count), Arrays.copyOfRange(allFirstTicks, first, count));
    }

    /** Returns this catalog without snapshots: the window is closed. */
    SnapshotCatalog2 closed() {
        return new SnapshotCatalog2(retainedTicks, creationTick, tick, NO_SNAPSHOTS, NO_TICKS);
    }

    /** Returns the snapshot of the given tick, or null if the tick is not in the window. */
    StorageSnapshot2 at(long tick) {
        if (tick >= this.tick || tick < windowStart(this.tick)) {
            return null;
        }
        for (int i = snapshots.length - 1; i >= 0; i--) {
            if (firstTicks[i] <= tick) {
                return snapshots[i];
            }
        }
        return null;
    }

    /** Returns why the snapshot of the given tick is not in the window. The tick must not be in it. */
    Reason reasonNotAvailable(long tick) {
        if (tick >= this.tick) {
            return Reason.NOT_YET_PUBLISHED;
        }
        if (tick < creationTick) {
            return Reason.BEFORE_CREATION;
        }
        return Reason.OUT_OF_RETENTION;
    }

    /** Returns the snapshot of the tick before the current one, or null if no snapshot is published. */
    StorageSnapshot2 latest() {
        return snapshots.length == 0 ? null : snapshots[snapshots.length - 1];
    }

    /** Returns the tick of the partition. */
    long tick() {
        return tick;
    }

    long creationTick() {
        return creationTick;
    }

    /** Returns k: the number of retained ticks. */
    int retainedTicks() {
        return retainedTicks;
    }

    /** Returns the number of snapshots in the window. */
    int size() {
        return snapshots.length;
    }

    /** Returns the i-th snapshot in the window, from the oldest one. */
    StorageSnapshot2 snapshotAt(int i) {
        return snapshots[i];
    }

    /** Returns the first tick that the i-th snapshot in the window is valid for. */
    long firstTickAt(int i) {
        return firstTicks[i];
    }

    private long windowStart(long tick) {
        return Math.max(tick - retainedTicks, creationTick);
    }
}
