package badspace.service.partition;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A node that hosts 2D partitions in the same process as the API. */
public final class LocalPartitionNode2 implements PartitionNode2 {

    // Each partition has its own writer, possibly on a different thread.
    private final Map<PartitionId, PartitionStorage2> partitions = new ConcurrentHashMap<>();

    @Override
    public void createPartition(PartitionId partition, IndexConfig index) {
        if (partitions.putIfAbsent(partition, new PartitionStorage2(index)) != null) {
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
    public void insertAll(PartitionId partition, List<Entity2> entities) {
        find(partition).insertAll(entities);
    }

    @Override
    public List<Entity2> getAll(PartitionId partition, long[] entityIds) {
        return find(partition).getAll(entityIds);
    }

    @Override
    public void updateAll(PartitionId partition, List<Entity2> entities) {
        find(partition).updateAll(entities);
    }

    @Override
    public void removeAll(PartitionId partition, long[] entityIds) {
        find(partition).removeAll(entityIds);
    }

    @Override
    public List<Entity2> findInRegion(PartitionId partition, Region2 region) {
        return find(partition).findInRegion(region);
    }

    @Override
    public List<Entity2> findNearest(PartitionId partition, Point2 point, int count) {
        return find(partition).findNearest(point, count);
    }

    @Override
    public void commit(PartitionId partition, long commit) {
        find(partition).commit(commit);
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

    private PartitionStorage2 find(PartitionId partition) {
        PartitionStorage2 storage = partitions.get(partition);
        if (storage == null) {
            throw new IllegalArgumentException("Unknown partition: " + partition);
        }
        return storage;
    }
}
