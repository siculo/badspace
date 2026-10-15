package badspace.api;

/**
 * Operations shared by 2D and 3D spaces.
 *
 * @param <N> the type of node that hosts the partitions of this space
 * @param <P> the type of partition of this space
 */
public interface Space<N, P extends Partition> {

    /**
     * Creates a partition on the given node, with the {@linkplain PartitionConfig#defaults() default}
     * configuration, at the tick of the space. The node must serve only this space.
     */
    default P createPartition(N node) {
        return createPartition(node, PartitionConfig.defaults());
    }

    /**
     * Creates a partition on the given node, with the given configuration, at the tick of the space.
     * The node must serve only this space. If the space has no partition on the node yet, the space
     * first moves the node to its tick.
     */
    P createPartition(N node, PartitionConfig config);

    /**
     * Removes a partition from its node, following its removal policy. After this call the partition
     * can no longer be used. The writer of the partition must call it. Fails with IllegalStateException
     * if the policy is REQUIRE_EMPTY and the partition still has entities (in this case the partition
     * does not change), or if the partition was already removed. Fails with IllegalArgumentException
     * if the partition belongs to another space.
     */
    void removePartition(P partition);

    /**
     * Moves the tick of the space forward to the given tick, and then the tick of each node on
     * which the space created partitions; each node moves its partitions. The partitions move
     * one after the other, not all at the same moment. The same tick again does nothing, and
     * ticks can be skipped. Fails with IllegalArgumentException if the tick is less than the
     * tick of the space; in this case nothing changes.
     */
    void setTick(long tick);

    /** Returns the tick of the space: 0 for a new space. Any thread can call it. */
    long tick();
}
