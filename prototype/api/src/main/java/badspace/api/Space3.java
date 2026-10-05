package badspace.api;

import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode3;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A 3-dimensional space: it is the database and the entry point of the common API.
 * It generates partition IDs, unique within the space. Entity IDs come from
 * a generator shared by all the spaces of the process, so they are unique across spaces.
 * Its partitions can live on different nodes.
 */
public final class Space3 implements Space<PartitionNode3, Partition3> {

    // Partitions may have writers on different threads, and they share these generators.
    private final SnowflakeIdGenerator entityIds;
    private final AtomicLong nextPartitionId = new AtomicLong(1);

    /** Creates a space. All the spaces of a process must share the same entity ID generator. */
    public Space3(SnowflakeIdGenerator entityIds) {
        this.entityIds = entityIds;
    }

    @Override
    public Partition3 createPartition(PartitionNode3 node, RemovalPolicy removalPolicy) {
        Objects.requireNonNull(removalPolicy);
        PartitionId id = new PartitionId(nextPartitionId.getAndIncrement());
        node.createPartition(id);
        return new Partition3(this, node, id, removalPolicy);
    }

    @Override
    public void removePartition(Partition3 partition) {
        if (partition.space() != this) {
            throw new IllegalArgumentException("Partition belongs to another space");
        }
        partition.removeFromNode();
    }

    long nextEntityId() {
        return entityIds.nextId();
    }
}
