package badspace.common.partition;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import java.util.List;

/**
 * Contract of a node that hosts partitions of a 3-dimensional space.
 * A node can be in the same process as the API or in a remote process.
 * A node serves a single space. The API generates the partition IDs, which are never
 * used again: the node trusts them.
 * Write operations work on many entities in one call, to reduce the number of
 * remote calls. Each write is all or nothing: if it fails, the partition does not change.
 * <p>
 * The reads of a partition by ID or by position see the state of its writer,
 * with the writes not yet committed, and only the writer of the partition can
 * call them. The other threads read the snapshots of the ticks: they
 * {@linkplain #acquire acquire} a snapshot, read it through the
 * {@link Acquisition} and then {@linkplain #release release} it.
 * <p>
 * The snapshot of tick n is the state confirmed by the last {@linkplain #commit commit}
 * of tick n, or the state of the tick before if tick n has no commit. It is published
 * when the tick of the partition moves past n: the snapshot of the current tick is never
 * acquirable. With the tick T of the partition, its creation tick C and k retained ticks,
 * readers can acquire the snapshots of the ticks from max(T - k, C) to T - 1 (the
 * retention window). A snapshot that leaves the window stays readable through the
 * acquisitions made before, until they are released or expire. Every acquisition has a
 * timeout ({@link AcquireOptions}): after it, the acquisition behaves like a released one,
 * and the node removes it without waiting for the reader. The acquisitions, the reads
 * through them and the release are thread-safe.
 * <p>
 * The removal of a partition has two steps. {@link #removePartition} and
 * {@link #dropPartition} mark the partition as removed: after the mark, the writes, the
 * commits, the reads of the writer and the new acquisitions fail with
 * IllegalStateException, but the acquisitions made before stay readable until they are
 * released or expire. When nobody uses its snapshots any more, the node removes the
 * partition. Until then its ID stays in use on the node.
 * <p>
 * Partition IDs are never used again, so the node cannot tell an unknown partition from
 * a removed one in an acquisition: the release does nothing and the reads fail with
 * IllegalStateException, like for a released acquisition.
 * <p>
 * The node has a logical time, the tick: a counter that only grows, which the software
 * moves forward with {@link #setTick}. A new node is at tick 0. Each partition has its
 * own tick too: the node moves its partitions to a new tick one after the other, so for
 * a short time the partitions of a node can be at different ticks. A partition is created
 * at the tick of the node. The writer and the thread that moves the tick can work at the
 * same time: on each partition the moves of the tick are ordered with the writes and the
 * commits, so a commit is in the snapshot of the tick that ends or in the next one.
 */
public interface PartitionNode3 {

    /**
     * Creates an empty partition that uses the linear scan as index, at the tick of the node.
     * Fails if the ID is already in use on this node.
     */
    default void createPartition(PartitionId partitionId) {
        createPartition(partitionId, IndexConfig.linearScan());
    }

    /**
     * Creates an empty partition with the given index and the default number of retained ticks,
     * at the tick of the node. Fails if the ID is already in use on this node.
     */
    default void createPartition(PartitionId partitionId, IndexConfig index) {
        createPartition(partitionId, index, RetentionWindow.DEFAULT_RETAINED_TICKS);
    }

    /**
     * Creates an empty partition with the given index, at the tick of the node: this is its
     * {@linkplain #creationTick creation tick}. Readers can acquire the snapshots of the last
     * retainedTicks ticks. Fails with IllegalArgumentException if the ID is already in use on
     * this node or if retainedTicks is less than 1; in these cases no partition is created.
     */
    void createPartition(PartitionId partitionId, IndexConfig index, int retainedTicks);

    /**
     * Moves the tick of the node forward to the given tick, and then the tick of each of its
     * partitions, also of the ones marked as removed. The partitions move one after the other,
     * not all at the same moment. The same tick again does nothing, and ticks can be skipped.
     * Fails with IllegalArgumentException if the tick is less than the tick of the node; in
     * this case nothing changes.
     */
    void setTick(long tick);

    /** Returns the tick of the node: 0 for a new node. Any thread can call it. */
    long tick();

    /**
     * Returns the tick of a partition. While the node moves its partitions to a new tick, it can
     * be less than the {@linkplain #tick() tick of the node}. Any thread can call it.
     */
    long tick(PartitionId partitionId);

    /** Returns the tick at which the partition was created: the tick of the node at that moment. */
    long creationTick(PartitionId partitionId);

    /**
     * Removes an empty partition: marks it as removed, and the node removes it when its
     * snapshots are no longer used. Fails with IllegalArgumentException if the ID is unknown,
     * and with IllegalStateException if the partition still has entities or is already
     * marked as removed.
     */
    void removePartition(PartitionId partitionId);

