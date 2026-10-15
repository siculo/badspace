package badspace.service.partition;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * A node that hosts 2D partitions in the same process as the API.
 * <p>
 * The removal of a partition has two steps. The mark of removal stops the writes, the
 * commits and the new acquisitions, but the acquisitions made before stay readable. The
 * node keeps the storage, under the same ID, until its last snapshot ends: then the
 * storage says that the partition is finished, and the node removes it. A partition
 * marked as removed has no events of its own, so at each event of the node (creation,
 * removal, commit, acquisition, release, new tick) the node looks for its expired acquisitions.
 * <p>
 * A lock orders the moves to a new tick and the creation of partitions: a partition created
 * while the node moves to a new tick is created either before the move, and then moves too,
 * or after it, at the new tick. So no partition stays behind the node.
 */
public final class LocalPartitionNode2 implements PartitionNode2 {

    // Each partition has its own writer, possibly on a different thread. A partition
    // marked as removed stays here until it is finished.
    private final Map<PartitionId, PartitionStorage2> partitions = new ConcurrentHashMap<>();
    // The partitions marked as removed and not yet finished. The writer adds a partition
    // when it marks it; the thread that finishes it, any thread, removes it.
    private final Map<PartitionId, PartitionStorage2> removedPartitions = new ConcurrentHashMap<>();
    // Measures the timeouts of the acquisitions in all the partitions.
    private final MonotonicClock clock;
    // Orders setTick and createPartition: both run while holding it.
    private final ReentrantLock tickLock = new ReentrantLock();
    // The tick of the node. Written by setTick while holding tickLock, read by any thread.
    private volatile long tick;

    /** Creates a node that measures the timeouts of the acquisitions with the clock of the system. */
    public LocalPartitionNode2() {
        this(MonotonicClock.SYSTEM);
    }

    /** Creates a node that measures the timeouts of the acquisitions with the given clock. */
    LocalPartitionNode2(MonotonicClock clock) {
        this.clock = clock;
    }

    @Override
    public void createPartition(PartitionId partitionId, IndexConfig index, int retainedPastSnapshots) {
        if (retainedPastSnapshots < 0) {
            throw new IllegalArgumentException("Negative number of retained past snapshots: " + retainedPastSnapshots);
        }
        removeExpiredOfRemovedPartitions();
        tickLock.lock();
        try {
            // A partition marked as removed keeps its ID until it is finished, so this
            // also rejects its ID.
            PartitionStorage2 storage = new PartitionStorage2(index, retainedPastSnapshots, clock, tick);
            if (partitions.putIfAbsent(partitionId, storage) != null) {
                throw new IllegalArgumentException("Partition already exists: " + partitionId);
            }
        } finally {
            tickLock.unlock();
        }
    }

    @Override
    public void setTick(long tick) {
        tickLock.lock();
        try {
            if (tick < this.tick) {
                throw new IllegalArgumentException("Tick " + tick + " is less than the tick of the node " + this.tick);
            }
            this.tick = tick;
            // The partitions marked as removed are in the map too.
            for (PartitionStorage2 storage : partitions.values()) {
                storage.setTick(tick);
            }
        } finally {
            tickLock.unlock();
        }
        removeExpiredOfRemovedPartitions();
    }

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public long tick(PartitionId partitionId) {
        return find(partitionId).tick();
    }

    @Override
    public long creationTick(PartitionId partitionId) {
        return find(partitionId).creationTick();
    }

    @Override
    public void removePartition(PartitionId partitionId) {
        removeExpiredOfRemovedPartitions();
        // find() fails if the partition is unknown. Check and mark are two steps,
        // but only the writer of the partition changes it.
        PartitionStorage2 storage = find(partitionId);
        if (storage.size() > 0) {
            throw new IllegalStateException("Partition is not empty: " + partitionId);
        }
        markRemoved(partitionId, storage);
    }

    @Override
    public void dropPartition(PartitionId partitionId) {
        removeExpiredOfRemovedPartitions();
        markRemoved(partitionId, find(partitionId));
    }

