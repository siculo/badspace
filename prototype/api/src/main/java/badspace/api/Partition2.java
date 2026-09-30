package badspace.api;

import badspace.common.Entity2;
import badspace.common.PartitionId;
import badspace.common.PartitionNode2;
import badspace.common.Point2;
import badspace.common.Region2;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proxy of a 2D partition that lives on a node. It has a single writer and is not thread-safe.
 * Single-entity operations are batches of one entity: to write many entities,
 * the batch operations make fewer calls to the node.
 * After the space removes the partition, every operation fails with IllegalStateException.
 */
public final class Partition2 implements Partition {

    private final Space2 space;
    private final PartitionNode2 node;
    private final PartitionId id;
    private final RemovalPolicy removalPolicy;
    private boolean removed;

    Partition2(Space2 space, PartitionNode2 node, PartitionId id, RemovalPolicy removalPolicy) {
        this.space = space;
        this.node = node;
        this.id = id;
        this.removalPolicy = removalPolicy;
    }

    Space2 space() {
        return space;
    }

    /** Removes the partition from its node, following its removal policy. */
    void removeFromNode() {
        checkNotRemoved();
        switch (removalPolicy) {
            case REQUIRE_EMPTY -> node.removePartition(id);
            case DISCARD_ENTITIES -> node.dropPartition(id);
        }
        removed = true;
    }

    private void checkNotRemoved() {
        if (removed) {
            throw new IllegalStateException("Partition was removed: " + id);
        }
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
        checkNotRemoved();
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
        checkNotRemoved();
        List<Entity2> found = node.getAll(id, new long[] {entityId});
        return found.stream().findFirst().map(Entity2::position);
    }

    /**
     * Returns the entities with the given IDs, in the same order.
     * IDs that are not in the partition are skipped.
     */
    public List<Entity2> getAll(long[] entityIds) {
        checkNotRemoved();
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
        checkNotRemoved();
        node.updateAll(id, entities);
    }

    /**
     * Returns the entities that are inside the region or on its border.
     * The order of the result is not defined.
     */
    public List<Entity2> findInRegion(Region2 region) {
        checkNotRemoved();
        return node.findInRegion(id, region);
    }

    /**
     * Returns the entities nearest to a point, at most count of them, sorted by distance
     * from the point and then by ID. Fails with IllegalArgumentException if count is negative.
     */
    public List<Entity2> findNearest(Point2 point, int count) {
        checkNotRemoved();
        return node.findNearest(id, point, count);
    }

    @Override
    public void remove(long entityId) {
        removeAll(new long[] {entityId});
    }

    @Override
    public void removeAll(long[] entityIds) {
        checkNotRemoved();
        node.removeAll(id, entityIds);
    }

    @Override
    public RemovalPolicy removalPolicy() {
        return removalPolicy;
    }

    @Override
    public int size() {
        checkNotRemoved();
        return node.size(id);
    }
}
