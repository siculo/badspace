package badspace.api;

/**
 * Operations shared by 2D and 3D spaces.
 *
 * @param <N> the type of node that hosts the partitions of this space
 * @param <P> the type of partition of this space
 */
public interface Space<N, P extends Partition> {

    /**
     * Creates a partition on the given node, with the REQUIRE_EMPTY removal policy.
     * The node must serve only this space.
     */
    default P createPartition(N node) {
        return createPartition(node, RemovalPolicy.REQUIRE_EMPTY);
    }

    /** Creates a partition on the given node, with the given removal policy. The node must serve only this space. */
    P createPartition(N node, RemovalPolicy removalPolicy);

    /**
     * Removes a partition from its node, following its removal policy. After this call the partition
     * can no longer be used. The writer of the partition must call it. Fails with IllegalStateException
     * if the policy is REQUIRE_EMPTY and the partition still has entities (in this case the partition
     * does not change), or if the partition was already removed. Fails with IllegalArgumentException
     * if the partition belongs to another space.
     */
    void removePartition(P partition);
}
