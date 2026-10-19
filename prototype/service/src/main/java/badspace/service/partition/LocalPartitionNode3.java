package badspace.service.partition;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.RetentionWindow;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.WriteToken;
import badspace.service.partition.snapshot.MonotonicClock;
import badspace.service.partition.snapshot.SnapshotCatalog3;
import badspace.service.partition.snapshot.StorageSnapshot3;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * A node that hosts 3D partitions in the same process as the API.
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
 * <p>
 * The writers of different partitions can work on different threads. Each partition has its
 * own lock and its own sequence of commits, and the writes and the commits do not take the
 * lock of the node: a writer waits only for a move of the tick of its own partition, never
 * for the writers of the other partitions.
 */
public final class LocalPartitionNode3 implements PartitionNode3 {

    // Each partition has its own writer, possibly on a different thread. A removed
    // partition stays here until it is destroyed; the thread that destroys it, any
    // thread, removes it.
    private final Map<PartitionId, PartitionStorage3> partitions = new ConcurrentHashMap<>();
    // Measures the timeouts of the acquisitions in all the partitions.
    private final MonotonicClock clock;
    // Makes the storage of each new partition.
    private final StorageFactory storageFactory;
    // Orders setTick and createPartition: both run while holding it.
    private final ReentrantLock tickLock = new ReentrantLock();
    // The tick of the node. Written by setTick while holding tickLock, read by any thread.
    private volatile long tick;

    /** Creates a node that measures the timeouts of the acquisitions with the clock of the system. */
    public LocalPartitionNode3() {
        this(MonotonicClock.SYSTEM);
    }

    /** Creates a node that measures the timeouts of the acquisitions with the given clock. */
    LocalPartitionNode3(MonotonicClock clock) {
        this(clock, PartitionStorage3::new);
    }

