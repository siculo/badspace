package badspace.service;

import badspace.common.Entity3;
import badspace.common.PartitionId;
import badspace.common.PartitionNode3;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A node that hosts 3D partitions in the same process as the API. */
public final class LocalPartitionNode3 implements PartitionNode3 {

    // Each partition has its own writer, possibly on a different thread.
    private final Map<PartitionId, PartitionStore3> partitions = new ConcurrentHashMap<>();

    @Override
    public void createPartition(PartitionId partition) {
        if (partitions.putIfAbsent(partition, new PartitionStore3()) != null) {
            throw new IllegalArgumentException("Partition already exists: " + partition);
        }
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
    public int size(PartitionId partition) {
        return find(partition).size();
    }

    private PartitionStore3 find(PartitionId partition) {
        PartitionStore3 store = partitions.get(partition);
        if (store == null) {
            throw new IllegalArgumentException("Unknown partition: " + partition);
        }
        return store;
    }
}
