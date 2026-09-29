package badspace.api;

/**
 * Operations shared by 2D and 3D spaces.
 *
 * @param <N> the type of node that hosts the partitions of this space
 * @param <P> the type of partition of this space
 */
public interface Space<N, P extends Partition> {

    /** Creates a partition on the given node. The node must serve only this space. */
    P createPartition(N node);
}
