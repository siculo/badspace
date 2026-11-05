package badspace.common.partition;

import java.util.List;

/**
 * The writes of the owner of a 2D partition that are not committed yet, with the node and the
 * token they go to. The writes make no calls to the node: they stay here, composed by entity
 * (see {@link EntityWrite2}), and {@link #commit()} sends them to the node in one call. The
 * positions are checked against the limits of the partition at once; the node checks the
 * token and the state of the partition at the commit. It works with any node of the
 * contract. It is not thread-safe: one writer uses it at a time.
 */
public final class PendingWrites2 {

    private final PartitionNode2 node;
    private final WriteToken token;
    private final CoordinateLimits limits;
    private final WriteBuffer2 buffer = new WriteBuffer2();

    /**
     * Creates the writes of an owner, with no writes yet.
     *
     * @param node the node of the partition
     * @param token the token of the owner, which the commit sends to the node
     * @param limits the limits of the coordinates of the partition, which the writes check
     */
    public PendingWrites2(PartitionNode2 node, WriteToken token, CoordinateLimits limits) {
        this.node = node;
        this.token = token;
        this.limits = limits;
    }

    /** Returns the token that the commit sends to the node. */
    public WriteToken token() {
        return token;
    }

    /**
     * Sets the positions of entities, in the order of the list: an entity moves and keeps its
     * metadata, or it is added with the same ID and no metadata. If an ID appears more than
     * once, the last position wins.
     *
     * @param entities the IDs of the entities with their positions
     * @throws IllegalArgumentException if a position is outside the limits; in this case
     *     nothing changes
     */
    public void setAll(List<Entity2> entities) {
        for (Entity2 entity : entities) {
            if (!limits.contains(entity.position())) {
                throw new IllegalArgumentException("Position of entity " + entity.id() + " is outside the limits "
                        + limits.min() + ", " + limits.max() + ": " + entity.position());
            }
        }
        for (Entity2 entity : entities) {
            buffer.set(entity);
        }
    }

    /**
     * Removes entities with their metadata; an entity that is not there, also because it
     * appears earlier in the array, is skipped.
     *
     * @param entityIds the IDs of the entities to remove
     */
    public void removeAll(long[] entityIds) {
        for (long entityId : entityIds) {
            buffer.remove(entityId);
        }
    }

    /**
     * Writes the metadata of entities, in the order of the list: for the same key the last
     * write wins, and a write on an entity that is not there does nothing.
     *
     * @param writes the writes, each of which sets or deletes one key of one entity
     */
    public void updateMetadataAll(List<MetadataWrite> writes) {
        for (MetadataWrite write : writes) {
            buffer.write(write);
        }
    }

    /**
     * Sends the writes to the node in one call, which applies them to the last commit and
     * confirms them (see {@link PartitionNode2#commit(WriteToken, List)}). After the commit there
     * are no writes; if the node rejects the commit or the commit fails, the writes stay.
     *
     * @throws OwnershipChangedException if the token is not the current one
     */
    public void commit() {
        node.commit(token, buffer.changes());
        buffer.clear();
    }

    /**
     * Discards the writes, then asks the node to go back to the last commit (see
     * {@link PartitionNode2#rollback(WriteToken)}); the node can fail like there.
     */
    public void rollback() {
        buffer.clear();
        node.rollback(token);
    }

    /**
     * Discards the writes, then releases the ownership on the node (see
     * {@link PartitionNode2#release(WriteToken)}).
     */
    public void release() {
        buffer.clear();
        node.release(token);
    }

    /** Discards the writes without calls to the node, for example after removing the partition. */
    public void discard() {
        buffer.clear();
    }
}
