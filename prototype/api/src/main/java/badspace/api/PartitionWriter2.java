package badspace.api;

import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.WriteSession2;
import badspace.common.partition.WriteToken;
import java.util.ArrayList;
import java.util.List;

/**
 * Writer handle of a 2D partition: it carries the token of the owner and sends the writes
 * through {@link WriteSession2}, in the write mode of the node (see {@link PartitionWriter}).
 * The handle generates the IDs of the new entities and applies the removal policy of the
 * partition. It keeps no state of the partition: at most, with the buffer, the writes not sent.
 */
public final class PartitionWriter2 implements PartitionWriter {

    private final Space2 space;
    private final PartitionNode2 node;
    private final PartitionConfig config;
    // The write session of the token. Only the writer that uses the handle calls it.
    private final WriteSession2 session;

    PartitionWriter2(Space2 space, PartitionNode2 node, WriteToken token, PartitionConfig config) {
        this.space = space;
        this.node = node;
        this.config = config;
        this.session = WriteSession2.of(node, token, config.index().limits());
    }

    @Override
    public WriteToken token() {
        return session.token();
    }

    /** Adds a new entity and returns its ID. */
    public long insert(double x, double y) {
        return insert(new Point2(x, y));
    }

    /** Adds a new entity and returns its ID. */
    public long insert(Point2 position) {
        return insertAll(List.of(position))[0];
    }

    /**
     * Adds many entities in one call and returns their IDs, in the same order as the positions.
     * Fails with IllegalArgumentException if a position is outside the
     * {@linkplain Partition#limits() limits}; in this case no entity is added.
     */
    public long[] insertAll(List<Point2> positions) {
        List<Entity2> entities = new ArrayList<>(positions.size());
        long[] entityIds = new long[positions.size()];
        for (int i = 0; i < entityIds.length; i++) {
            entityIds[i] = space.nextEntityId();
            entities.add(new Entity2(entityIds[i], positions.get(i)));
        }
        session.setAll(entities);
        return entityIds;
    }

    /**
     * Sets the position of an entity. If the entity is in the partition, it moves and keeps its
     * application metadata; if not, for example after a {@link #remove(long)}, it is added
     * again with the same ID and no metadata. The ID must come from the entity ID generator of
     * the space: from {@link #insert}, from a snapshot of a partition of the space, or from a
     * partition loaded from a save. A new entity gets a new ID only with {@code insert}.
     *
     * @param entityId the ID of the entity
     * @param position the new position
     * @throws IllegalArgumentException if the position is outside the
     *     {@linkplain Partition#limits() limits}
     */
    public void set(long entityId, Point2 position) {
        setAll(List.of(new Entity2(entityId, position)));
    }

    /**
     * Sets the positions of many entities in one call, like {@link #set(long, Point2)} for
     * each entity, in the order of the list: if an ID appears more than once, the last position
     * wins.
     *
     * @param entities the IDs of the entities with their positions
     * @throws IllegalArgumentException if a position is outside the
     *     {@linkplain Partition#limits() limits}; in this case nothing changes
     */
    public void setAll(List<Entity2> entities) {
        session.setAll(entities);
    }

    @Override
    public void remove(long entityId) {
        removeAll(new long[] {entityId});
    }

    @Override
    public void removeAll(long[] entityIds) {
        session.removeAll(entityIds);
    }

    @Override
    public void putMetadata(long entityId, String key, long value) {
        updateMetadataAll(List.of(MetadataWrite.put(entityId, key, value)));
    }

    @Override
    public void deleteMetadata(long entityId, String key) {
        updateMetadataAll(List.of(MetadataWrite.delete(entityId, key)));
    }

    @Override
    public void updateMetadataAll(List<MetadataWrite> writes) {
        session.updateMetadataAll(writes);
    }

    @Override
    public void flush() {
        session.flush();
    }

    @Override
    public void commit() {
        session.commit();
    }

    @Override
    public void rollback() {
        session.rollback();
    }

    @Override
    public long lastCommitTick() {
        return node.lastCommitTick(session.token());
    }

    @Override
    public void removePartition() {
        switch (config.removalPolicy()) {
            case REQUIRE_EMPTY -> node.removePartition(session.token());
            case DISCARD_ENTITIES -> node.dropPartition(session.token());
        }
        session.discard();
    }

    @Override
    public void close() {
        session.release();
    }
}
