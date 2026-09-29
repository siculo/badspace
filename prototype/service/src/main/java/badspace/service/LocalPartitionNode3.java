package badspace.service;

import badspace.common.PartitionId;
import badspace.common.PartitionNode3;
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
    public void insert(PartitionId partition, long entityId, double x, double y, double z) {
        find(partition).insert(entityId, x, y, z);
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
