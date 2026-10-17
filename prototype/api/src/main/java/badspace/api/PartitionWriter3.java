package badspace.api;

import badspace.common.geometry.Point3;
import badspace.common.partition.Entity3;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.WriteToken;
import java.util.ArrayList;
import java.util.List;

/**
 * Writer handle of a 3D partition: it carries the token of the owner and sends the writes to
 * the node of the partition with it (see {@link PartitionWriter}). Single-entity operations are
 * batches of one entity: to write many entities, the batch operations make fewer calls to the
 * node. The handle keeps no state of the partition: the node checks the token and the state
 * of the partition at each call.
 */
public final class PartitionWriter3 implements PartitionWriter {

    private final Space3 space;
    private final PartitionNode3 node;
    private final WriteToken token;
    private final PartitionConfig config;

    PartitionWriter3(Space3 space, PartitionNode3 node, WriteToken token, PartitionConfig config) {
        this.space = space;
        this.node = node;
        this.token = token;
        this.config = config;
    }

    @Override
    public WriteToken token() {
        return token;
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
        long[] entityIds = new long[positions.size()];
        List<Entity3> entities = new ArrayList<>(positions.size());
        for (int i = 0; i < entityIds.length; i++) {
            entityIds[i] = space.nextEntityId();
            entities.add(new Entity3(entityIds[i], positions.get(i)));
        }
        node.insertAll(token, entities);
        return entityIds;
    }

    /**
     * Moves an entity. Fails with NoSuchElementException if the ID is not in the partition,
     * and with IllegalArgumentException if the position is outside the
     * {@linkplain Partition#limits() limits}.
     */
    public void update(long entityId, Point3 position) {
        updateAll(List.of(new Entity3(entityId, position)));
    }

    /**
     * Moves many entities in one call. Fails with NoSuchElementException if an ID is not
     * in the partition, and with IllegalArgumentException if a position is outside the
     * {@linkplain Partition#limits() limits}; in these cases no entity moves.
     */
    public void updateAll(List<Entity3> entities) {
        node.updateAll(token, entities);
    }

    @Override
    public void remove(long entityId) {
        removeAll(new long[] {entityId});
    }

    @Override
    public void removeAll(long[] entityIds) {
        node.removeAll(token, entityIds);
    }

    @Override
    public void commit() {
        node.commit(token);
    }

    @Override
    public void removePartition() {
        switch (config.removalPolicy()) {
            case REQUIRE_EMPTY -> node.removePartition(token);
            case DISCARD_ENTITIES -> node.dropPartition(token);
        }
    }

    @Override
    public void close() {
        node.release(token);
    }
}
