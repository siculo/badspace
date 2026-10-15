package badspace.api;

import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode3;
import java.util.Objects;

/**
 * A 3-dimensional space: it is the database and the entry point of the common API.
 * Entity IDs and partition IDs come from the generators of its {@link SpaceConfig},
 * shared by all the spaces of the process, so they are unique across spaces and never
 * used again. Its partitions can live on different nodes.
 */
public final class Space3 implements Space<PartitionNode3, Partition3> {

    // Partitions may have writers on different threads, and they share the generators.
    private final SpaceConfig config;

    /**
     * Creates a space with the given configuration. All the spaces of a process must share
     * the same generators. Fails with NullPointerException if config is null.
     */
    public Space3(SpaceConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public Partition3 createPartition(PartitionNode3 node, PartitionConfig config) {
        Objects.requireNonNull(config);
        PartitionId id = new PartitionId(this.config.partitionIds().nextId());
        node.createPartition(id, config.index(), config.retainedPastSnapshots());
        return new Partition3(this, node, id, config);
    }

    @Override
    public void removePartition(Partition3 partition) {
        if (partition.space() != this) {
            throw new IllegalArgumentException("Partition belongs to another space");
        }
        partition.removeFromNode();
    }

    long nextEntityId() {
        return config.entityIds().nextId();
    }
}
