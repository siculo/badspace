package badspace.common.partition;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import java.util.List;
import java.util.Optional;

/**
 * Contract of a node that hosts partitions of a 3-dimensional space.
 * A node can be in the same process as the API or in a remote process.
 * A node serves a single space. The API generates the partition IDs, which are never
 * used again: the node trusts them.
 * Write operations work on many entities in one call, to reduce the number of
 * remote calls. Each write is all or nothing: if it fails, the partition does not change.
 * <p>
 * Partitions are read only through the snapshots of the ticks, also by their writer:
 * a reader {@linkplain #acquire acquires} a snapshot, reads it through the
 * {@link Acquisition} and then {@linkplain #release releases} it. Nobody reads the
 * state of the writer, so the writes not yet published are in no read.
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
 * {@link #removePartition} and {@link #dropPartition} are the last operation of the
 * writer: after them the writes and the commits fail with
 * IllegalStateException, and the writes not committed are discarded. The next move of
 * the tick makes the removal effective at the new tick, the removal tick R: the partition
 * exists for the ticks before R. Readers can still acquire the snapshots of the ticks
 * before R while they are in the retention window, and the acquisitions made before stay
 * readable until they are released or expire. When no tick of the partition is in the
 * window and nobody uses its snapshots, the node destroys the partition. Until then its
 * ID stays in use on the node.
 * <p>
 * A partition has at most one owner, its writer. The {@link Ownership} of a partition has a
 * generation that grows at each change; a new partition has no owner, at generation 0.
 * {@link #claim} is a compare-and-set on the generation and the only way to become the owner:
 * it takes a free partition, passes the ownership to another writer and takes it away from
 * the owner, and the caller chooses which one with the generation it gives. The owner gets a
 * {@link WriteToken} and leaves the ownership with {@link #release(WriteToken)}. At each change
 * of the generation the writes not committed are discarded, and the commits stay. The node
 * does not check who calls these operations: the software decides who owns a partition.
 * The writes, the commits and the removals take the token of the owner, and the partition
 * checks it in the same step as the operation: with a token that is not current they fail
 * with {@link OwnershipChangedException} and the partition does not change, so a writer that
 * lost the ownership cannot change the partition even if it does not know it yet. The moves
 * of the tick and the reads take no token.
 * <p>
 * Partition IDs are never used again, so the node cannot tell an unknown partition from
 * a destroyed one: the operations of the partition fail with IllegalArgumentException,
 * and in an acquisition the release does nothing and the reads fail with
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
     * Fails if the ID or the name is already in use on this node.
     */
    default void createPartition(PartitionId partitionId, PartitionName name) {
        createPartition(partitionId, name, IndexConfig.linearScan());
    }

    /**
     * Creates an empty partition with the given index and the default number of retained ticks,
     * at the tick of the node. Fails if the ID or the name is already in use on this node.
     */
    default void createPartition(PartitionId partitionId, PartitionName name, IndexConfig index) {
        createPartition(partitionId, name, index, RetentionWindow.DEFAULT_RETAINED_TICKS);
    }

    /**
     * Creates an empty partition with the given index, at the tick of the node: this is its
     * {@linkplain #creationTick creation tick}. Readers can acquire the snapshots of the last
     * retainedTicks ticks. The ID identifies the partition in all the other operations; the
     * node uses the name only for the saves. Fails with IllegalArgumentException if the ID or
     * the name is already in use on this node, also by a removed partition that is not
     * destroyed yet, or if retainedTicks is less than 1; in these cases no partition is created.
     */
    void createPartition(PartitionId partitionId, PartitionName name, IndexConfig index, int retainedTicks);

    /**
     * Creates a partition with the content of a save: the same entity IDs, positions and
     * application metadata. Like a new partition, it is created at the tick of the node and has
     * no owner. The content is confirmed at the creation tick, so readers acquire it after the
     * next move of the tick. The node rebuilds the index by inserting the entities; a move of
     * the tick of the node waits for it. If the load fails, no partition is created.
     *
     * @param partitionId the ID of the new partition
     * @param saved the save to load, in the save destination of the node; the partition takes
     *     its name
     * @param index the index of the partition; it does not come from the save
     * @param retainedTicks the number of retained ticks of the partition
     * @throws IllegalArgumentException if the ID or the name is already in use on this node,
     *     if retainedTicks is less than 1, or if a position of the save is outside the limits
     *     of the index
     * @throws IllegalStateException if the node has no save destination
     * @throws java.io.UncheckedIOException if the save is not there or cannot be read, for
     *     example because it is corrupted or has another number of dimensions
     */
    void loadPartition(PartitionId partitionId, SavedPartition saved, IndexConfig index, int retainedTicks);

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
     * be less than the {@linkplain #tick() tick of the node}. Any thread can call it, also after
     * the removal of the partition, until it is destroyed.
     */
    long tick(PartitionId partitionId);

    /** Returns the tick at which the partition was created: the tick of the node at that moment. */
    long creationTick(PartitionId partitionId);

    /**
     * Removes a partition whose last commit has no entities: the removal becomes effective at
     * the next move of the tick, and the writes not committed are discarded. Fails with
     * IllegalArgumentException if the partition of the token is unknown, and with
     * IllegalStateException if the last commit has entities or the removal was already requested; in these cases the
     * partition does not change.
     * Fails with OwnershipChangedException if the token is not the current one.
     */
    void removePartition(WriteToken token);

    /**
     * Removes a partition together with its entities: the removal becomes effective at the
     * next move of the tick, and the writes not committed are discarded. Fails with
     * IllegalArgumentException if the partition of the token is unknown, and with
     * IllegalStateException if the removal was already requested.
     * Fails with OwnershipChangedException if the token is not the current one.
     */
    void dropPartition(WriteToken token);

    /**
     * Returns the state of the ownership of a partition: its generation and whether it has
     * an owner. It is only a read: the node tells nobody about the changes. Any thread can
     * call it, also after the removal of the partition, until it is destroyed. Fails with
     * IllegalArgumentException if the ID is unknown.
     */
    Ownership ownership(PartitionId partitionId);

    /**
     * Makes the caller the owner of a partition if the generation of its ownership is still
     * expectedGeneration: the generation grows by one, the writes not committed are
     * discarded, and the result is the token of the new generation. Otherwise the result is
     * empty and nothing changes. With the generation of a free partition it takes the
     * partition; with the generation of the token of the owner it passes the ownership to a
     * new token, or takes it away from the owner. Fails with IllegalArgumentException if
     * expectedGeneration is negative or the ID is unknown, and with IllegalStateException
     * after the removal of the partition was requested. Any thread can call it: it waits for
     * a write or a commit that is running.
     */
    Optional<WriteToken> claim(PartitionId partitionId, long expectedGeneration);

    /**
     * Leaves the ownership of a partition if the token is the current one: the generation
     * grows by one, the partition has no owner, and the writes not committed are discarded.
     * Otherwise it does nothing: also if the ownership has already changed, after the removal
     * of the partition was requested, and if the partition is unknown or destroyed. Any thread
     * can call it.
     */
    void release(WriteToken token);

    /**
     * Adds new entities to a partition. The API generates the entity IDs, which must
     * not be used anywhere else. Fails with IllegalArgumentException if an ID is
     * already in the partition or appears twice in the list, or if a position is
     * outside the {@linkplain #limits limits} of the partition.
     * Fails with OwnershipChangedException if the token is not the current one.
     */
    void insertAll(WriteToken token, List<Entity3> entities);

    /**
     * Sets the position of existing entities. Fails with NoSuchElementException
     * if an ID is not in the partition, and with IllegalArgumentException if a
     * position is outside the {@linkplain #limits limits} of the partition.
     * If an ID appears more than once, the last position wins.
     * Fails with OwnershipChangedException if the token is not the current one.
     */
    void updateAll(WriteToken token, List<Entity3> entities);

    /**
     * Removes entities from a partition. Fails with NoSuchElementException if an ID
     * is not in the partition, and with IllegalArgumentException if an ID appears twice.
     * Fails with OwnershipChangedException if the token is not the current one.
     */
    void removeAll(WriteToken token, long[] entityIds);

    /**
     * Writes the application metadata of existing entities: each write sets or deletes one
     * key of one entity, in the order of the list, so if more writes change the same key the
     * last one wins. The metadata are confirmed by the same commit as the positions, and are
     * in the same snapshot. Removing an entity deletes its metadata. Fails with
     * NoSuchElementException if an ID is not in the partition.
     * Fails with OwnershipChangedException if the token is not the current one.
     */
    void updateMetadataAll(WriteToken token, List<MetadataWrite> writes);

    /**
     * Applies a conditional write: it moves entities and writes their application metadata
     * only if all its conditions are true on the state of the writer, with the writes not
     * committed yet. Checking the conditions and applying the write is a single step, so no
     * other write of the partition can come in between. Returns the false conditions, in
     * their order, or an empty list if the write was applied; a false condition is not an
     * error, and then the partition does not change. Fails with NoSuchElementException if an
     * ID of the write or of a condition is not in the partition, and with
     * IllegalArgumentException if a position is outside the {@linkplain #limits limits} of
     * the partition; in these cases the partition does not change.
     * Fails with OwnershipChangedException if the token is not the current one.
     */
    List<MetadataCondition> updateIf(WriteToken token, ConditionalWrite3 write);

    /**
     * Confirms the writes of a partition in its current tick: the snapshot of the tick will
     * have them.
     * Each commit confirms the full current state, including the effects of earlier commits
     * unless later writes change or remove them. Only the last commit of a tick defines its
     * snapshot; readers cannot acquire the intermediate states. Writes after the last commit
     * stay uncommitted and carry over to the next tick. A commit with no writes is valid.
     * This call does not advance the tick, wait for the next tick or stop further writes
     * in the same tick. The writer decides whether to wait before writing again.
     * The snapshot becomes acquirable when the tick of the partition moves forward.
     * Fails with OwnershipChangedException if the token is not the current one.
     */
    void commit(WriteToken token);

    /**
     * Acquires the snapshot of the given tick of a partition: it must be in the retention
     * window. Checking the window and acquiring the snapshot is a single step, so a move of
     * the tick at the same time cannot make it go away. Fails with IllegalArgumentException
     * if tick is negative, and with SnapshotNotAvailableException, with the reason, if the
     * snapshot is not published yet, is older than the window, is before the creation of the
     * partition or is at its removal tick or after it. The options give the timeout of the
     * acquisition.
     */
    Acquisition acquire(PartitionId partitionId, long tick, AcquireOptions options);

    /**
     * Acquires the snapshot of the tick before the current tick of a partition, like
     * {@link #acquire}. Fails with SnapshotNotAvailableException if the tick of the partition
     * has not moved since its creation, so no snapshot is published yet, or if the partition
     * did not exist at that tick any more.
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
     * Returns the application metadata of the entities of the acquired snapshot with the given
     * IDs, in the same order, with an empty map for an entity without metadata. IDs that are
     * not in the snapshot are skipped. Fails like {@link #size(Acquisition)}.
     */
    List<ApplicationMetadata> getMetadataAll(Acquisition acquisition, long[] entityIds);

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

    /**
     * Returns the coordinates that the entities of a partition can have. The
     * index of the partition sets them: they are the same as
     * {@link IndexConfig#limits()} of the index chosen at creation.
     */
    CoordinateLimits limits(PartitionId partitionId);

    /**
     * Saves the snapshot of the given tick of a partition to the save destination of the node
     * and returns the reference to the save, with the name of the partition. The call is synchronous: it returns when the save
     * is complete. The node acquires the snapshot like {@link #acquire}, with the default
     * timeout, copies it and releases it, then writes the copy: the timeout does not limit
     * the save, and the writer and the readers of the partition continue meanwhile. A save of
     * a tick already saved replaces the save with the same content. Fails like {@link #acquire}
     * if the snapshot is not available, and then writes nothing; fails with
     * IllegalStateException if the node has no save destination, and with
     * UncheckedIOException if the save cannot be written: the saves already there do not
     * change. Any thread can call it, also at the same time as other saves. A save of a
     * snapshot that another thread is saving does not copy it again: it releases its
     * acquisition, waits for that save and returns the same reference, or fails with
     * UncheckedIOException if that save fails.
     */
    SavedPartition save(PartitionId partitionId, long tick);

    /**
     * Saves the snapshot of the tick before the current tick of a partition, like
     * {@link #save}. Fails like {@link #acquireLatest} if no snapshot is available.
     */
    SavedPartition saveLatest(PartitionId partitionId);

    /**
     * Returns the saves in the save destination of the node, sorted by name and then by tick.
     * The saves do not need a partition on the node: they stay after the destruction of their
     * partition and after the end of the run that wrote them. The node has no rule on which
     * saves to keep: the software chooses. Fails with IllegalStateException if the node has no
     * save destination, and with UncheckedIOException if the destination cannot be read. Any
     * thread can call it, also during saves: a save in progress is in the list only if it is
     * complete.
     */
    List<SavedPartition> saves();

    /**
     * Returns the saves of a partition name, sorted by tick, like {@link #saves()}; empty if the
     * name has no saves.
     */
    List<SavedPartition> saves(PartitionName name);

    /**
     * Deletes the given saves from the save destination of the node, in the order of the list.
     * A save that is not there is skipped, so deleting the same save twice does nothing. A save
     * of the same tick in progress at the same time is not stopped, and its file can come back.
     * Fails with IllegalStateException if the node has no save destination, and with
     * UncheckedIOException if a save cannot be deleted: the saves before it in the list are
     * already deleted. Any thread can call it.
     */
    void deleteSaves(List<SavedPartition> saves);
}
