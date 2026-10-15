package badspace.service.partition;

/**
 * The snapshots of a 3D partition in the retention window: the last snapshot
 * and up to k past snapshots before it, so at most k + 1 snapshots. Only the
 * published commits count: a skipped commit number makes no snapshot.
 * <p>
 * A catalog is immutable. The commit makes a new catalog with the new snapshot,
 * and the storage publishes it with a single write, so a reader sees the last
 * snapshot and the window of the same commit.
 */
final class SnapshotCatalog3 {

    /** k: the past snapshots kept besides the last one. */
    private final int retainedPastSnapshots;
    /** The snapshots in the window, from the oldest to the last one; never empty. */
    private final StorageSnapshot3[] snapshots;

    private SnapshotCatalog3(int retainedPastSnapshots, StorageSnapshot3[] snapshots) {
        this.retainedPastSnapshots = retainedPastSnapshots;
        this.snapshots = snapshots;
    }

    /**
     * Returns the catalog of a new partition, with only the empty snapshot at
     * commit 0. Fails with IllegalArgumentException if retainedPastSnapshots is negative.
     */
    static SnapshotCatalog3 empty(int retainedPastSnapshots) {
        if (retainedPastSnapshots < 0) {
            throw new IllegalArgumentException("Negative number of retained past snapshots: " + retainedPastSnapshots);
        }
        return new SnapshotCatalog3(retainedPastSnapshots, new StorageSnapshot3[] {StorageSnapshot3.empty()});
    }

    /**
     * Returns a new catalog with the given snapshot as the last one. If the window
     * is full, the oldest snapshot leaves it. The commit of the snapshot must be
     * greater than the last commit: the storage checks it.
     */
    SnapshotCatalog3 withSnapshot(StorageSnapshot3 snapshot) {
        int kept = Math.min(snapshots.length, retainedPastSnapshots);
        StorageSnapshot3[] next = new StorageSnapshot3[kept + 1];
        System.arraycopy(snapshots, snapshots.length - kept, next, 0, kept);
        next[kept] = snapshot;
        return new SnapshotCatalog3(retainedPastSnapshots, next);
    }

    /**
     * Returns the snapshot that the next {@link #withSnapshot} makes leave the window,
     * or null if the window is not full.
     */
    StorageSnapshot3 nextToLeave() {
        return snapshots.length == retainedPastSnapshots + 1 ? snapshots[0] : null;
    }

    /** Returns the snapshot of the last commit. */
    StorageSnapshot3 lastSnapshot() {
        return snapshots[snapshots.length - 1];
    }

    /**
     * Returns the snapshot at the given offset from the last one: offset 0 is the last
     * snapshot, offset i the i-th snapshot before it. Returns null if the window has no
     * snapshot at that offset. The offset must not be negative: the node checks it.
     */
    StorageSnapshot3 previous(int offset) {
        if (offset >= snapshots.length) {
            return null;
        }
        return snapshots[snapshots.length - 1 - offset];
    }

    /**
     * Returns the snapshot in the window with the greatest commit that is not greater
     * than tick, or null if there is none.
     */
    StorageSnapshot3 atOrBefore(long tick) {
        for (int i = snapshots.length - 1; i >= 0; i--) {
            if (snapshots[i].commit() <= tick) {
                return snapshots[i];
            }
        }
        return null;
    }

    /** Returns the snapshot in the window with the given commit, or null if there is none. */
    StorageSnapshot3 exact(long commit) {
        StorageSnapshot3 snapshot = atOrBefore(commit);
        return snapshot != null && snapshot.commit() == commit ? snapshot : null;
    }

    /** Returns k: the number of past snapshots kept besides the last one. */
    int retainedPastSnapshots() {
        return retainedPastSnapshots;
    }
}
