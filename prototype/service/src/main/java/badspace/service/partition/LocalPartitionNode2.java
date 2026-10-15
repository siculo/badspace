package badspace.service.partition;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.RetentionWindow;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
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
 * The removal of a partition goes through the {@linkplain PartitionState states}. The
 * request of removal stops the writes and the commits; the next move of the tick makes
 * the removal effective, and the snapshots of the ticks before it stay acquirable while
 * they are in the window. The node keeps the storage, under the same ID, until its last
 * snapshot ends: then the storage says that the partition is destroyed, and the node
 * removes it. Every move of the tick reaches all the partitions, also the removed ones,
 * and looks for their expired acquisitions, so a reader that never releases cannot keep
 * a removed partition for ever.
 * <p>
 * A lock orders the moves to a new tick and the creation of partitions: a partition created
 * while the node moves to a new tick is created either before the move, and then moves too,
 * or after it, at the new tick. So no partition stays behind the node.
 */
public final class LocalPartitionNode2 implements PartitionNode2 {

    // Each partition has its own writer, possibly on a different thread. A removed
    // partition stays here until it is destroyed; the thread that destroys it, any
    // thread, removes it.
    private final Map<PartitionId, PartitionStorage2> partitions = new ConcurrentHashMap<>();
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
    public void createPartition(PartitionId partitionId, IndexConfig index, int retainedTicks) {
        if (retainedTicks < RetentionWindow.MIN_RETAINED_TICKS) {
            throw new IllegalArgumentException("Number of retained ticks is less than "
                    + RetentionWindow.MIN_RETAINED_TICKS + ": " + retainedTicks);
        }
        tickLock.lock();
        try {
            // A removed partition keeps its ID until it is destroyed, so this also rejects
            // its ID.
            PartitionStorage2 storage = new PartitionStorage2(index, retainedTicks, clock, tick);
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
            // The removed partitions are in the map too.
            for (Map.Entry<PartitionId, PartitionStorage2> e : partitions.entrySet()) {
                if (e.getValue().setTick(tick)) {
                    finish(e.getKey(), e.getValue());
                }
            }
        } finally {
            tickLock.unlock();
        }
    }

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public long tick(PartitionId partitionId) {
        return findAny(partitionId).tick();
    }

    @Override
    public long creationTick(PartitionId partitionId) {
        return findAny(partitionId).creationTick();
    }

    @Override
    public void removePartition(PartitionId partitionId) {
        find(partitionId).requestRemoval(true);
    }

    @Override
    public void dropPartition(PartitionId partitionId) {
        find(partitionId).requestRemoval(false);
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
    public void commit(PartitionId partitionId) {
        find(partitionId).commit();
    }

    @Override
    public Acquisition acquire(PartitionId partitionId, long tick, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (tick < 0) {
            throw new IllegalArgumentException("Negative tick: " + tick);
        }
        return acquire(partitionId, options, false, tick);
    }

    @Override
    public Acquisition acquireLatest(PartitionId partitionId, AcquireOptions options) {
        Objects.requireNonNull(options);
        return acquire(partitionId, options, true, 0);
    }

    @Override
    public void release(Acquisition acquisition) {
        PartitionStorage2 storage = partitions.get(acquisition.partitionId());
        // A destroyed partition has no valid acquisitions: the release does nothing.
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
    public int size(PartitionId partitionId) {
        return find(partitionId).size();
    }

    @Override
    public CoordinateLimits limits(PartitionId partitionId) {
        return find(partitionId).limits();
    }

    /**
     * Acquires the snapshot of a tick: the tick before the current one if latest is true,
     * otherwise the given tick. The tick and the snapshot come from the same catalog. If a move
     * of the tick makes the snapshot leave the window before the acquisition, it chooses again
     * from the new catalog. Fails with SnapshotNotAvailableException if the tick is not in the
     * window, also because the partition was removed. A removed partition gives the snapshots
     * of the ticks before its removal while they are in the window.
     */
    private Acquisition acquire(PartitionId partitionId, AcquireOptions options, boolean latest, long tick) {
        PartitionStorage2 storage = findAny(partitionId);
        while (true) {
            SnapshotCatalog2 catalog = storage.catalog();
            long chosen = latest ? catalog.tick() - 1 : tick;
            StorageSnapshot2 snapshot = catalog.at(chosen);
            if (snapshot == null) {
                if (latest && catalog.tick() == catalog.creationTick()) {
                    throw new SnapshotNotAvailableException(Reason.NOT_YET_PUBLISHED,
                            "No snapshot published yet in partition " + partitionId);
                }
                throw new SnapshotNotAvailableException(catalog.reasonNotAvailable(chosen),
                        "No snapshot of tick " + chosen + " in partition " + partitionId + " at tick " + catalog.tick());
            }
            long acquisitionId = storage.acquire(snapshot, chosen, options);
            if (acquisitionId != PartitionStorage2.NOT_ACQUIRED) {
                return new Acquisition(partitionId, acquisitionId, chosen);
            }
        }
    }

    /**
     * Runs a query on the snapshot of the acquisition. The query keeps the snapshot until
     * it ends, and then gives it back, also if it fails. The tick in the acquisition
     * must be the acquired one. The partition can be removed.
     */
    private <T> T query(Acquisition acquisition, Function<StorageSnapshot2, T> query) {
        PartitionStorage2 storage = partitions.get(acquisition.partitionId());
        if (storage == null) {
            // A destroyed partition has no valid acquisitions. Partition IDs are never used
            // again, so the node cannot tell an unknown partition from a destroyed one.
            throw new IllegalStateException("Acquisition was released or is expired: " + acquisition);
        }
        StorageSnapshot2 snapshot = storage.admit(acquisition.id(), acquisition.tick());
        try {
            return query.apply(snapshot);
        } finally {
            if (storage.endQuery(snapshot)) {
                finish(acquisition.partitionId(), storage);
            }
        }
    }

    /**
     * Removes a destroyed partition from the node: its last snapshot ended. The storage
     * says it once, so only one thread calls this for a partition.
     */
    private void finish(PartitionId partitionId, PartitionStorage2 storage) {
        partitions.remove(partitionId, storage);
    }

    /**
     * Returns the storage of a partition for its writer. Fails with IllegalArgumentException
     * if the partition is unknown, and with IllegalStateException if its removal was requested.
     * Package-private for the tests.
     */
    PartitionStorage2 find(PartitionId partitionId) {
        PartitionStorage2 storage = findAny(partitionId);
        if (storage.state() != PartitionState.ACTIVE) {
            throw new IllegalStateException("Partition was removed: " + partitionId);
        }
        return storage;
    }

    /**
     * Returns the storage of a partition, also if it is removed, for the readers and the
     * ticks. Fails with IllegalArgumentException if the partition is unknown: never created
     * on this node, or already destroyed. Package-private for the tests.
     */
    PartitionStorage2 findAny(PartitionId partitionId) {
        PartitionStorage2 storage = partitions.get(partitionId);
        if (storage == null) {
            throw new IllegalArgumentException("Unknown partition: " + partitionId);
        }
        return storage;
    }

    /**
     * Returns true if the node still has the partition, also if it is removed and not yet
     * destroyed. For the tests.
     */
    boolean hasPartition(PartitionId partitionId) {
        return partitions.containsKey(partitionId);
    }
}
