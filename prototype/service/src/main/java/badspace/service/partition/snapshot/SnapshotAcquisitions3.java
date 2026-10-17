package badspace.service.partition.snapshot;

import badspace.common.partition.AcquireOptions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The acquisitions of the snapshots of a 3D partition: the access of the readers. Each
 * acquisition has a number and holds its snapshot, also after the snapshot leaves the window,
 * until it is released or expires. The timeouts are measured with the {@link MonotonicClock}
 * of the node.
 * <p>
 * The caller chooses the snapshot and its tick from the catalog; this component does not know
 * the catalog, the writer or the ownership. The uses that it removes go to the
 * {@link SnapshotLifetime3} of the partition: the methods that can end the last published
 * snapshot return true, and the caller destroys the partition.
 * <p>
 * Any thread can call the methods, and none takes the lock of the writer: the concurrent map
 * makes each acquisition, read and release atomic, and a query keeps its own reference to the
 * snapshot, so a release or an expiry during the query does not stop it.
 */
public final class SnapshotAcquisitions3 {

    /** The value that {@link #acquire} returns when the snapshot left the window. */
    public static final long NOT_ACQUIRED = 0;

    // Acquisition number -> acquired snapshot with its timeout.
    private final ConcurrentHashMap<Long, AcquisitionEntry3> acquisitions = new ConcurrentHashMap<>();
    // The number of the last acquisition. Numbers start from 1 and are never used again.
    private final AtomicLong lastAcquisitionId = new AtomicLong();
    private final MonotonicClock clock;
    private final SnapshotLifetime3 lifetime;

    /** Creates empty acquisitions with the clock and lifetime of their partition. */
    public SnapshotAcquisitions3(MonotonicClock clock, SnapshotLifetime3 lifetime) {
        this.clock = clock;
        this.lifetime = lifetime;
    }

    /**
     * Acquires the given snapshot of the given tick with the options, and returns the number
     * of the acquisition. Returns NOT_ACQUIRED, and uses no number, if a move of the tick made
     * the snapshot leave the window after the caller chose it. It does not remove the expired
     * acquisitions: the caller calls {@link #removeExpired} after it and takes its result,
     * because the new acquisition can already be expired, and then the scan can end the last
     * published snapshot.
     */
    public long acquire(StorageSnapshot3 snapshot, long tick, AcquireOptions options) {
        // Checking the window and adding the use is a single step, so a snapshot that
        // left the window is never acquired again.
        if (!snapshot.uses().addUseIfInWindow()) {
            return NOT_ACQUIRED;
        }
        long acquisitionId = lastAcquisitionId.incrementAndGet();
        acquisitions.put(acquisitionId, AcquisitionEntry3.of(snapshot, tick, options, clock.nanoTime()));
        return acquisitionId;
    }

    /**
     * Admits a query: returns the acquired snapshot, which the query then reads, and adds a
     * use to it. The query must call {@link #endQuery} when it ends, also if it fails.
     * Checking the acquisition and taking the snapshot is a single step: the query can end
     * also if the acquisition is released or expires in the meantime. Fails with
     * IllegalStateException if the acquisition was released or is expired, and with
     * IllegalArgumentException if it was never made or if its tick is not the given tick.
     */
    public StorageSnapshot3 admit(long acquisitionId, long tick) {
        // A single read of the entry: a removal at the same time cannot make the
        // check and the snapshot disagree.
        AcquisitionEntry3 entry = acquisitions.get(acquisitionId);
        if (entry == null || entry.isExpired(clock.nanoTime())) {
            throw missingAcquisition(acquisitionId);
        }
        if (entry.tick() != tick) {
            throw new IllegalArgumentException("Acquisition " + acquisitionId + " is of tick " + entry.tick()
                    + ", not " + tick);
        }
        // A release at the same time can end the snapshot after the read of the entry:
        // then the query fails, as after the release.
        if (!entry.snapshot().uses().addUseIfNotEnded()) {
            throw missingAcquisition(acquisitionId);
        }
        return entry.snapshot();
    }

    /**
     * Ends a query admitted by {@link #admit}: removes its use, which can end the snapshot.
     * Returns true if this ends the last published snapshot.
     */
    public boolean endQuery(StorageSnapshot3 snapshot) {
        return lifetime.removeUse(snapshot);
    }

    /**
     * Releases an acquisition: removes its use, which can end the snapshot. Releasing it
     * again, or after its timeout, does nothing. It also removes the expired acquisitions.
     * Returns true if this ends the last published snapshot. Fails with
     * IllegalArgumentException if the acquisition was never made.
     */
    public boolean release(long acquisitionId) {
        checkMade(acquisitionId);
        boolean lastEnded = false;
        // Only one thread gets the entry: a second release or the removal after the
        // timeout finds nothing, so the use is removed once.
        AcquisitionEntry3 entry = acquisitions.remove(acquisitionId);
        if (entry != null) {
            lastEnded = lifetime.removeUse(entry.snapshot());
        }
        return removeExpired() | lastEnded;
    }

    /**
     * Removes the expired acquisitions and their uses, which can end their snapshots.
     * Returns true if this ends the last published snapshot. The reader of an expired
     * acquisition may never come back, so nobody waits for it. It scans all the
     * acquisitions; the map does not block the other threads.
     */
    public boolean removeExpired() {
        boolean lastEnded = false;
        long now = clock.nanoTime();
        for (Map.Entry<Long, AcquisitionEntry3> e : acquisitions.entrySet()) {
            // remove(key, value) succeeds for one thread only, like the release.
            if (e.getValue().isExpired(now) && acquisitions.remove(e.getKey(), e.getValue())) {
                lastEnded |= lifetime.removeUse(e.getValue().snapshot());
            }
        }
        return lastEnded;
    }

    /**
     * Returns the number of acquisitions not yet released or removed after the timeout:
     * an expired acquisition counts until the next scan of the expired ones.
     */
    public int count() {
        return acquisitions.size();
    }

    private void checkMade(long acquisitionId) {
        if (!isMade(acquisitionId)) {
            throw new IllegalArgumentException("Unknown acquisition: " + acquisitionId);
        }
    }

    /**
     * Returns the error for an acquisition that admit cannot find: IllegalArgumentException
     * if it was never made, IllegalStateException if it was released or is expired. After the
     * removal of an expired entry nobody can tell the two cases apart.
     */
    private RuntimeException missingAcquisition(long acquisitionId) {
        if (!isMade(acquisitionId)) {
            return new IllegalArgumentException("Unknown acquisition: " + acquisitionId);
        }
        return new IllegalStateException("Acquisition was released or is expired: " + acquisitionId);
    }

    /** Returns true if the acquisition was made: its number was given by acquire. */
    private boolean isMade(long acquisitionId) {
        return acquisitionId >= 1 && acquisitionId <= lastAcquisitionId.get();
    }
}
