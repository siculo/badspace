package badspace.api;

import badspace.common.Entity2;
import badspace.common.PartitionId;
import badspace.common.PartitionNode2;
import badspace.common.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proxy of a 2D partition that lives on a node. It has a single writer and is not thread-safe.
 * Single-entity operations are batches of one entity: to write many entities,
 * the batch operations make fewer calls to the node.
 */
public final class Partition2 implements Partition {

    private final Space2 space;
    private final PartitionNode2 node;
    private final PartitionId id;

    Partition2(Space2 space, PartitionNode2 node, PartitionId id) {
        this.space = space;
        this.node = node;
        this.id = id;
    }

    /** Adds a new entity and returns its ID. */
    public long insert(double x, double y) {
        return insert(new Point2(x, y));
    }

    /** Adds a new entity and returns its ID. */
    public long insert(Point2 position) {
        return insertAll(List.of(position))[0];
    }

    /** Adds many entities in one call and returns their IDs, in the same order as the positions. */
    public long[] insertAll(List<Point2> positions) {
        long[] entityIds = new long[positions.size()];
        List<Entity2> entities = new ArrayList<>(positions.size());
        for (int i = 0; i < entityIds.length; i++) {
            entityIds[i] = space.nextEntityId();
            entities.add(new Entity2(entityIds[i], positions.get(i)));
        }
        node.insertAll(id, entities);
        return entityIds;
    }

    /** Returns the position of an entity, or empty if the ID is not in the partition. */
    public Optional<Point2> get(long entityId) {
        List<Entity2> found = node.getAll(id, new long[] {entityId});
        return found.stream().findFirst().map(Entity2::position);
    }

    /**
     * Returns the entities with the given IDs, in the same order.
     * IDs that are not in the partition are skipped.
     */
    public List<Entity2> getAll(long[] entityIds) {
        return node.getAll(id, entityIds);
    }

    /** Moves an entity. Fails with NoSuchElementException if the ID is not in the partition. */
    public void update(long entityId, Point2 position) {
        updateAll(List.of(new Entity2(entityId, position)));
    }

    /**
     * Moves many entities in one call. Fails with NoSuchElementException if an ID is not
     * in the partition; in this case no entity moves.
     */
    public void updateAll(List<Entity2> entities) {
        node.updateAll(id, entities);
    }

    @Override
    public void remove(long entityId) {
        removeAll(new long[] {entityId});
    }

    @Override
    public void removeAll(long[] entityIds) {
        node.removeAll(id, entityIds);
    }

    @Override
    public int size() {
        return node.size(id);
    }
}