    @Override
    public void insertAll(PartitionId partitionId, List<Entity2> entities) {
        find(partitionId).insertAll(entities);
    }

    @Override
    public List<Entity2> getAll(PartitionId partitionId, long[] entityIds) {
        return find(partitionId).getAll(entityIds);
    }

    @Override
    public void updateAll(PartitionId partitionId, List<Entity2> entities) {
        find(partitionId).updateAll(entities);
    }

    @Override
    public void removeAll(PartitionId partitionId, long[] entityIds) {
        find(partitionId).removeAll(entityIds);
    }

    @Override
    public List<Entity2> findInRegion(PartitionId partitionId, Region2 region) {
        return find(partitionId).findInRegion(region);
    }

    @Override
    public List<Entity2> findNearest(PartitionId partitionId, Point2 point, int count) {
        return find(partitionId).findNearest(point, count);
    }

    @Override
    public void commit(PartitionId partitionId, long commit) {
        removeExpiredOfRemovedPartitions();
        find(partitionId).commit(commit);
    }

    @Override
    public Acquisition acquireLatest(PartitionId partitionId, AcquireOptions options) {
        Objects.requireNonNull(options);
        return acquire(partitionId, options, SnapshotCatalog2::lastSnapshot,
                "No last snapshot in partition " + partitionId);
    }

    @Override
    public Acquisition acquirePrevious(PartitionId partitionId, int offset, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (offset < 0) {
            throw new IllegalArgumentException("Negative offset: " + offset);
        }
        return acquire(partitionId, options, catalog -> catalog.previous(offset),
                "No snapshot at offset " + offset + " in partition " + partitionId);
    }

    @Override
    public Acquisition acquireAtOrBefore(PartitionId partitionId, long tick, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (tick < 0) {
            throw new IllegalArgumentException("Negative tick: " + tick);
        }
        return acquire(partitionId, options, catalog -> catalog.atOrBefore(tick),
                "No snapshot at tick " + tick + " or before in partition " + partitionId);
    }

    @Override
    public Acquisition acquireExact(PartitionId partitionId, long commit, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (commit < 0) {
            throw new IllegalArgumentException("Negative commit: " + commit);
        }
        return acquire(partitionId, options, catalog -> catalog.exact(commit),
                "No snapshot of commit " + commit + " in partition " + partitionId);
    }

    @Override
    public void release(Acquisition acquisition) {
        removeExpiredOfRemovedPartitions();
        PartitionStorage2 storage = partitions.get(acquisition.partitionId());
        // A finished partition has no valid acquisitions: the release does nothing.
        if (storage != null && storage.release(acquisition.id())) {
            finish(acquisition.partitionId(), storage);
        }
    }

    @Override
    public int size(Acquisition acquisition) {
        return query(acquisition, StorageSnapshot2::size);
    }

    @Override
    public List<Entity2> getAll(Acquisition acquisition, long[] entityIds) {
        return query(acquisition, snapshot -> snapshot.getAll(entityIds));
    }

    @Override
    public List<Entity2> findInRegion(Acquisition acquisition, Region2 region) {
        return query(acquisition, snapshot -> snapshot.findInRegion(region));
    }

    @Override
    public List<Entity2> findNearest(Acquisition acquisition, Point2 point, int count) {
        return query(acquisition, snapshot -> snapshot.findNearest(point, count));
    }

    @Override
    public long lastCommit(PartitionId partitionId) {
        return find(partitionId).lastCommit();
    }

    @Override
    public int size(PartitionId partitionId) {
        return find(partitionId).size();
    }

    @Override
    public CoordinateLimits limits(PartitionId partitionId) {
        return find(partitionId).limits();
    }

