package badspace.api;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.PartitionVersion3;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proxy of a 3D partition that lives on a node. It has a single writer and is not thread-safe,
 * except {@link #lastVersion()}. The reads of the proxy see the writes not yet committed;
 * the other threads read the {@linkplain #lastVersion() version of the last commit}.
 * Single-entity operations are batches of one entity: to write many entities,
 * the batch operations make fewer calls to the node.
 * After the space removes the partition, every operation fails with IllegalStateException.
 */
public final class Partition3 implements Partition {

    private final Space3 space;
    private final PartitionNode3 node;
    private final PartitionId id;
    private final PartitionConfig config;
    // Volatile because lastVersion() can be called by any thread.
    private volatile boolean removed;

    Partition3(Space3 space, PartitionNode3 node, PartitionId id, PartitionConfig config) {
        this.space = space;
        this.node = node;
        this.id = id;
        this.config = config;
    }

    Space3 space() {
        return space;
    }

    /** Removes the partition from its node, following its removal policy. */
    void removeFromNode() {
        checkNotRemoved();
        switch (config.removalPolicy()) {
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
    public long insert(double x, double y, double z) {
        return insert(new Point3(x, y, z));
    }

    /** Adds a new entity and returns its ID. */
    public long insert(Point3 position) {
        return insertAll(List.of(position))[0];
    }

    /**
     * Adds many entities in one call and returns their IDs, in the same order as the positions.
     * Fails with IllegalArgumentException if a position is outside the {@linkplain #limits() limits};
     * in this case no entity is added.
     */
    public long[] insertAll(List<Point3> positions) {
        checkNotRemoved();
        long[] entityIds = new long[positions.size()];
        List<Entity3> entities = new ArrayList<>(positions.size());
        for (int i = 0; i < entityIds.length; i++) {
            entityIds[i] = space.nextEntityId();
            entities.add(new Entity3(entityIds[i], positions.get(i)));
        }
        node.insertAll(id, entities);
        return entityIds;
    }

    /** Returns the position of an entity, or empty if the ID is not in the partition. */
    public Optional<Point3> get(long entityId) {
        checkNotRemoved();
        List<Entity3> found = node.getAll(id, new long[] {entityId});
        return found.stream().findFirst().map(Entity3::position);
    }

    /**
     * Returns the entities with the given IDs, in the same order.
     * IDs that are not in the partition are skipped.
     */
    public List<Entity3> getAll(long[] entityIds) {
        checkNotRemoved();
        return node.getAll(id, entityIds);
    }

    /**
     * Moves an entity. Fails with NoSuchElementException if the ID is not in the partition,
     * and with IllegalArgumentException if the position is outside the {@linkplain #limits() limits}.
     */
    public void update(long entityId, Point3 position) {
        updateAll(List.of(new Entity3(entityId, position)));
    }

    /**
     * Moves many entities in one call. Fails with NoSuchElementException if an ID is not
     * in the partition, and with IllegalArgumentException if a position is outside the
     * {@linkplain #limits() limits}; in these cases no entity moves.
     */
    public void updateAll(List<Entity3> entities) {
        checkNotRemoved();
        node.updateAll(id, entities);
    }

    /**
     * Returns the entities that are inside the region or on its border.
     * The order of the result is not defined.
     */
    public List<Entity3> findInRegion(Region3 region) {
        checkNotRemoved();
        return node.findInRegion(id, region);
    }

    /**
     * Returns the entities nearest to a point, at most count of them, sorted by distance
     * from the point and then by ID. Fails with IllegalArgumentException if count is negative.
     */
    public List<Entity3> findNearest(Point3 point, int count) {
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
    public void commit(long n) {
        checkNotRemoved();
        node.commit(id, n);
    }

    /**
     * Returns the version of the last commit: the empty version at commit 0 if the partition
     * has no commit yet. The version never changes, and its reads see only that commit.
     * Unlike the other methods, any thread can call it.
     */
    public PartitionVersion3 lastVersion() {
        checkNotRemoved();
        return node.lastVersion(id);
    }

    @Override
    public long lastCommit() {
        checkNotRemoved();
        return node.lastCommit(id);
    }

    @Override
    public PartitionConfig config() {
        return config;
    }

    @Override
    public int size() {
        checkNotRemoved();
        return node.size(id);
    }

    @Override
    public CoordinateLimits limits() {
        checkNotRemoved();
        return node.limits(id);
    }
}
