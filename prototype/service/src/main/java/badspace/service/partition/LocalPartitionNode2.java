package badspace.service.partition;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.VersionNotAvailableException;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** A node that hosts 2D partitions in the same process as the API. */
public final class LocalPartitionNode2 implements PartitionNode2 {

    // Each partition has its own writer, possibly on a different thread.
    private final Map<PartitionId, PartitionStorage2> partitions = new ConcurrentHashMap<>();
    // Measures the timeouts of the acquisitions in all the partitions.
    private final MonotonicClock clock;

    /** Creates a node that measures the timeouts of the acquisitions with the clock of the system. */
    public LocalPartitionNode2() {
        this(MonotonicClock.SYSTEM);
    }

    /** Creates a node that measures the timeouts of the acquisitions with the given clock. */
    LocalPartitionNode2(MonotonicClock clock) {
        this.clock = clock;
    }

    @Override
    public void createPartition(PartitionId partitionId, IndexConfig index, int retainedPastVersions) {
        if (retainedPastVersions < 0) {
            throw new IllegalArgumentException("Negative number of retained past versions: " + retainedPastVersions);
        }
        if (partitions.putIfAbsent(partitionId, new PartitionStorage2(index, retainedPastVersions, clock)) != null) {
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
        find(partitionId).commit(commit);
    }

    @Override
    public Acquisition acquireLatest(PartitionId partitionId, AcquireOptions options) {
        Objects.requireNonNull(options);
        PartitionStorage2 storage = find(partitionId);
        return acquire(partitionId, storage, storage.catalog().lastVersion(), options);
    }

    @Override
    public Acquisition acquirePrevious(PartitionId partitionId, int offset, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (offset < 0) {
            throw new IllegalArgumentException("Negative offset: " + offset);
        }
        PartitionStorage2 storage = find(partitionId);
        StorageVersion2 version = storage.catalog().previous(offset);
        if (version == null) {
            throw new VersionNotAvailableException(
                    "No version at offset " + offset + " in partition " + partitionId);
        }
        return acquire(partitionId, storage, version, options);
    }

    @Override
    public Acquisition acquireAtOrBefore(PartitionId partitionId, long tick, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (tick < 0) {
            throw new IllegalArgumentException("Negative tick: " + tick);
        }
        PartitionStorage2 storage = find(partitionId);
        StorageVersion2 version = storage.catalog().atOrBefore(tick);
        if (version == null) {
            throw new VersionNotAvailableException(
                    "No version at tick " + tick + " or before in partition " + partitionId);
        }
        return acquire(partitionId, storage, version, options);
    }

    @Override
    public Acquisition acquireExact(PartitionId partitionId, long commit, AcquireOptions options) {
        Objects.requireNonNull(options);
        if (commit < 0) {
            throw new IllegalArgumentException("Negative commit: " + commit);
        }
        PartitionStorage2 storage = find(partitionId);
        StorageVersion2 version = storage.catalog().exact(commit);
        if (version == null) {
            throw new VersionNotAvailableException(
                    "No version of commit " + commit + " in partition " + partitionId);
        }
        return acquire(partitionId, storage, version, options);
    }

    @Override
    public void release(Acquisition acquisition) {
        find(acquisition.partitionId()).release(acquisition.id());
    }

    @Override
    public int size(Acquisition acquisition) {
        return admit(acquisition).size();
    }

    @Override
    public List<Entity2> getAll(Acquisition acquisition, long[] entityIds) {
        return admit(acquisition).getAll(entityIds);
    }

    @Override
    public List<Entity2> findInRegion(Acquisition acquisition, Region2 region) {
        return admit(acquisition).findInRegion(region);
    }

    @Override
    public List<Entity2> findNearest(Acquisition acquisition, Point2 point, int count) {
        return admit(acquisition).findNearest(point, count);
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

    private static Acquisition acquire(PartitionId partitionId, PartitionStorage2 storage, StorageVersion2 version,
            AcquireOptions options) {
        return new Acquisition(partitionId, storage.acquire(version, options), version.commit());
    }

    /**
     * Returns the version of the acquisition, which the query keeps until it ends.
     * The commit in the acquisition must be the one of the version.
     */
    private StorageVersion2 admit(Acquisition acquisition) {
        StorageVersion2 version = find(acquisition.partitionId()).admit(acquisition.id());
        if (version.commit() != acquisition.commit()) {
            throw new IllegalArgumentException("Wrong commit in acquisition: " + acquisition);
        }
        return version;
    }

    private PartitionStorage2 find(PartitionId partitionId) {
        PartitionStorage2 storage = partitions.get(partitionId);
        if (storage == null) {
            throw new IllegalArgumentException("Unknown partition: " + partitionId);
        }
        return storage;
    }
}
