package badspace.common;

/**
 * Contract of a node that hosts partitions of a 3-dimensional space.
 * A node can be in the same process as the API or in a remote process.
 * A node serves a single space, so partition IDs never collide on a node.
 */
public interface PartitionNode3 {

    /** Creates an empty partition. Fails if the ID is already in use on this node. */
    void createPartition(PartitionId partition);

    /**
     * Adds a new entity to a partition. The node trusts the caller:
     * the entity ID must not be used anywhere else.
     */
    void insert(PartitionId partition, long entityId, double x, double y, double z);

    /** Returns the number of entities in a partition. */
    int size(PartitionId partition);
}