    /**
     * Creates a node that measures the timeouts with the given clock and makes the storages of
     * the partitions with the factory. The tests use it to stop a commit inside the index of a
     * partition, and to know when a move of the tick waits for the lock of a partition.
     */
    LocalPartitionNode3(MonotonicClock clock, StorageFactory storageFactory) {
        this.clock = clock;
        this.storageFactory = storageFactory;
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
            PartitionStorage3 storage = storageFactory.create(index, retainedTicks, clock, tick);
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
            for (Map.Entry<PartitionId, PartitionStorage3> e : partitions.entrySet()) {
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
    public void removePartition(WriteToken token) {
        find(token.partitionId()).requestRemoval(token.generation(), true);
    }

    @Override
    public void dropPartition(WriteToken token) {
        find(token.partitionId()).requestRemoval(token.generation(), false);
    }

    @Override
    public Ownership ownership(PartitionId partitionId) {
        return findAny(partitionId).ownership();
    }

    @Override
    public Optional<WriteToken> claim(PartitionId partitionId, long expectedGeneration) {
        if (expectedGeneration < 0) {
            throw new IllegalArgumentException("Negative generation: " + expectedGeneration);
        }
        long generation = findAny(partitionId).claimOwnership(expectedGeneration);
        if (generation == PartitionStorage3.NOT_CLAIMED) {
            return Optional.empty();
        }
        return Optional.of(new WriteToken(partitionId, generation));
    }

    @Override
    public void release(WriteToken token) {
        PartitionStorage3 storage = partitions.get(token.partitionId());
        // A destroyed partition has no owner: the release does nothing.
        if (storage != null) {
            storage.releaseOwnership(token.generation());
        }
    }

    @Override
    public void insertAll(WriteToken token, List<Entity3> entities) {
        find(token.partitionId()).insertAll(token.generation(), entities);
    }

    @Override
    public void updateAll(WriteToken token, List<Entity3> entities) {
        find(token.partitionId()).updateAll(token.generation(), entities);
    }

    @Override
    public void removeAll(WriteToken token, long[] entityIds) {
        find(token.partitionId()).removeAll(token.generation(), entityIds);
    }

    @Override
    public void updateMetadataAll(WriteToken token, List<MetadataWrite> writes) {
        find(token.partitionId()).updateMetadataAll(token.generation(), writes);
    }

    @Override
    public void commit(WriteToken token) {
        PartitionStorage3 storage = find(token.partitionId());
        storage.commit(token.generation());
        // After the commit releases the lock of the partition, the partition can be removed and
        // retired by other threads: the scan can end its last snapshot.
        removeExpired(token.partitionId(), storage);
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
        PartitionStorage3 storage = partitions.get(acquisition.partitionId());
        // A destroyed partition has no valid acquisitions: the release does nothing.
        if (storage != null && storage.release(acquisition.id())) {
            finish(acquisition.partitionId(), storage);
        }
    }

    @Override
    public int size(Acquisition acquisition) {
        return query(acquisition, StorageSnapshot3::size);
    }

    @Override
    public List<Entity3> getAll(Acquisition acquisition, long[] entityIds) {
        return query(acquisition, snapshot -> snapshot.getAll(entityIds));
    }

    @Override
    public List<ApplicationMetadata> getMetadataAll(Acquisition acquisition, long[] entityIds) {
        return query(acquisition, snapshot -> snapshot.getMetadataAll(entityIds));
    }

    @Override
    public List<Entity3> findInRegion(Acquisition acquisition, Region3 region) {
        return query(acquisition, snapshot -> snapshot.findInRegion(region));
    }

    @Override
    public List<Entity3> findNearest(Acquisition acquisition, Point3 point, int count) {
        return query(acquisition, snapshot -> snapshot.findNearest(point, count));
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
        PartitionStorage3 storage = findAny(partitionId);
        while (true) {
            SnapshotCatalog3 catalog = storage.catalog();
            long chosen = latest ? catalog.tick() - 1 : tick;
            StorageSnapshot3 snapshot = catalog.at(chosen);
            if (snapshot == null) {
                if (latest && catalog.tick() == catalog.creationTick()) {
                    throw new SnapshotNotAvailableException(Reason.NOT_YET_PUBLISHED,
                            "No snapshot published yet in partition " + partitionId);
                }
                throw new SnapshotNotAvailableException(catalog.reasonNotAvailable(chosen),
                        "No snapshot of tick " + chosen + " in partition " + partitionId + " at tick " + catalog.tick());
            }
            long acquisitionId = storage.acquire(snapshot, chosen, options);
            if (acquisitionId != PartitionStorage3.NOT_ACQUIRED) {
                // The new acquisition can already be expired: the scan can end the last snapshot.
                removeExpired(partitionId, storage);
                return new Acquisition(partitionId, acquisitionId, chosen);
            }
        }
    }

    /**
     * Runs a query on the snapshot of the acquisition. The query keeps the snapshot until
     * it ends, and then gives it back, also if it fails. The tick in the acquisition
     * must be the acquired one. The partition can be removed.
     */
    private <T> T query(Acquisition acquisition, Function<StorageSnapshot3, T> query) {
        PartitionStorage3 storage = partitions.get(acquisition.partitionId());
        if (storage == null) {
            // A destroyed partition has no valid acquisitions. Partition IDs are never used
            // again, so the node cannot tell an unknown partition from a destroyed one.
            throw new IllegalStateException("Acquisition was released or is expired: " + acquisition);
        }
        StorageSnapshot3 snapshot = storage.admit(acquisition.id(), acquisition.tick());
        try {
            return query.apply(snapshot);
        } finally {
            if (storage.endQuery(snapshot)) {
                finish(acquisition.partitionId(), storage);
            }
        }
    }

    /**
     * Removes the expired acquisitions of a partition, and removes the partition from the node
     * if this ended its last snapshot.
     */
    private void removeExpired(PartitionId partitionId, PartitionStorage3 storage) {
        if (storage.removeExpired()) {
            finish(partitionId, storage);
        }
    }

    /**
     * Removes a destroyed partition from the node: its last snapshot ended. The storage
     * says it once, so only one thread calls this for a partition.
     */
    private void finish(PartitionId partitionId, PartitionStorage3 storage) {
        partitions.remove(partitionId, storage);
    }

    /**
     * Returns the storage of a partition for its writer. Fails with IllegalArgumentException
     * if the partition is unknown, and with IllegalStateException if its removal was requested.
     */
    private PartitionStorage3 find(PartitionId partitionId) {
        PartitionStorage3 storage = findAny(partitionId);
        if (storage.state() != PartitionState.ACTIVE) {
            throw new IllegalStateException("Partition was removed: " + partitionId);
        }
        return storage;
    }

    /**
     * Returns the storage of a partition, also if it is removed, for the readers and the
     * ticks. Fails with IllegalArgumentException if the partition is unknown: never created
     * on this node, or already destroyed.
     */
    private PartitionStorage3 findAny(PartitionId partitionId) {
        PartitionStorage3 storage = partitions.get(partitionId);
        if (storage == null) {
            throw new IllegalArgumentException("Unknown partition: " + partitionId);
        }
        return storage;
    }

    /** Makes the storage of a new partition at the given tick. */
    @FunctionalInterface
    interface StorageFactory {

        PartitionStorage3 create(IndexConfig index, int retainedTicks, MonotonicClock clock, long creationTick);
    }
}
