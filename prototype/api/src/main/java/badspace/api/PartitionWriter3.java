package badspace.api;

import badspace.common.geometry.Point3;
import badspace.common.partition.Entity3;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.PendingWrites3;
import badspace.common.partition.WriteToken;
import java.util.ArrayList;
import java.util.List;

/**
 * Writer handle of a 3D partition: it carries the token of the owner and keeps the writes in
 * {@link PendingWrites3}, which sends them to the node of the partition with the token at the
 * commit, in one call (see {@link PartitionWriter}). The writes make no calls to the node: the
 * positions are checked against the limits at once, and the node checks the token and the
 * state of the partition at the commit. The handle generates the IDs of the new entities and
 * applies the removal policy of the partition. It keeps no state of the partition.
 */
public final class PartitionWriter3 implements PartitionWriter {

    private final Space3 space;
    private final PartitionNode3 node;
    private final PartitionConfig config;
    // The writes not committed yet. Only the writer that uses the handle reads and changes them.
    private final PendingWrites3 pending;

    PartitionWriter3(Space3 space, PartitionNode3 node, WriteToken token, PartitionConfig config) {
        this.space = space;
        this.node = node;
        this.config = config;
        this.pending = new PendingWrites3(node, token, config.index().limits());
    }

    @Override
    public WriteToken token() {
        return pending.token();
    }

    /** Adds a new entity and returns its ID. */
    public long insert(double x, double y, double z) {
        return insert(new Point3(x, y, z));
    }

    /** Adds a new entity and returns its ID. */
    public long insert(Point3 position) {
        return insertAll(List.of(position))[0];
    }

    /**
     * Adds many entities in one call and returns their IDs, in the same order as the positions.
     * Fails with IllegalArgumentException if a position is outside the
     * {@linkplain Partition#limits() limits}; in this case no entity is added.
     */
    public long[] insertAll(List<Point3> positions) {
        List<Entity3> entities = new ArrayList<>(positions.size());
        long[] entityIds = new long[positions.size()];
        for (int i = 0; i < entityIds.length; i++) {
            entityIds[i] = space.nextEntityId();
            entities.add(new Entity3(entityIds[i], positions.get(i)));
        }
        pending.setAll(entities);
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
    public void set(long entityId, Point3 position) {
        setAll(List.of(new Entity3(entityId, position)));
    }

    /**
     * Sets the positions of many entities in one call, like {@link #set(long, Point3)} for
     * each entity, in the order of the list: if an ID appears more than once, the last position
     * wins.
     *
     * @param entities the IDs of the entities with their positions
     * @throws IllegalArgumentException if a position is outside the
     *     {@linkplain Partition#limits() limits}; in this case nothing changes
     */
    public void setAll(List<Entity3> entities) {
        pending.setAll(entities);
    }

    @Override
    public void remove(long entityId) {
        removeAll(new long[] {entityId});
    }

    @Override
    public void removeAll(long[] entityIds) {
        pending.removeAll(entityIds);
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
        pending.updateMetadataAll(writes);
    }

    @Override
    public void commit() {
        pending.commit();
    }

    @Override
    public void rollback() {
        pending.rollback();
    }

    @Override
    public long lastCommitTick() {
        return node.lastCommitTick(pending.token());
    }

    @Override
    public void removePartition() {
        switch (config.removalPolicy()) {
            case REQUIRE_EMPTY -> node.removePartition(pending.token());
            case DISCARD_ENTITIES -> node.dropPartition(pending.token());
        }
        pending.discard();
    }

    @Override
    public void close() {
        pending.release();
    }
}
