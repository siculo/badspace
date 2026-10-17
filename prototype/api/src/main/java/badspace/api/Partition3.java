package badspace.api;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.WriteToken;
import badspace.common.geometry.Point3;
import java.util.ArrayList;
import java.util.List;

/**
 * Proxy of a 3D partition that lives on a node. It has a single writer and is not thread-safe,
 * except {@link #tick()} and the acquisitions. The proxy has only writes: everyone, the writer
 * too, reads by {@linkplain #acquireLatest() acquiring} the snapshot of a completed tick. The
 * writer does not see its own writes before the tick that has them moves forward: if it must
 * remember them, it keeps its own state.
 * Single-entity operations are batches of one entity: to write many entities,
 * the batch operations make fewer calls to the node.
 * After the space removes the partition, the operations of the writer fail with
 * IllegalStateException; the acquisitions, {@link #tick()} and {@link #creationTick()} still
 * go to the node, which gives the snapshots of the ticks before the removal while they are
 * in the retention window.
 */
public final class Partition3 implements Partition {

    private final Space3 space;
    private final PartitionNode3 node;
    private final PartitionId partitionId;
    // The token of the ownership, which the space takes when it creates the partition: the
    // writes, the commits and the removal of the proxy carry it.
    private final WriteToken token;
    private final PartitionConfig config;
    // Written only by the writer, when the space removes the partition, and read only by
    // the writer: it stops the operations of the writer without a call to the node.
    private boolean removed;

    Partition3(Space3 space, PartitionNode3 node, WriteToken token, PartitionConfig config) {
        this.space = space;
        this.node = node;
        this.partitionId = token.partitionId();
        this.token = token;
        this.config = config;
    }

    Space3 space() {
        return space;
    }

    /** Removes the partition from its node, following its removal policy. */
    void removeFromNode() {
        checkNotRemoved();
        switch (config.removalPolicy()) {
            case REQUIRE_EMPTY -> node.removePartition(token);
            case DISCARD_ENTITIES -> node.dropPartition(token);
        }
        removed = true;
    }

    private void checkNotRemoved() {
        if (removed) {
            throw new IllegalStateException("Partition was removed: " + partitionId);
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
        node.insertAll(token, entities);
        return entityIds;
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
        node.updateAll(token, entities);
    }

    @Override
    public void remove(long entityId) {
        removeAll(new long[] {entityId});
    }

    @Override
    public void removeAll(long[] entityIds) {
        checkNotRemoved();
        node.removeAll(token, entityIds);
    }

    @Override
    public void commit() {
        checkNotRemoved();
        node.commit(token);
    }

    /** Acquires the snapshot of a tick with the default timeout, like {@link #acquire(long, AcquireOptions)}. */
    public AcquiredSnapshot3 acquire(long tick) {
        return acquire(tick, AcquireOptions.defaultTimeout());
    }

    /**
     * Acquires the snapshot of the given tick: with the tick T of the partition, the tick must
     * be from T - k to T - 1, and not before the creation of the partition. Choosing the snapshot
     * and acquiring it is a single step, so a move of the tick at the same time cannot make it
     * go away. Fails with IllegalArgumentException if tick is negative, and with
     * SnapshotNotAvailableException, with the reason, if the snapshot is not published yet, is
     * older than the window, or is before the creation of the partition. The options give the
     * timeout of the acquisition. Any thread can call it.
     */
    public AcquiredSnapshot3 acquire(long tick, AcquireOptions options) {
        return new AcquiredSnapshot3(node, node.acquire(partitionId, tick, options));
    }

    /** Acquires the latest snapshot with the default timeout, like {@link #acquireLatest(AcquireOptions)}. */
    public AcquiredSnapshot3 acquireLatest() {
        return acquireLatest(AcquireOptions.defaultTimeout());
    }

    /**
     * Acquires the snapshot of the tick before the current tick of the partition, like
     * {@link #acquire(long, AcquireOptions)}. Fails with SnapshotNotAvailableException if the
     * tick has not moved since the creation of the partition. Any thread can call it.
     */
    public AcquiredSnapshot3 acquireLatest(AcquireOptions options) {
        return new AcquiredSnapshot3(node, node.acquireLatest(partitionId, options));
    }

    @Override
    public long tick() {
        return node.tick(partitionId);
    }

    @Override
    public long creationTick() {
        return node.creationTick(partitionId);
    }

    @Override
    public PartitionConfig config() {
        return config;
    }

    @Override
    public CoordinateLimits limits() {
        checkNotRemoved();
        return node.limits(partitionId);
    }
}
