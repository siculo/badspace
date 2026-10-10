package badspace.service.partition;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.PartitionVersion3;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A node that hosts 3D partitions in the same process as the API. */
public final class LocalPartitionNode3 implements PartitionNode3 {

    // Each partition has its own writer, possibly on a different thread.
    private final Map<PartitionId, PartitionStorage3> partitions = new ConcurrentHashMap<>();

    @Override
    public void createPartition(PartitionId partitionId, IndexConfig index) {
        if (partitions.putIfAbsent(partitionId, new PartitionStorage3(index)) != null) {
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
    public PartitionVersion3 lastVersion(PartitionId partitionId) {
        return find(partitionId).lastVersion();
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

    private PartitionStorage3 find(PartitionId partitionId) {
        PartitionStorage3 storage = partitions.get(partitionId);
        if (storage == null) {
            throw new IllegalArgumentException("Unknown partition: " + partitionId);
        }
        return storage;
    }
}
