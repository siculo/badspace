package badspace.api;

import badspace.common.PartitionId;
import badspace.common.PartitionNode2;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A 2-dimensional space: it is the database and the entry point of the common API.
 * It generates entity IDs and partition IDs, unique within the space.
 * Its partitions can live on different nodes.
 */
public final class Space2 implements Space<PartitionNode2, Partition2> {

    // Partitions may have writers on different threads, and they share these generators.
    private final AtomicLong nextEntityId = new AtomicLong(1);
    private final AtomicLong nextPartitionId = new AtomicLong(1);

    @Override
    public Partition2 createPartition(PartitionNode2 node) {
        PartitionId id = new PartitionId(nextPartitionId.getAndIncrement());
        node.createPartition(id);
        return new Partition2(this, node, id);
    }

    long nextEntityId() {
        return nextEntityId.getAndIncrement();
    }
}
