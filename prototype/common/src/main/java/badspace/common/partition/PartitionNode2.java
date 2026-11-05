package badspace.common.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import java.util.List;
import java.util.Optional;

/**
 * Contract of a node that hosts partitions of a 2-dimensional space.
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
 * The snapshot of tick n is the state confirmed by the last
 * {@linkplain #commit(WriteToken, List) commit} of tick n, or the state of the tick before if
 * tick n has no commit. It is published
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
 * writer: after them the commits fail with IllegalStateException. The next move of
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
 * {@link WriteToken} and leaves the ownership with {@link #release(WriteToken)}. The writes of
 * the owner reach the node only with its commits, so between the commits the node has no
 * writes to discard: a change of the generation keeps the commits, and the writes that an old
 * owner did not commit never reach the partition. The node does not check who calls these
 * operations: the software decides who owns a partition. The commits, the rollbacks and the
 * removals take the token of the owner, and the partition
 * checks it in the same step as the operation: with a token that is not current they fail
 * with {@link OwnershipChangedException} and the partition does not change, so a writer that
 * lost the ownership cannot change the partition even if it does not know it yet. The moves
 * of the tick and the reads take no token.
 * <p>
 * Partition IDs are never used again, so the node cannot tell an unknown partition from
 * a destroyed one: the operations of the partition fail with {@link UnknownPartitionException},
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
public interface PartitionNode2 {

    /**
     * Creates an empty partition that uses the linear scan as index, like
     * {@link #createPartition(PartitionId, PartitionName, IndexConfig, int)}.
     *
     * @param partitionId the ID of the new partition
     * @param name the name of the new partition
     * @throws IllegalArgumentException if the ID or the name is already in use on this node
     */
    default void createPartition(PartitionId partitionId, PartitionName name) {
        createPartition(partitionId, name, IndexConfig.linearScan());
    }

    /**
     * Creates an empty partition with the default number of retained ticks, like
     * {@link #createPartition(PartitionId, PartitionName, IndexConfig, int)}.
     *
     * @param partitionId the ID of the new partition
     * @param name the name of the new partition
     * @param index the index of the partition
     * @throws IllegalArgumentException if the ID or the name is already in use on this node
     */
    default void createPartition(PartitionId partitionId, PartitionName name, IndexConfig index) {
        createPartition(partitionId, name, index, RetentionWindow.DEFAULT_RETAINED_TICKS);
    }

    /**
     * Creates an empty partition at the tick of the node: this is its
     * {@linkplain #creationTick creation tick}. If the creation fails, no partition is created.
     *
     * @param partitionId the ID of the new partition, which identifies it in all the other
     *     operations
     * @param name the name of the new partition; the node uses it only for the saves
     * @param index the index of the partition
     * @param retainedTicks the number of ticks whose snapshots readers can acquire
     * @throws IllegalArgumentException if the ID or the name is already in use on this node,
     *     also by a removed partition that is not destroyed yet, or if retainedTicks is less
     *     than 1
     */
    void createPartition(PartitionId partitionId, PartitionName name, IndexConfig index, int retainedTicks);

    /**
     * Creates a partition with the content of a save: the same entity IDs, positions and
     * application metadata. Like a new partition, it has no owner. The node reads the save and
     * rebuilds the index by inserting the entities while the moves of the tick go on, and then
     * creates the partition at its tick: this is the creation tick, which can be later than the
     * tick of the call. The load commits the content at the creation tick, so readers acquire
     * it after the next move of the tick, with no commit of the writer; a commit of the writer
     * in the same tick replaces the one of the load. If the load fails, no partition is created.
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
     * Moves the tick of the node forward, and then the tick of each of its partitions, also of
     * the ones marked as removed. The partitions move one after the other, not all at the same
     * moment, in an order that is not defined: the ones with a commit that is running move
     * after the others, so a partition waits only for its own commit. When the call returns,
     * all the partitions are at the new tick. The same tick again does nothing, and ticks can
     * be skipped.
     *
     * @param tick the new tick of the node
     * @throws IllegalArgumentException if the tick is less than the tick of the node; in this
     *     case nothing changes
     */
    void setTick(long tick);

    /** Returns the tick of the node: 0 for a new node. Any thread can call it. */
    long tick();

    /**
     * Returns the tick of a partition. While the node moves its partitions to a new tick, it can
     * be less than the {@linkplain #tick() tick of the node}. Any thread can call it, also after
     * the removal of the partition, until it is destroyed.
     *
     * @param partitionId the ID of the partition
     * @return the tick of the partition
     * @throws UnknownPartitionException if the partition is unknown
     */
    long tick(PartitionId partitionId);

    /**
     * Returns the tick at which the partition was created: the tick of the node at that moment.
     *
     * @param partitionId the ID of the partition
     * @return the creation tick of the partition
     * @throws UnknownPartitionException if the partition is unknown
     */
    long creationTick(PartitionId partitionId);

    /**
     * Removes a partition whose last commit has no entities: the removal becomes effective at
     * the next move of the tick. If the call fails, the partition does not change.
     *
     * @param token the token of the owner of the partition
     * @throws UnknownPartitionException if the partition of the token is unknown
     * @throws IllegalStateException if the last commit has entities or the removal was already
     *     requested
     * @throws OwnershipChangedException if the token is not the current one
     */
    void removePartition(WriteToken token);

    /**
     * Removes a partition together with its entities: the removal becomes effective at the
     * next move of the tick. If the call fails, the partition does not change.
     *
     * @param token the token of the owner of the partition
     * @throws UnknownPartitionException if the partition of the token is unknown
     * @throws IllegalStateException if the removal was already requested
     * @throws OwnershipChangedException if the token is not the current one
     */
    void dropPartition(WriteToken token);

    /**
     * Returns the state of the ownership of a partition. It is only a read: the node tells
     * nobody about the changes. Any thread can call it, also after the removal of the
     * partition, until it is destroyed.
     *
     * @param partitionId the ID of the partition
     * @return the generation of the ownership and whether the partition has an owner
     * @throws UnknownPartitionException if the partition is unknown
     */
    Ownership ownership(PartitionId partitionId);

    /**
     * Returns the health of a partition: healthy, or in need of a recovery after a failure
     * whose restore did not work. Then the partition refuses the commits with
     * IllegalStateException, while its snapshots stay readable; a {@linkplain #rollback
     * rollback} of the owner, or a claim or a release of the ownership, tries the restore
     * again. Any thread can call it, also after the removal of the partition, until it is
     * destroyed.
     *
     * @param partitionId the ID of the partition
     * @return the health of the partition, with the report of the failure if it needs a recovery
     * @throws UnknownPartitionException if the partition is unknown
     */
    PartitionHealth health(PartitionId partitionId);

    /**
     * Makes the caller the owner of a partition if the generation of its ownership is still
     * the expected one: the generation grows by one, and the partition stays at its last
     * commit. With the generation of a free partition it takes the partition; with the
     * generation of the token of the owner it passes the ownership to a new token, or takes it
     * away from the owner. It also tries the recovery of a partition that {@linkplain #health
     * needs one}. Any thread can call it: it waits for a commit or a rollback that is running.
     *
     * @param partitionId the ID of the partition
     * @param expectedGeneration the generation that the ownership must have
     * @return the token of the new generation, or empty if the generation is not the expected
     *     one; in this case nothing changes
     * @throws IllegalArgumentException if expectedGeneration is negative
     * @throws UnknownPartitionException if the partition is unknown
     * @throws IllegalStateException if the removal of the partition was requested
     */
    Optional<WriteToken> claim(PartitionId partitionId, long expectedGeneration);

    /**
     * Leaves the ownership of a partition if the token is the current one: the generation
     * grows by one and the partition has no owner; it stays at its last commit, and the node
     * tries the recovery of a partition that {@linkplain #health needs one}.
     * Otherwise it does nothing: also if the ownership has already changed, after the removal
     * of the partition was requested, and if the partition is unknown or destroyed. Any thread
     * can call it.
     *
     * @param token the token of the owner
     */
    void release(WriteToken token);

    /**
     * Applies a batch of writes to a partition and confirms it in the current tick of the
     * partition, in one step: the snapshot of the tick will have the writes, and becomes
     * acquirable when the tick of the partition moves forward. The changes apply to the last
     * commit of the partition, also if it waits for the move of the tick, not to the snapshot
     * that the writer read; each change says what to do with the entity in that state or not
     * (see {@link EntityWrite2}), and the changes of different entities are independent. The
     * IDs come from the entity ID generator of the API: the node trusts them and cannot check
     * where they come from.
     * <p>
     * Each commit confirms the full state, including the effects of earlier commits unless
     * the batch changes or removes them. Only the last commit of a tick defines its snapshot;
     * readers cannot acquire the intermediate states. A commit with no changes is valid. The
     * call does not move the tick, wait for the next tick or stop further commits in the same
     * tick: the writer decides whether to wait before writing again. A rejected batch changes
     * nothing.
     *
     * @param token the token of the owner of the partition
     * @param changes the final changes of the entities, at most one for each entity
     * @throws IllegalArgumentException if a position is outside the {@linkplain #limits limits}
     *     of the partition, or if an entity has more than one change
     * @throws OwnershipChangedException if the token is not the current one
     */
    void commit(WriteToken token, List<EntityWrite2> changes);

    /**
     * Brings the state of a partition back to the last commit, also if it waits for the move of
     * the tick, and the commits stay confirmed. The writes of the owner reach the node only with
     * its commits, so between the commits there is nothing to discard: the rollback is the
     * recovery of a partition that {@linkplain #health needs one}, and when the restore works
     * the partition is healthy again. On a healthy partition it does nothing.
     *
     * @param token the token of the owner of the partition
     * @throws OwnershipChangedException if the token is not the current one
     * @throws IllegalStateException if the removal of the partition was requested
     * @throws PartitionFailureException if the restore fails: then the partition needs a
     *     recovery, and the report has the outcome {@link FailureOutcome#RECOVERY_REQUIRED};
     *     an {@link Error} goes to the caller as it is
     */
    void rollback(WriteToken token);

    /**
     * Returns the tick of the last commit of a partition. The creation of the partition, and
     * its load from a save, count as a commit at the creation tick, so a partition always has
     * a last commit. Only the commits of the owner change the value, and the token makes sure
     * that the caller is the owner: after a claim the value is stable for the new owner, which
     * compares it with the current tick to know whether the tick already has a commit, for
     * example when it takes the partition from a writer that stopped. A move of the tick does
     * not change the value, and it can be read also when the partition needs a recovery.
     *
     * @param token the token of the owner of the partition
     * @return the tick of the last commit
     * @throws OwnershipChangedException if the token is not the current one
     * @throws IllegalStateException if the removal of the partition was requested
     */
    long lastCommitTick(WriteToken token);

    /**
     * Acquires the snapshot of a tick of a partition, which must be in the retention window.
     * Checking the window and acquiring the snapshot is a single step, so a move of the tick
     * at the same time cannot make it go away.
     *
     * @param partitionId the ID of the partition
     * @param tick the tick of the snapshot
     * @param options the timeout of the acquisition
     * @return the acquisition, for the reads and the release
     * @throws IllegalArgumentException if tick is negative
     * @throws UnknownPartitionException if the partition is unknown
     * @throws SnapshotNotAvailableException with the reason, if the snapshot is not published
     *     yet, is older than the window, is before the creation of the partition or is at its
     *     removal tick or after it
     */
    Acquisition acquire(PartitionId partitionId, long tick, AcquireOptions options);

    /**
     * Acquires the snapshot of the tick before the current tick of a partition, like
     * {@link #acquire}.
     *
     * @param partitionId the ID of the partition
     * @param options the timeout of the acquisition
     * @return the acquisition, for the reads and the release
     * @throws UnknownPartitionException if the partition is unknown
     * @throws SnapshotNotAvailableException if the tick of the partition has not moved since
     *     its creation, so no snapshot is published yet, or if the partition did not exist at
     *     that tick any more
     */
    Acquisition acquireLatest(PartitionId partitionId, AcquireOptions options);

    /**
     * Releases an acquisition: after this, its reads fail. Releasing it again, after its
     * timeout or after the removal of the partition does nothing. The other acquisitions do
     * not change.
     *
     * @param acquisition the acquisition to release
     * @throws IllegalArgumentException if the partition never made the acquisition
     */
    void release(Acquisition acquisition);

    /**
     * Returns the number of entities in the acquired snapshot.
     *
     * @param acquisition the acquisition of the snapshot
     * @return the number of entities
     * @throws IllegalStateException if the acquisition was released or is expired
     * @throws IllegalArgumentException if the partition never made the acquisition
     */
    int size(Acquisition acquisition);

    /**
     * Returns the entities of the acquired snapshot with the given IDs, in the same order.
     * IDs that are not in the snapshot are skipped.
     *
     * @param acquisition the acquisition of the snapshot
     * @param entityIds the IDs of the entities
     * @return the entities found
     * @throws IllegalStateException if the acquisition was released or is expired
     * @throws IllegalArgumentException if the partition never made the acquisition
     */
    List<Entity2> getAll(Acquisition acquisition, long[] entityIds);

    /**
     * Returns the application metadata of the entities of the acquired snapshot with the given
     * IDs, in the same order. IDs that are not in the snapshot are skipped.
     *
     * @param acquisition the acquisition of the snapshot
     * @param entityIds the IDs of the entities
     * @return the metadata of the entities found, with an empty map for an entity without
     *     metadata
     * @throws IllegalStateException if the acquisition was released or is expired
     * @throws IllegalArgumentException if the partition never made the acquisition
     */
    List<ApplicationMetadata> getMetadataAll(Acquisition acquisition, long[] entityIds);

    /**
     * Returns the entities of the acquired snapshot that are inside the region or on its
     * border.
     *
     * @param acquisition the acquisition of the snapshot
     * @param region the region of the query
     * @return the entities found, in no defined order
     * @throws IllegalStateException if the acquisition was released or is expired
     * @throws IllegalArgumentException if the partition never made the acquisition
     */
    List<Entity2> findInRegion(Acquisition acquisition, Region2 region);

    /**
     * Returns the entities of the acquired snapshot nearest to a point.
     *
     * @param acquisition the acquisition of the snapshot
     * @param point the point of the query
     * @param count the largest number of entities to return
     * @return at most count entities, sorted by distance from the point and then by ID
     * @throws IllegalStateException if the acquisition was released or is expired
     * @throws IllegalArgumentException if the partition never made the acquisition, or if
     *     count is negative
     */
    List<Entity2> findNearest(Acquisition acquisition, Point2 point, int count);

    /**
     * Returns the coordinates that the entities of a partition can have. The index of the
     * partition sets them: they are the same as {@link IndexConfig#limits()} of the index
     * chosen at creation.
     *
     * @param partitionId the ID of the partition
     * @return the limits of the coordinates
     * @throws UnknownPartitionException if the partition is unknown
     * @throws IllegalStateException if the removal of the partition was requested
     */
    CoordinateLimits limits(PartitionId partitionId);

    /**
     * Saves the snapshot of a tick of a partition to the save destination of the node. The
     * call is synchronous: it returns when the save is complete. The node acquires the
     * snapshot like {@link #acquire}, with the default timeout, copies it and releases it, then
     * writes the copy: the timeout does not limit the save, and the writer and the readers of
     * the partition continue meanwhile. A save of a tick already saved replaces the save with
     * the same content. Any thread can call it, also at the same time as other saves. A save of
     * a snapshot that another thread is saving does not copy it again: it releases its
     * acquisition, waits for that save and returns the same reference.
     *
     * @param partitionId the ID of the partition
     * @param tick the tick of the snapshot
     * @return the reference to the save, with the name of the partition and the tick
     * @throws IllegalArgumentException if tick is negative
     * @throws UnknownPartitionException if the partition is unknown
     * @throws SnapshotNotAvailableException like {@link #acquire}, if the snapshot is not
     *     available; then nothing is written
     * @throws IllegalStateException if the node has no save destination
     * @throws java.io.UncheckedIOException if the save cannot be written, or if the save of
     *     another thread that it waited for failed; the saves already there do not change
     */
    SavedPartition save(PartitionId partitionId, long tick);

    /**
     * Saves the snapshot of the tick before the current tick of a partition, like
     * {@link #save}.
     *
     * @param partitionId the ID of the partition
     * @return the reference to the save, with the name of the partition and the tick
     * @throws UnknownPartitionException if the partition is unknown
     * @throws SnapshotNotAvailableException like {@link #acquireLatest}, if no snapshot is
     *     available; then nothing is written
     * @throws IllegalStateException if the node has no save destination
     * @throws java.io.UncheckedIOException like {@link #save}
     */
    SavedPartition saveLatest(PartitionId partitionId);

    /**
     * Returns the saves in the save destination of the node. The saves do not need a partition
     * on the node: they stay after the destruction of their partition and after the end of the
     * run that wrote them. The node has no rule on which saves to keep: the software chooses.
     * Any thread can call it, also during saves: a save in progress is in the list only if it
     * is complete.
     *
     * @return the saves, sorted by name and then by tick
     * @throws IllegalStateException if the node has no save destination
     * @throws java.io.UncheckedIOException if the destination cannot be read
     */
    List<SavedPartition> saves();

    /**
     * Returns the saves of a partition name, like {@link #saves()}.
     *
     * @param name the name of the partition
     * @return the saves of the name, sorted by tick; empty if the name has no saves
     * @throws IllegalStateException if the node has no save destination
     * @throws java.io.UncheckedIOException if the destination cannot be read
     */
    List<SavedPartition> saves(PartitionName name);

    /**
     * Deletes saves from the save destination of the node, in the order of the list. A save
     * that is not there is skipped, so deleting the same save twice does nothing. A save of
     * the same tick in progress at the same time is not stopped, and its file can come back.
     * Any thread can call it.
     *
     * @param saves the saves to delete
     * @throws IllegalStateException if the node has no save destination
     * @throws java.io.UncheckedIOException if a save cannot be deleted; the saves before it in
     *     the list are already deleted
     */
    void deleteSaves(List<SavedPartition> saves);
}
