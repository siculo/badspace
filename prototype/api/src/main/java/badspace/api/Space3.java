package badspace.api;

import badspace.common.PartitionId;
import badspace.common.PartitionNode3;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A 3-dimensional space: it is the database and the entry point of the common API.
 * It generates entity IDs and partition IDs, unique within the space.
 * Its partitions can live on different nodes.
 */
public final class Space3 {

    // Partitions may have writers on different threads, and they share these generators.
    private final AtomicLong nextEntityId = new AtomicLong(1);
    private final AtomicLong nextPartitionId = new AtomicLong(1);

    /** Creates a partition on the given node. The node must serve only this space. */
    public Partition3 createPartition(PartitionNode3 node) {
        PartitionId id = new PartitionId(nextPartitionId.getAndIncrement());
        node.createPartition(id);
        return new Partition3(this, node, id);
    }

    long nextEntityId() {
        return nextEntityId.getAndIncrement();
    }
}