    /**
     * Acquires the snapshot that the selection chooses from the catalog. If a commit makes
     * the snapshot leave the window before the acquisition, it chooses again from the new
     * catalog. Fails with SnapshotNotAvailableException if the selection finds no snapshot,
     * and with IllegalStateException if the partition is marked as removed, also during
     * the acquisition.
     */
    private Acquisition acquire(PartitionId partitionId, AcquireOptions options,
            Function<SnapshotCatalog2, StorageSnapshot2> selection, String notAvailable) {
        removeExpiredOfRemovedPartitions();
        PartitionStorage2 storage = find(partitionId);
        while (true) {
            StorageSnapshot2 snapshot = selection.apply(storage.catalog());
            if (snapshot == null) {
                throw new SnapshotNotAvailableException(notAvailable);
            }
            long acquisitionId = storage.acquire(snapshot, options);
            if (acquisitionId != PartitionStorage2.NOT_ACQUIRED) {
                return new Acquisition(partitionId, acquisitionId, snapshot.commit());
            }
            // The mark closes the window: the snapshot left it, and no other snapshot
            // can be acquired.
            if (storage.isRemoved()) {
                throw removedPartition(partitionId);
            }
        }
    }

    /**
     * Runs a query on the snapshot of the acquisition. The query keeps the snapshot until
     * it ends, and then gives it back, also if it fails. The commit in the acquisition
     * must be the one of the snapshot. The partition can be marked as removed.
     */
    private <T> T query(Acquisition acquisition, Function<StorageSnapshot2, T> query) {
        PartitionStorage2 storage = partitions.get(acquisition.partitionId());
        if (storage == null) {
            // A finished partition has no valid acquisitions. Partition IDs are never used
            // again, so the node cannot tell an unknown partition from a finished one.
            throw new IllegalStateException("Acquisition was released or is expired: " + acquisition);
        }
        StorageSnapshot2 snapshot = storage.admit(acquisition.id());
        try {
            if (snapshot.commit() != acquisition.commit()) {
                throw new IllegalArgumentException("Wrong commit in acquisition: " + acquisition);
            }
            return query.apply(snapshot);
        } finally {
            if (storage.endQuery(snapshot)) {
                finish(acquisition.partitionId(), storage);
            }
        }
    }

    /** Marks a partition as removed, and removes it at once if it is already finished. */
    private void markRemoved(PartitionId partitionId, PartitionStorage2 storage) {
        // Added before the mark, so a thread that finishes the partition after the
        // mark always finds it here.
        removedPartitions.put(partitionId, storage);
        if (storage.markRemoved()) {
            finish(partitionId, storage);
        }
    }

    /**
     * Removes a finished partition from the node: its last snapshot ended. The storage
     * says it once, so only one thread calls this for a partition.
     */
    private void finish(PartitionId partitionId, PartitionStorage2 storage) {
        partitions.remove(partitionId, storage);
        removedPartitions.remove(partitionId, storage);
    }

    /**
     * Removes the expired acquisitions of the partitions marked as removed, which can
     * finish them. Those partitions have no events of their own, so the node calls this
     * at each of its events.
     */
    private void removeExpiredOfRemovedPartitions() {
        for (Map.Entry<PartitionId, PartitionStorage2> e : removedPartitions.entrySet()) {
            if (e.getValue().removeExpired()) {
                finish(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Returns the storage of a partition for its writer or for a new acquisition. Fails
     * with IllegalArgumentException if the partition is unknown, and with
     * IllegalStateException if it is marked as removed. Package-private for the tests.
     */
    PartitionStorage2 find(PartitionId partitionId) {
        PartitionStorage2 storage = partitions.get(partitionId);
        if (storage == null) {
            throw new IllegalArgumentException("Unknown partition: " + partitionId);
        }
        if (storage.isRemoved()) {
            throw removedPartition(partitionId);
        }
        return storage;
    }

    /**
     * Returns true if the node still has the partition, also if it is marked as removed
     * and not yet finished. For the tests.
     */
    boolean hasPartition(PartitionId partitionId) {
        return partitions.containsKey(partitionId);
    }

    private static IllegalStateException removedPartition(PartitionId partitionId) {
        return new IllegalStateException("Partition was removed: " + partitionId);
    }
}
