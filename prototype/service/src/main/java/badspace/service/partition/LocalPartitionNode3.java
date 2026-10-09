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
    public void createPartition(PartitionId partition, IndexConfig index) {
        if (partitions.putIfAbsent(partition, new PartitionStorage3(index)) != null) {
            throw new IllegalArgumentException("Partition already exists: " + partition);
        }
    }

    @Override
    public void removePartition(PartitionId partition) {
        // find() fails if the partition is unknown. Check and removal are two steps,
        // but only the writer of the partition changes it.
        if (find(partition).size() > 0) {
            throw new IllegalStateException("Partition is not empty: " + partition);
        }
        partitions.remove(partition);
    }

    @Override
    public void dropPartition(PartitionId partition) {
        find(partition);
        partitions.remove(partition);
    }

    @Override
    public void insertAll(PartitionId partition, List<Entity3> entities) {
        find(partition).insertAll(entities);
    }

    @Override
    public List<Entity3> getAll(PartitionId partition, long[] entityIds) {
        return find(partition).getAll(entityIds);
    }

    @Override
    public void updateAll(PartitionId partition, List<Entity3> entities) {
        find(partition).updateAll(entities);
    }

    @Override
    public void removeAll(PartitionId partition, long[] entityIds) {
        find(partition).removeAll(entityIds);
    }

    @Override
    public List<Entity3> findInRegion(PartitionId partition, Region3 region) {
        return find(partition).findInRegion(region);
    }

    @Override
    public List<Entity3> findNearest(PartitionId partition, Point3 point, int count) {
        return find(partition).findNearest(point, count);
    }

    @Override
    public void commit(PartitionId partition, long commit) {
        find(partition).commit(commit);
    }

    @Override
    public PartitionVersion3 lastVersion(PartitionId partition) {
        return find(partition).lastVersion();
    }

    @Override
    public long lastCommit(PartitionId partition) {
        return find(partition).lastCommit();
    }

    @Override
    public int size(PartitionId partition) {
        return find(partition).size();
    }

    @Override
    public CoordinateLimits limits(PartitionId partition) {
        return find(partition).limits();
    }

    private PartitionStorage3 find(PartitionId partition) {
        PartitionStorage3 storage = partitions.get(partition);
        if (storage == null) {
            throw new IllegalArgumentException("Unknown partition: " + partition);
        }
        return storage;
    }
}
