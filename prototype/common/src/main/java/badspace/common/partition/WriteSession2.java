package badspace.common.partition;

import java.util.List;

/**
 * The write session of the owner of a 2D partition: the writes, the commits and the rollbacks
 * that the owner sends to the node with its token, from the token to its release. The
 * {@linkplain PartitionNode2#writeMode() mode of the node} decides when the writes reach it: at once, each with a call, or all together at the commit. The result is the
 * same in the two modes: the same writes, in the same order, make the same commits; a rejected
 * write or commit changes nothing, and a failed one brings the partition back to the last
 * commit and discards all the writes after it. Only one thing changes: in the direct mode the
 * node rejects a write at once for the token or the state of the partition, while with the
 * buffer it rejects the commit, and the writes stay. It works with any node of the contract.
 * It is not thread-safe: one writer uses it at a time.
 */
public interface WriteSession2 {

    /**
     * Returns a session of an owner, with no writes yet, in the mode of the node.
     *
     * @param node the node of the partition
     * @param token the token of the owner, which the calls send to the node
     * @param limits the limits of the coordinates of the partition, which the writes check
     */
    static WriteSession2 of(PartitionNode2 node, WriteToken token, CoordinateLimits limits) {
        return switch (node.writeMode()) {
            case DIRECT -> new DirectWriteSession2(node, token);
            case BUFFERED -> new BufferedWriteSession2(node, token, limits);
        };
    }

    /** Returns the token that the calls send to the node. */
    WriteToken token();

    /**
     * Sets the positions of entities, in the order of the list: an entity moves and keeps its
     * metadata, or it is added with the same ID and no metadata. If an ID appears more than
     * once, the last position wins.
     *
     * @param entities the IDs of the entities with their positions
     * @throws IllegalArgumentException if a position is outside the limits; in this case
     *     nothing changes
     * @throws OwnershipChangedException in the direct mode, if the token is not the current
     *     one; in this case nothing changes
     * @throws PartitionFailureException in the direct mode, if the write fails after the
     *     checks: the partition goes back to the last commit
     */
    void setAll(List<Entity2> entities);

    /**
     * Removes entities with their metadata; an entity that is not there, also because it
     * appears earlier in the array, is skipped. Fails in the direct mode like
     * {@link #setAll(List)}.
     *
     * @param entityIds the IDs of the entities to remove
     */
    void removeAll(long[] entityIds);

    /**
     * Writes the metadata of entities, in the order of the list: for the same key the last
     * write wins, and a write on an entity that is not there does nothing. Fails in the direct
     * mode like {@link #setAll(List)}.
     *
     * @param writes the writes, each of which sets or deletes one key of one entity
     */
    void updateMetadataAll(List<MetadataWrite> writes);

    /**
     * Confirms the writes in the current tick of the partition (see
     * {@link PartitionNode2#commit(WriteToken, List)}). After the commit there are no writes. If
     * the node rejects the commit, nothing changes and the writes stay; if the commit fails
     * after the checks, the node goes back to the last commit and the writes are discarded.
     *
     * @throws OwnershipChangedException if the token is not the current one; the writes stay
     * @throws PartitionFailureException if the commit fails after the checks; the writes are
     *     discarded, and an {@link Error} goes to the caller as it is, also after discarding them
     */
    void commit();

    /**
     * Discards the writes, then asks the node to go back to the last commit (see
     * {@link PartitionNode2#rollback(WriteToken)}); the node can fail like there.
     */
    void rollback();

    /**
     * Discards the writes and releases the ownership on the node (see
     * {@link PartitionNode2#release(WriteToken)}).
     */
    void release();

    /**
     * Discards the writes kept by the writer without calls to the node, for example after
     * removing the partition, which stops the writes on the node.
     */
    void discard();
}
