package badspace.api;

import badspace.common.partition.PartitionName;

/**
 * Operations shared by 2D and 3D spaces.
 *
 * @param <N> the type of node that hosts the partitions of this space
 * @param <P> the type of partition of this space
 */
public interface Space<N, P extends Partition> {

    /**
     * Creates a partition with the given name on the given node, with the
     * {@linkplain PartitionConfig#defaults() default} configuration, at the tick of the space.
     * The node must serve only this space.
     */
    default P createPartition(N node, PartitionName name) {
        return createPartition(node, name, PartitionConfig.defaults());
    }

    /**
     * Creates a partition with the given name on the given node, with the given configuration,
     * at the tick of the space. The node must serve only this space. The name is the public
     * identifier of the partition, chosen by the software: the saves of the partition are
     * identified by it, also after the end of the run. If the space has no partition on the node
     * yet, the space first moves the node to its tick. The new partition has no owner: the writer
     * takes it with {@link Partition#claim(long)} and writes through the handle of the token. The
     * writer removes the partition with {@link PartitionWriter#removePartition()}. Fails with
     * IllegalArgumentException if the name is already in use on the node, also by a removed
     * partition that is not destroyed yet; the names on different nodes are not checked, so the
     * software keeps them unique.
     */
    P createPartition(N node, PartitionName name, PartitionConfig config);

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
