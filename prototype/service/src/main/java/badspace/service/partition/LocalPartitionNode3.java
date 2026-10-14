package badspace.service.partition;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.VersionNotAvailableException;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** A node that hosts 3D partitions in the same process as the API. */
public final class LocalPartitionNode3 implements PartitionNode3 {

    // Each partition has its own writer, possibly on a different thread.
    private final Map<PartitionId, PartitionStorage3> partitions = new ConcurrentHashMap<>();
    // Measures the timeouts of the acquisitions in all the partitions.
    private final MonotonicClock clock;

    /** Creates a node that measures the timeouts of the acquisitions with the clock of the system. */
    public LocalPartitionNode3() {
        this(MonotonicClock.SYSTEM);
    }

    /** Creates a node that measures the timeouts of the acquisitions with the given clock. */
    LocalPartitionNode3(MonotonicClock clock) {
        this.clock = clock;
    }

    @Override
    public void createPartition(PartitionId partitionId, IndexConfig index, int retainedPastVersions) {
        if (retainedPastVersions < 0) {
            throw new IllegalArgumentException("Negative number of retained past versions: " + retainedPastVersions);
        }
        if (partitions.putIfAbsent(partitionId, new PartitionStorage3(index, retainedPastVersions, clock)) != null) {
            throw new IllegalArgumentException("Partition already exists: " + partitionId);
        }
    }

    @Override
    public void removePartition(PartitionId partitionId) {
        // find() fails if the partition is unknown. Check and removal are two steps,
        // but only the writer of the partition changes it.
        if (find(partitionId).size() > 0) {
            throw new IllegalStateException("Partition is not empty: " + partitionId);
        }
        partitions.remove(partitionId);
    }

    @Override
    public void dropPartition(PartitionId partitionId) {
        find(partitionId);
        partitions.remove(partitionId);
    }

    @Override
    public void insertAll(PartitionId partitionId, List<Entity3> entities) {
        find(partitionId).insertAll(entities);
    }

    @Override
    public List<Entity3> getAll(PartitionId partitionId, long[] entityIds) {
        return find(partitionId).getAll(entityIds);
    }

    @Override
    public void updateAll(PartitionId partitionId, List<Entity3> entities) {
        find(partitionId).updateAll(entities);
    }

    @Override
    public void removeAll(PartitionId partitionId, long[] entityIds) {
        find(partitionId).removeAll(entityIds);
    }

    @Override
    public List<Entity3> findInRegion(PartitionId partitionId, Region3 region) {
        return find(partitionId).findInRegion(region);
    }

    @Override
    public List<Entity3> findNearest(PartitionId partitionId, Point3 point, int count) {
        return find(partitionId).findNearest(point, count);
    }

    @Override
    public void commit(PartitionId partitionId, long commit) {
        find(partitionId).commit(commit);
    }

    @Override
    public Acquisition acquireLatest(PartitionId partitionId, AcquireOptions options) {
        Objects.requireNonNull(options);
        return acquire(partitionId, options, VersionCatalog3::lastVersion,
                "No last version in partition " + partitionId);
    }

    @Override
    public Acquisition acquirePrevious(PartitionId partitionId, int offset, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (offset < 0) {
            throw new IllegalArgumentException("Negative offset: " + offset);
        }
        return acquire(partitionId, options, catalog -> catalog.previous(offset),
                "No version at offset " + offset + " in partition " + partitionId);
    }

    @Override
    public Acquisition acquireAtOrBefore(PartitionId partitionId, long tick, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (tick < 0) {
            throw new IllegalArgumentException("Negative tick: " + tick);
        }
        return acquire(partitionId, options, catalog -> catalog.atOrBefore(tick),
                "No version at tick " + tick + " or before in partition " + partitionId);
    }

    @Override
    public Acquisition acquireExact(PartitionId partitionId, long commit, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (commit < 0) {
            throw new IllegalArgumentException("Negative commit: " + commit);
        }
        return acquire(partitionId, options, catalog -> catalog.exact(commit),
                "No version of commit " + commit + " in partition " + partitionId);
    }

    @Override
    public void release(Acquisition acquisition) {
        find(acquisition.partitionId()).release(acquisition.id());
    }

    @Override
    public int size(Acquisition acquisition) {
        return query(acquisition, StorageVersion3::size);
    }

    @Override
    public List<Entity3> getAll(Acquisition acquisition, long[] entityIds) {
        return query(acquisition, version -> version.getAll(entityIds));
    }

    @Override
    public List<Entity3> findInRegion(Acquisition acquisition, Region3 region) {
        return query(acquisition, version -> version.findInRegion(region));
    }

    @Override
    public List<Entity3> findNearest(Acquisition acquisition, Point3 point, int count) {
        return query(acquisition, version -> version.findNearest(point, count));
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
     * Acquires the version that the selection chooses from the catalog. If a commit makes
     * the version leave the window before the acquisition, it chooses again from the new
     * catalog. Fails with VersionNotAvailableException if the selection finds no version.
     */
    private Acquisition acquire(PartitionId partitionId, AcquireOptions options,
            Function<VersionCatalog3, StorageVersion3> selection, String notAvailable) {
        PartitionStorage3 storage = find(partitionId);
        while (true) {
            StorageVersion3 version = selection.apply(storage.catalog());
            if (version == null) {
                throw new VersionNotAvailableException(notAvailable);
            }
            long acquisitionId = storage.acquire(version, options);
            if (acquisitionId != PartitionStorage3.NOT_ACQUIRED) {
                return new Acquisition(partitionId, acquisitionId, version.commit());
            }
        }
    }

    /**
     * Runs a query on the version of the acquisition. The query keeps the version until
     * it ends, and then gives it back, also if it fails. The commit in the acquisition
     * must be the one of the version.
     */
    private <T> T query(Acquisition acquisition, Function<StorageVersion3, T> query) {
        PartitionStorage3 storage = find(acquisition.partitionId());
        StorageVersion3 version = storage.admit(acquisition.id());
        try {
            if (version.commit() != acquisition.commit()) {
                throw new IllegalArgumentException("Wrong commit in acquisition: " + acquisition);
            }
            return query.apply(version);
        } finally {
            storage.endQuery(version);
        }
    }

    /** Returns the storage of a partition. Package-private for the tests. */
    PartitionStorage3 find(PartitionId partitionId) {
        PartitionStorage3 storage = partitions.get(partitionId);
        if (storage == null) {
            throw new IllegalArgumentException("Unknown partition: " + partitionId);
        }
        return storage;
    }
}
