package badspace.service;

import badspace.common.PartitionId;
import badspace.common.PartitionNode2;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A node that hosts 2D partitions in the same process as the API. */
public final class LocalPartitionNode2 implements PartitionNode2 {

    // Each partition has its own writer, possibly on a different thread.
    private final Map<PartitionId, PartitionStore2> partitions = new ConcurrentHashMap<>();

    @Override
    public void createPartition(PartitionId partition) {
        if (partitions.putIfAbsent(partition, new PartitionStore2()) != null) {
            throw new IllegalArgumentException("Partition already exists: " + partition);
        }
    }

    @Override
    public void insert(PartitionId partition, long entityId, double x, double y) {
        find(partition).insert(entityId, x, y);
    }

    @Override
    public int size(PartitionId partition) {
        return find(partition).size();
    }

    private PartitionStore2 find(PartitionId partition) {
        PartitionStore2 store = partitions.get(partition);
        if (store == null) {
            throw new IllegalArgumentException("Unknown partition: " + partition);
        }
        return store;
    }
}