    /**
     * Removes a partition together with its entities: marks it as removed, and the node
     * removes it when its snapshots are no longer used. Fails with IllegalArgumentException
     * if the ID is unknown, and with IllegalStateException if the partition is already
     * marked as removed.
     */
    void dropPartition(PartitionId partitionId);

    /**
     * Adds new entities to a partition. The API generates the entity IDs, which must
     * not be used anywhere else. Fails with IllegalArgumentException if an ID is
     * already in the partition or appears twice in the list, or if a position is
     * outside the {@linkplain #limits limits} of the partition.
     */
    void insertAll(PartitionId partitionId, List<Entity3> entities);

    /**
     * Returns the entities with the given IDs, in the same order.
     * IDs that are not in the partition are skipped.
     */
    List<Entity3> getAll(PartitionId partitionId, long[] entityIds);

    /**
     * Sets the position of existing entities. Fails with NoSuchElementException
     * if an ID is not in the partition, and with IllegalArgumentException if a
     * position is outside the {@linkplain #limits limits} of the partition.
     * If an ID appears more than once, the last position wins.
     */
    void updateAll(PartitionId partitionId, List<Entity3> entities);

    /**
     * Removes entities from a partition. Fails with NoSuchElementException if an ID
     * is not in the partition, and with IllegalArgumentException if an ID appears twice.
     */
    void removeAll(PartitionId partitionId, long[] entityIds);

    /**
     * Returns the entities of a partition that are inside the region or on its border.
     * The order of the result is not defined.
     */
    List<Entity3> findInRegion(PartitionId partitionId, Region3 region);

    /**
     * Returns the entities of a partition that are nearest to a point, at most count of them.
     * The result is sorted by distance from the point; entities at the same distance are
     * sorted by ID. Fails with IllegalArgumentException if count is negative.
     */
    List<Entity3> findNearest(PartitionId partitionId, Point3 point, int count);

    /**
     * Confirms the writes of a partition in its current tick: the snapshot of the tick will
     * have them. A later commit in the same tick replaces it, and the writes after the last
     * commit go to the next commit. A commit with no writes is valid. The snapshot becomes
     * acquirable when the tick of the partition moves forward.
     */
    void commit(PartitionId partitionId);

    /**
     * Acquires the snapshot of the given tick of a partition: it must be in the retention
     * window. Checking the window and acquiring the snapshot is a single step, so a move of
     * the tick at the same time cannot make it go away. Fails with IllegalArgumentException
     * if tick is negative, and with SnapshotNotAvailableException, with the reason, if the
     * snapshot is not published yet, is older than the window, or is before the creation of
     * the partition. The options give the timeout of the acquisition.
     */
    Acquisition acquire(PartitionId partitionId, long tick, AcquireOptions options);

    /**
     * Acquires the snapshot of the tick before the current tick of a partition, like
     * {@link #acquire}. Fails with SnapshotNotAvailableException if the tick of the partition
     * has not moved since its creation, so no snapshot is published yet.
     */
    Acquisition acquireLatest(PartitionId partitionId, AcquireOptions options);

    /**
     * Releases an acquisition: after this, its reads fail. Releasing it again, or
     * after its timeout, does nothing; also after the removal of the partition. Fails
     * with IllegalArgumentException if the partition never made the acquisition; the
     * other acquisitions do not change.
     */
    void release(Acquisition acquisition);

    /**
     * Returns the number of entities in the acquired snapshot. Fails with
     * IllegalStateException if the acquisition was released or is expired, and with
     * IllegalArgumentException if the partition never made it.
     */
    int size(Acquisition acquisition);

    /**
     * Returns the entities of the acquired snapshot with the given IDs, in the same order.
     * IDs that are not in the snapshot are skipped. Fails like {@link #size(Acquisition)}.
     */
    List<Entity3> getAll(Acquisition acquisition, long[] entityIds);

    /**
     * Returns the entities of the acquired snapshot that are inside the region or on its
     * border. The order of the result is not defined. Fails like {@link #size(Acquisition)}.
     */
    List<Entity3> findInRegion(Acquisition acquisition, Region3 region);

    /**
     * Returns the entities of the acquired snapshot nearest to a point, at most count of
     * them, sorted by distance from the point and then by ID. Fails like
     * {@link #size(Acquisition)}, and with IllegalArgumentException if count is negative.
     */
    List<Entity3> findNearest(Acquisition acquisition, Point3 point, int count);

    /** Returns the number of entities in a partition. */
    int size(PartitionId partitionId);

    /**
     * Returns the coordinates that the entities of a partition can have. The
     * index of the partition sets them: they are the same as
     * {@link IndexConfig#limits()} of the index chosen at creation.
     */
    CoordinateLimits limits(PartitionId partitionId);
}
