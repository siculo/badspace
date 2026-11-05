package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.ConditionalWrite2;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.FailureOutcome;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataCondition;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.Ownership;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionComponent;
import badspace.common.partition.PartitionFailure;
import badspace.common.partition.PartitionFailureException;
import badspace.common.partition.PartitionHealth;
import badspace.common.partition.PartitionOperation;
import badspace.common.partition.RetentionWindow;
import badspace.service.index.IndexSnapshot2;
import badspace.service.index.SlotView2;
import badspace.service.index.SpatialIndex2;
import badspace.service.index.SpatialIndexes;
import badspace.service.partition.metadata.MetadataSnapshot;
import badspace.service.partition.metadata.MutableMetadata;
import badspace.service.partition.slots.CoordinateBufferPool;
import badspace.service.partition.slots.MutableSlots2;
import badspace.service.partition.slots.SlotSnapshot2;
import badspace.service.partition.snapshot.MonotonicClock;
import badspace.service.partition.snapshot.SnapshotAcquisitions2;
import badspace.service.partition.snapshot.SnapshotCatalog2;
import badspace.service.partition.snapshot.SnapshotLifetime2;
import badspace.service.partition.snapshot.StorageSnapshot2;
import badspace.service.persistence.PartitionCopy2;
import badspace.service.transaction.TransactionalComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A 2D partition on its node: it coordinates the writer, the moves of the tick and the
 * readers. It has a single writer and is not thread-safe, except {@link #setTick},
 * {@link #catalog()}, {@link #tick()}, {@link #creationTick()}, the ownership and the
 * methods of the acquisitions.
 * <p>
 * The partition keeps the protocols that involve more than one part, and gives the details
 * to its components:
 * <ul>
 *   <li>{@link MutableSlots2}: the entities of the writer, in primitive arrays, and the
 *       snapshots of the slots;</li>
 *   <li>the spatial index, which reads the slots through their view; the partition tells it
 *       each change, after the change of the slots;</li>
 *   <li>{@link MutableMetadata}: the application metadata of the entities, separate from
 *       the slots and from the index;</li>
 *   <li>{@link SnapshotAcquisitions2}: the acquisitions, the queries and the timeouts;</li>
 *   <li>{@link SnapshotLifetime2}: the end of the snapshots and the count of the live ones;</li>
 *   <li>{@link CoordinateBufferPool}: the buffers of the coordinates, which the commits take
 *       and the ended snapshots give back.</li>
 * </ul>
 * <p>
 * The slots, the index and the metadata are {@link TransactionalComponent}s: at each commit
 * the partition asks each of them for its snapshot, with the same commit sequence, and when
 * it discards the writes not committed it restores them to the last commit, in this order:
 * slots, index, metadata. A commit is all or nothing: the partition accepts the snapshots
 * only after all of them and the snapshot of the partition are ready. If a preparation
 * fails, it abandons the snapshots already prepared.
 * <p>
 * A failure after the checks of the input, in a write or in a commit, discards all the writes
 * not committed: the partition restores the structures to the last commit, and the last commit
 * and the snapshot that waits for the move of the tick stay as they were. The caller gets a
 * {@link PartitionFailureException} with the report of the failure, or the {@link Error} as it
 * is. If also the restore fails, the {@linkplain #health() health} of the partition says that
 * it needs a recovery: writes and commits are refused, the confirmed snapshots stay readable,
 * and a rollback of the owner, or a claim or a release of the ownership, tries the restore
 * again. Rollback, claim, release and the failures use the same restore.
 * <p>
 * Each write, commit and request of removal takes the generation of the owner and first
 * checks it, under the lock of the writer, together with all the input; then it applies
 * the write. So a failed write, or one with a generation that is not current, does not
 * change the partition. The positions of the entities must be in the limits of the index:
 * writes outside them fail, so the indices never see them. Nobody queries the state of the
 * writer: queries read the snapshots, through the acquisitions.
 * <p>
 * Writes are grouped in commits. Each commit makes a {@link StorageSnapshot2}: the snapshots
 * of the slots, of the index and of the metadata of the same commit. Each commit advances an
 * internal sequence for the copy-on-write of the index and for the pages of the slots: the
 * partition owns it, and gives the slots the commit of each change. This sequence is separate
 * from the logical tick: one tick can have many commits. The coordinates are copied only in
 * the pages changed after the commit of the buffer that the commit takes from the pool; the IDs
 * and the table from ID to slot only after insertions or removals, else the new snapshot shares
 * them with the one before. The metadata are a persistent map, which copies only the nodes
 * changed since the commit before. A commit without changes of the coordinates shares the buffer of
 * the snapshot before it. If the pool has no buffer large enough, the commit allocates one:
 * the writer never waits for a reader, only the memory grows.
 * <p>
 * The partition has a tick: the logical time of the partition, which the node moves forward.
 * The snapshot of the last commit of a tick is not published at the commit: the move of the
 * tick publishes it in the {@link SnapshotCatalog2}, as the snapshot of that tick and of the
 * ticks without a commit after it. A later commit in the same tick replaces the snapshot that
 * waits for the move, and gives back its buffer when it is accepted, because no reader ever
 * saw it; the next commit can take the buffer again. The new
 * partition has the empty snapshot as if it had a commit at its creation tick. A partition loaded
 * from a save has instead the snapshot of the loaded content (see {@link #load}). The catalog
 * holds the same snapshots, not copies, for the ticks of the retention window. Everyone, the
 * writer too, reads the snapshots of the {@linkplain #catalog() catalog}: the move of the tick
 * publishes it with a single write of a volatile field, so a reader sees either the catalog
 * before or the whole new catalog, never a new tick with an old window.
 * <p>
 * The thread that moves the tick is not the writer. A lock orders the moves of the tick and
 * the operations that change the partition (writes, commit, claim, release, request of
 * removal): a commit is either before a move, and then it is in the snapshot of the tick that
 * ends, or after it. The readers never take the lock, and the writer waits only for a move of
 * the tick, a claim or a release, which are short, never for a reader. The release and the
 * move of the tick remove the expired acquisitions, and the node calls {@link #removeExpired}
 * after each acquisition and commit, so a reader that never releases does not keep its
 * snapshot in memory after the timeout. Every method that removes expired acquisitions
 * returns whether this ended the last snapshot: the scan can end it on any of these paths.
 * <p>
 * Each snapshot ends when it is out of the window, with no valid acquisitions and no admitted
 * queries (see {@code SnapshotUses}). The event that removes the last of these ends it: the
 * move of the tick that moves the window, the release or the removal after the timeout of the
 * last acquisition, the end of the last query. The removal of the partition goes through the
 * {@linkplain PartitionState states}. {@link #requestRemoval} is the last operation of the
 * writer: no more writes or commits. The next move of the tick makes the removal effective at
 * the new tick, the removal tick: the snapshots of the ticks before it stay acquirable while
 * they are in the window. When the last snapshot ends the partition is destroyed: the method
 * that ends it returns true, so the node can remove the partition. It happens once, and only
 * after the removal is effective. The partition has no reference to the node.
 */
final class PartitionStorage2 {

    /** The value that {@code acquire} returns when the snapshot left the window. */
    static final long NOT_ACQUIRED = SnapshotAcquisitions2.NOT_ACQUIRED;
    /** The value that {@code claimOwnership} returns when the generation is not the expected one. */
    static final long NOT_CLAIMED = -1;

    // Orders the moves of the tick and the operations that change the partition: each of
    // them runs while holding it. The readers never take it.
    private final ReentrantLock writerLock;
    // The state of the writer, which the next commit confirms. Changed only while holding
    // writerLock; the index reads it through its view of the slots.
    private final MutableSlots2 slots;
    private final SpatialIndex2 index;
    // The application metadata of the writer, like the slots. Changed only while holding
    // writerLock; removing an entity deletes its metadata.
    private final MutableMetadata metadata = new MutableMetadata();
    // The slots and the metadata as structures of the commits: the structures themselves,
    // or, in the tests, a component around them that can fail. The commits and the restores
    // use these; the writes and the reads use the structures.
    private final TransactionalComponent<SlotSnapshot2> slotCommits;
    private final TransactionalComponent<MetadataSnapshot> metadataCommits;
    private final CoordinateLimits limits;
    // The number of the last commit in the sequence. Only the writer uses it.
    private long commitSequence;
    // The snapshot of the last commit, the empty snapshot before the first commit. Only the
    // writer uses it, as the base of the next snapshot and of the rollback.
    private StorageSnapshot2 lastCommitted;
    // The snapshot that the next move of the tick publishes: the one of the last commit of
    // the current tick, or null if the tick has no commit. Written by the commit, read and
    // cleared by the move of the tick, both while holding writerLock.
    private StorageSnapshot2 pending;
    // Replaced by the move of the tick while holding writerLock; any thread reads it. The
    // volatile write publishes the new tick and the new window together.
    private volatile SnapshotCatalog2 catalog;
    // The state of the ownership. Written by claim and release while holding writerLock, read
    // by any thread: the volatile write publishes the generation and the owner together.
    private volatile Ownership ownership = Ownership.INITIAL;
    // The state of the removal. Written while holding writerLock: by the request of removal,
    // by the move of the tick that makes it effective, and by the end of the last snapshot
    // (which needs no lock: nothing else changes the state after RETIRED). Read by any thread.
    private volatile PartitionState state = PartitionState.ACTIVE;
    // Healthy, or in need of a recovery after a restore that failed. Written while holding
    // writerLock; read by any thread through health().
    private volatile PartitionHealth health = PartitionHealth.HEALTHY;
    // The tick of the last commit: the creation and the load count as a commit at the
    // creation tick. It is the base of the reports of the failures, and the owner reads it.
    // Only the writer uses it.
    private long lastCommitTick;
    // The structure that the writer is changing, for the reports of the failures. Only the
    // writer uses it.
    private PartitionComponent step = PartitionComponent.UNKNOWN;
    // The buffers of the coordinates. The commits take from the pool, and the lifetime gives
    // back the buffers of the ended snapshots: both use this instance.
    private final CoordinateBufferPool coordinateBuffers;
    private final SnapshotLifetime2 lifetime;
    private final SnapshotAcquisitions2 acquisitions;

    /** Creates a storage that uses the linear scan as index. */
    PartitionStorage2() {
        this(IndexConfig.linearScan());
    }

    /** Creates a storage with the given index, and with its limits, and the default number of retained ticks. */
    PartitionStorage2(IndexConfig index) {
        this(index, RetentionWindow.DEFAULT_RETAINED_TICKS);
    }

    /** Creates a storage with the given index, and with its limits, that keeps retainedTicks retained ticks. */
    PartitionStorage2(IndexConfig index, int retainedTicks) {
        this(index, retainedTicks, MonotonicClock.SYSTEM);
    }

    /**
     * Creates a storage at tick 0 with the given index, and with its limits, that keeps
     * retainedTicks retained ticks and measures the timeouts with the clock.
     */
    PartitionStorage2(IndexConfig index, int retainedTicks, MonotonicClock clock) {
        this(index, retainedTicks, clock, 0);
    }

    /**
     * Creates a storage at the given tick with the given index, and with its limits, that keeps
     * retainedTicks retained ticks and measures the timeouts with the clock.
     */
    PartitionStorage2(IndexConfig index, int retainedTicks, MonotonicClock clock, long creationTick) {
        this(index.limits(), slots -> SpatialIndexes.create(index, slots), retainedTicks, clock,
                creationTick);
    }

    /**
     * Creates a storage with the given limits, the index made by the factory
     * and the default number of retained ticks. The factory gets the slots of
     * the writer, so the index can read them.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory) {
        this(limits, indexFactory, RetentionWindow.DEFAULT_RETAINED_TICKS);
    }

    /**
     * Creates a storage with the given limits, the index made by the factory and
     * retainedTicks retained ticks. The factory gets the slots of the writer, so the
     * index can read them.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory,
            int retainedTicks) {
        this(limits, indexFactory, retainedTicks, MonotonicClock.SYSTEM);
    }

    /**
     * Creates a storage with the given limits, the index made by the factory,
     * retainedTicks retained ticks and the clock of the timeouts. The factory
     * gets the slots of the writer, so the index can read them.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory,
            int retainedTicks, MonotonicClock clock) {
        this(limits, indexFactory, retainedTicks, clock, 0);
    }

    /**
     * Creates a storage at the given tick with the given limits, the index made by the factory,
     * retainedTicks retained ticks and the clock of the timeouts. The factory gets the
     * slots of the writer, so the index can read them.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory,
            int retainedTicks, MonotonicClock clock, long creationTick) {
        this(limits, indexFactory, retainedTicks, clock, creationTick, new ReentrantLock(),
                new CoordinateBufferPool());
    }

    /**
     * Creates a storage at the given tick with the given limits, the index made by the factory,
     * retainedTicks retained ticks, the clock of the timeouts, the lock of the writer and the
     * empty pool of the coordinate buffers. The tests give a lock that tells them when a
     * thread starts to wait for it, and a pool on a deque that stops a thread that gives back
     * a buffer.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory,
            int retainedTicks, MonotonicClock clock, long creationTick, ReentrantLock writerLock,
            CoordinateBufferPool coordinateBuffers) {
        this(limits, indexFactory, retainedTicks, clock, creationTick, writerLock, coordinateBuffers,
                UnaryOperator.identity(), UnaryOperator.identity());
    }

    /**
     * Creates a storage like the constructor before, and puts the slots and the metadata into
     * the components of the commits that the wrappers make. The tests give wrappers whose
     * components fail in the commit or in the restore of the slots and of the metadata.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory,
            int retainedTicks, MonotonicClock clock, long creationTick, ReentrantLock writerLock,
            CoordinateBufferPool coordinateBuffers,
            UnaryOperator<TransactionalComponent<SlotSnapshot2>> slotWrapper,
            UnaryOperator<TransactionalComponent<MetadataSnapshot>> metadataWrapper) {
        this.writerLock = writerLock;
        this.limits = limits;
        this.coordinateBuffers = coordinateBuffers;
        this.slots = new MutableSlots2(coordinateBuffers);
        this.index = indexFactory.apply(slots);
        this.slotCommits = slotWrapper.apply(slots);
        this.metadataCommits = metadataWrapper.apply(metadata);
        this.lifetime = new SnapshotLifetime2(coordinateBuffers);
        this.acquisitions = new SnapshotAcquisitions2(clock, lifetime);
        this.catalog = SnapshotCatalog2.empty(creationTick, retainedTicks);
        // As if the partition had a commit at its creation tick: the first move of the tick
        // publishes the empty snapshot, unless a commit replaces it.
        this.lastCommitted = StorageSnapshot2.empty();
        this.pending = lastCommitted;
        this.lastCommitTick = creationTick;
    }

    // Writes of the owner.

    void insertAll(long generation, List<Entity2> entities) {
        writerLock.lock();
        try {
            checkWritable(generation);
            Set<Long> seen = new HashSet<>();
            for (Entity2 e : entities) {
                if (slots.slotOf(e.id()) != SlotSnapshot2.NONE || !seen.add(e.id())) {
                    throw new IllegalArgumentException("Duplicate entity ID: " + e.id());
                }
                checkLimits(e.id(), e.position());
            }
            try {
                step = PartitionComponent.SLOTS;
                slots.ensureCapacity(slots.size() + entities.size());
                for (Entity2 e : entities) {
                    insert(e.id(), e.position());
                }
            } catch (Throwable failure) {
                throw failed(PartitionOperation.WRITE, failure);
            }
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Sets the positions of entities, in the order of the list: an entity not in the partition
     * is added with the same ID, an entity in the partition moves and keeps its metadata. If an
     * ID appears more than once, the last position wins. Fails with IllegalArgumentException
     * if a position is outside the limits; in this case nothing changes.
     */
    void setAll(long generation, List<Entity2> entities) {
        writerLock.lock();
        try {
            checkWritable(generation);
            int added = 0;
            for (Entity2 e : entities) {
                checkLimits(e.id(), e.position());
                if (slots.slotOf(e.id()) == SlotSnapshot2.NONE) {
                    added++;
                }
            }
            try {
                step = PartitionComponent.SLOTS;
                // An ID that appears twice and is not in the partition is counted twice: the
                // capacity can be a bit larger than needed, but it is never too small.
                slots.ensureCapacity(slots.size() + added);
                for (Entity2 e : entities) {
                    int slot = slots.slotOf(e.id());
                    if (slot == SlotSnapshot2.NONE) {
                        insert(e.id(), e.position());
                    } else {
                        move(slot, e.position());
                    }
                }
            } catch (Throwable failure) {
                throw failed(PartitionOperation.WRITE, failure);
            }
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Applies the writes of the application metadata, in their order: if more writes set
     * the same key, the last one wins. Fails with NoSuchElementException if an entity is not
     * in the partition; in this case nothing changes.
     */
    void updateMetadataAll(long generation, List<MetadataWrite> writes) {
        writerLock.lock();
        try {
            checkWritable(generation);
            for (MetadataWrite write : writes) {
                existingSlot(write.entityId());
            }
            try {
                step = PartitionComponent.METADATA;
                for (MetadataWrite write : writes) {
                    metadata.write(write);
                }
            } catch (Throwable failure) {
                throw failed(PartitionOperation.WRITE, failure);
            }
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Applies a conditional write if all its conditions are true on the state of the writer:
     * first the positions, then the metadata. Returns the false conditions, in their order; if
     * there are any, nothing changes. Fails with NoSuchElementException if an entity of the
     * write or of a condition is not in the partition, and with IllegalArgumentException if a
     * position is outside the limits; in these cases nothing changes.
     */
    List<MetadataCondition> updateIf(long generation, ConditionalWrite2 write) {
        writerLock.lock();
        try {
            checkWritable(generation);
            for (MetadataCondition condition : write.conditions()) {
                existingSlot(condition.entityId());
            }
            for (Entity2 e : write.positions()) {
                existingSlot(e.id());
                checkLimits(e.id(), e.position());
            }
            for (MetadataWrite metadataWrite : write.metadata()) {
                existingSlot(metadataWrite.entityId());
            }
            List<MetadataCondition> failed = new ArrayList<>();
            for (MetadataCondition condition : write.conditions()) {
                if (!metadata.holds(condition)) {
                    failed.add(condition);
                }
            }
            if (!failed.isEmpty()) {
                return failed;
            }
            try {
                for (Entity2 e : write.positions()) {
                    move(slots.slotOf(e.id()), e.position());
                }
                step = PartitionComponent.METADATA;
                for (MetadataWrite metadataWrite : write.metadata()) {
                    metadata.write(metadataWrite);
                }
            } catch (Throwable failure) {
                throw failed(PartitionOperation.WRITE, failure);
            }
            return List.of();
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Removes entities with their metadata. An ID not in the partition, also because it
     * appears earlier in the array, is skipped.
     */
    void removeAll(long generation, long[] entityIds) {
        writerLock.lock();
        try {
            checkWritable(generation);
            try {
                for (long id : entityIds) {
                    if (slots.slotOf(id) != SlotSnapshot2.NONE) {
                        remove(id);
                    }
                }
            } catch (Throwable failure) {
                throw failed(PartitionOperation.WRITE, failure);
            }
        } finally {
            writerLock.unlock();
        }
    }

    // Load.

    /**
     * Loads the content of a copy into a new partition: its entities, with the same IDs,
     * positions and application metadata, and an index rebuilt by inserting them. Then it
     * commits them, as the commit of the creation tick: the first move of the tick publishes
     * them as the snapshot of the creation tick, and no reader sees a partition loaded in part.
     * The tick of the copy does not matter. The partition must have no commits and no owner, so
     * the node calls it before the partition is on the node, and then sets the creation tick
     * with {@link #setCreationTick}. Fails with
     * IllegalArgumentException if the copy has an entity ID twice or a position outside the
     * limits of the index; in this case nothing changes.
     */
    void load(PartitionCopy2 copy) {
        writerLock.lock();
        try {
            if (commitSequence != 0 || ownership.owned()) {
                throw new IllegalStateException("Only a new partition can be loaded");
            }
            int count = copy.size();
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < count; i++) {
                if (!seen.add(copy.entityId(i))) {
                    throw new IllegalArgumentException("Duplicate entity ID: " + copy.entityId(i));
                }
                checkLimits(copy.entityId(i), copy.position(i));
            }
            slots.ensureCapacity(count);
            for (int i = 0; i < count; i++) {
                long entityId = copy.entityId(i);
                insert(entityId, copy.position(i));
                for (Map.Entry<String, Long> pair : copy.metadata(i).entrySet()) {
                    metadata.write(new MetadataWrite.Put(entityId, pair.getKey(), pair.getValue()));
                }
            }
            commitWrites();
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Sets the creation tick of a partition that is not on a node yet: the node builds the
     * partition, and loads it, before it knows the tick at which it adds the partition to the
     * node. The partition is then at the new tick, and the snapshot that waits for the move of
     * the tick, empty or of the load, becomes the snapshot of that tick. Fails with
     * IllegalStateException if the tick of the partition has moved since its creation.
     */
    void setCreationTick(long creationTick) {
        writerLock.lock();
        try {
            SnapshotCatalog2 current = catalog;
            if (current.tick() != current.creationTick()) {
                throw new IllegalStateException("Tick of the partition has moved: " + current.tick());
            }
            catalog = SnapshotCatalog2.empty(creationTick, current.retainedTicks());
            lastCommitTick = creationTick;
        } finally {
            writerLock.unlock();
        }
    }

    // Commit and move of the tick.

    /**
     * Confirms the writes in the current tick and returns the snapshot of the commit. The next
     * move of the tick publishes it, unless a later commit in the same tick replaces it: then
     * the returned snapshot must not be read any more, because its buffer can go to a later
     * commit. It does not remove the expired acquisitions: the caller calls
     * {@link #removeExpired} after it and takes its result, because after the release of the
     * lock the partition can be removed and its last snapshot can end. Only the writer can call
     * it. Fails with OwnershipChangedException if generation is not the one of the owner, with
     * IllegalStateException if the partition needs a recovery, and with
     * PartitionFailureException if the commit fails: then the writes not committed are
     * discarded.
     */
    StorageSnapshot2 commit(long generation) {
        writerLock.lock();
        try {
            checkWritable(generation);
            return commitWrites();
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Discards the writes not committed: the state of the writer goes back to the last commit,
     * with the restore of a change of the ownership, and the commits stay confirmed. It is also
     * the recovery of a partition that needs one: when the restore works, the partition is
     * healthy again. Only the writer can call it. Fails with OwnershipChangedException if
     * generation is not the one of the owner, and with PartitionFailureException if the restore
     * fails, or with the Error as it is: then the partition needs a recovery.
     */
    void rollback(long generation) {
        writerLock.lock();
        try {
            checkOwner(generation);
            Throwable restoreFailure = restoreToLastCommit(PartitionOperation.ROLLBACK);
            if (restoreFailure != null) {
                throw reported(restoreFailure);
            }
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Returns the tick of the last commit: the creation of the partition, and its load, count
     * as a commit at the creation tick. Only the commits of the owner change the value, so
     * after a claim it tells the new owner whether the current tick already has a commit. It
     * works also when the partition needs a recovery. Only the writer can call it. Fails with
     * OwnershipChangedException if generation is not the one of the owner.
     */
    long lastCommitTick(long generation) {
        writerLock.lock();
        try {
            checkOwner(generation);
            return lastCommitTick;
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Moves the tick of the partition forward to the given tick. The move publishes the snapshot
     * of the last commit of the current tick, if there is one, as the snapshot of the current
     * tick; the ticks without a commit keep the snapshot before. The snapshots whose ticks are
     * all before the new window leave it, and the ones with no uses end. It also removes the
     * expired acquisitions. If the removal was requested, the move makes it effective at the
     * new tick. The same tick again does nothing, and does not make the removal effective.
     * Returns true if this destroys a removed partition. Fails with IllegalArgumentException if
     * the tick is less than the tick of the partition. The thread that moves the tick of the
     * node calls it, also while the writer works: it waits for a write or a commit that is
     * running.
     */
    boolean setTick(long tick) {
        SnapshotCatalog2 current;
        SnapshotCatalog2 next;
        StorageSnapshot2 published;
        writerLock.lock();
        try {
            current = catalog;
            if (tick < current.tick()) {
                throw new IllegalArgumentException("Tick " + tick + " is less than the tick " + current.tick());
            }
            if (tick == current.tick()) {
                return false;
            }
            published = pending;
            pending = null;
            boolean retire = state == PartitionState.REMOVAL_PENDING;
            next = current.advance(tick, published, retire);
            if (retire) {
                state = PartitionState.RETIRED;
            }
            if (published != null) {
                lifetime.published();
            }
            // The publication of the new tick and window.
            catalog = next;
        } finally {
            writerLock.unlock();
        }
        // After the publication, so a new acquisition finds the snapshot either in the
        // window or out of it, and in that case it chooses again from the new window.
        int kept = next.size() - (published != null ? 1 : 0);
        boolean lastEnded = false;
        for (int i = 0; i < current.size() - kept; i++) {
            lastEnded |= lifetime.leaveWindow(current.snapshotAt(i));
        }
        return destroyedIf(acquisitions.removeExpired() | lastEnded);
    }

    // Removal.

    /**
     * Requests the removal of the partition: the last operation of the writer. After this the
     * node allows no writes or commits, and the writes not committed are discarded: the last
     * snapshot is the state of the last commit. The next move of the tick makes the removal
     * effective. Fails with IllegalStateException if the removal was already requested, or if
     * requireEmpty is true and the state of the last commit has entities; in these cases
     * nothing changes. Fails with OwnershipChangedException if generation is not the one of
     * the owner. Only the writer can call it.
     */
    void requestRemoval(long generation, boolean requireEmpty) {
        writerLock.lock();
        try {
            if (state != PartitionState.ACTIVE) {
                throw new IllegalStateException("Removal was already requested");
            }
            checkOwner(generation);
            if (requireEmpty && lastCommitted.size() > 0) {
                throw new IllegalStateException("Committed state is not empty: " + lastCommitted.size() + " entities");
            }
            state = PartitionState.REMOVAL_PENDING;
        } finally {
            writerLock.unlock();
        }
    }

    /** Returns the state of the partition. Any thread can call it. */
    PartitionState state() {
        return state;
    }

    /**
     * Returns the health of the partition: healthy, or in need of a recovery after a restore
     * that failed. Any thread can call it.
     */
    PartitionHealth health() {
        return health;
    }

    // Ownership.

    /** Returns the state of the ownership. Any thread can call it. */
    Ownership ownership() {
        return ownership;
    }

    /**
     * Makes the caller the owner if the generation is still expectedGeneration: the generation
     * grows by one and the writes not committed are discarded. If the restore of the
     * structures fails, the ownership changes anyway and the partition needs a recovery; if it
     * works, the partition is healthy again. Returns the new generation, or
     * NOT_CLAIMED if the generation is not the expected one; in this case nothing changes.
     * Fails with IllegalStateException after the removal was requested. Any thread can call
     * it: it waits for a write, a commit or a move of the tick that is running.
     */
    long claimOwnership(long expectedGeneration) {
        writerLock.lock();
        try {
            if (state != PartitionState.ACTIVE) {
                throw new IllegalStateException("Removal was requested: the partition has no more owners");
            }
            Ownership current = ownership;
            if (current.generation() != expectedGeneration) {
                return NOT_CLAIMED;
            }
            restoreToLastCommit(PartitionOperation.CLAIM);
            ownership = new Ownership(current.generation() + 1, true);
            return current.generation() + 1;
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Leaves the ownership if the generation is the one of the current owner: the generation
     * grows by one, the partition has no owner and the writes not committed are discarded,
     * with the same health as {@link #claimOwnership}. Otherwise, and after the removal was
     * requested, it does nothing. Any thread can call it.
     */
    void releaseOwnership(long generation) {
        writerLock.lock();
        try {
            Ownership current = ownership;
            if (state != PartitionState.ACTIVE || !current.owned() || current.generation() != generation) {
                return;
            }
            restoreToLastCommit(PartitionOperation.RELEASE);
            ownership = new Ownership(current.generation() + 1, false);
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Fails with OwnershipChangedException if the generation is not the one of the current
     * owner. The caller holds writerLock, so the ownership cannot change until it ends.
     */
    private void checkOwner(long generation) {
        Ownership current = ownership;
        if (!current.owned() || current.generation() != generation) {
            throw new OwnershipChangedException("Token of generation " + generation
                    + " is not current: the ownership is " + current);
        }
    }

    /**
     * Fails with OwnershipChangedException if the generation is not the one of the current
     * owner, and then with IllegalStateException if the partition needs a recovery. The
     * caller holds writerLock.
     */
    private void checkWritable(long generation) {
        checkOwner(generation);
        if (health instanceof PartitionHealth.RecoveryRequired) {
            throw new IllegalStateException("Partition needs a recovery: writes and commits are refused");
        }
    }

    /**
     * Discards the writes not committed for a rollback, a claim or a release. If the restore
     * works, the partition is healthy, also if it needed a recovery before. If it fails, the
     * partition needs a recovery, with the report of the operation, and the method returns the
     * failure: a claim and a release change the ownership anyway, so a coordinator can always
     * take the ownership away from a writer. The caller holds writerLock.
     */
    private Throwable restoreToLastCommit(PartitionOperation operation) {
        Throwable restoreFailure = tryRestore();
        if (restoreFailure == null) {
            health = PartitionHealth.HEALTHY;
            return null;
        }
        try {
            health = new PartitionHealth.RecoveryRequired(new PartitionFailure(operation, step,
                    FailureOutcome.RECOVERY_REQUIRED, lastCommitTick, restoreFailure, null));
        } catch (Throwable reportFailure) {
            // The report needs memory: without it the health stays without a report.
        }
        return restoreFailure;
    }

    /**
     * Returns the PartitionFailureException with the report in the health, for a restore
     * that failed, or throws the failure as it is if it is an Error or there is no report.
     */
    private RuntimeException reported(Throwable restoreFailure) {
        if (restoreFailure instanceof RuntimeException runtimeFailure) {
            if (health instanceof PartitionHealth.RecoveryRequired(PartitionFailure report) && report != null) {
                try {
                    return new PartitionFailureException(report);
                } catch (Throwable reportFailure) {
                    // The exception needs memory: the caller gets at least the failure.
                }
            }
            throw runtimeFailure;
        }
        throw (Error) restoreFailure;
    }

    /**
     * Handles a failure of a write or a commit after the checks of the input: discards the
     * writes not committed and returns the PartitionFailureException that the caller throws.
     * If the failure is an Error, it throws it, with the failure of the restore as a
     * suppressed exception. If the restore fails, the partition needs a recovery. Without the
     * memory for the report, it throws the failure as it is. The caller holds writerLock.
     */
    private RuntimeException failed(PartitionOperation operation, Throwable failure) {
        PartitionComponent component = step;
        Throwable restoreFailure = tryRestore();
        RuntimeException reported = null;
        try {
            FailureOutcome outcome = restoreFailure == null
                    ? FailureOutcome.WRITES_DISCARDED
                    : FailureOutcome.RECOVERY_REQUIRED;
            PartitionFailure report = new PartitionFailure(operation, component, outcome, lastCommitTick, failure,
                    restoreFailure);
            if (restoreFailure != null) {
                health = new PartitionHealth.RecoveryRequired(report);
            }
            if (failure instanceof RuntimeException) {
                reported = new PartitionFailureException(report);
            } else if (restoreFailure != null) {
                failure.addSuppressed(restoreFailure);
            }
        } catch (Throwable reportFailure) {
            // The report needs memory: the caller gets at least the failure of the operation.
        }
        if (reported != null) {
            return reported;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    /**
     * Brings the structures back to the last commit. Returns null if it works, or the failure
     * of the restore: then the partition needs a recovery, and step is the structure that
     * failed. The caller holds writerLock.
     */
    private Throwable tryRestore() {
        try {
            discardUncommittedWrites();
            step = PartitionComponent.UNKNOWN;
            return null;
        } catch (Throwable restoreFailure) {
            // Before anything that allocates, so the partition is blocked also without memory.
            health = PartitionHealth.RecoveryRequired.UNREPORTED;
            return restoreFailure;
        }
    }

    /**
     * Brings the state of the writer back to the last commit. The slots go back first, with
     * the numbering of the commit; then the index goes back to its snapshot of the same
     * commit, on the restored slots; then the metadata. The index gets no notifications for
     * the discarded writes. The next commit copies what changed, as after any write. The
     * caller holds writerLock.
     */
    private void discardUncommittedWrites() {
        StorageSnapshot2 confirmed = lastCommitted;
        step = PartitionComponent.SLOTS;
        slotCommits.restore(confirmed.slots());
        step = PartitionComponent.INDEX;
        index.restore(confirmed.index());
        step = PartitionComponent.METADATA;
        metadataCommits.restore(confirmed.metadata());
    }

    // Information about the partition.

    /**
     * Returns the number of entities of the writer, with the writes not committed. Only the
     * writer can call it.
     */
    int size() {
        return slots.size();
    }

    CoordinateLimits limits() {
        return limits;
    }

    /** Returns the tick at which the partition was created. Any thread can call it. */
    long creationTick() {
        return catalog.creationTick();
    }

    /** Returns the tick of the partition, from the published catalog. Any thread can call it. */
    long tick() {
        return catalog.tick();
    }

    /**
     * Returns the catalog of the current tick, with the snapshots in the retention
     * window. Unlike the other methods, any thread can call it.
     */
    SnapshotCatalog2 catalog() {
        return catalog;
    }

    // Readers. The acquisitions do not take writerLock; the methods that can end the last
    // snapshot record the destruction of the partition and return true.

    /**
     * Acquires the snapshot of the tick with the default timeout and returns the number of the
     * acquisition, or NOT_ACQUIRED if the snapshot left the window.
     */
    long acquire(StorageSnapshot2 snapshot, long tick) {
        return acquire(snapshot, tick, AcquireOptions.defaultTimeout());
    }

    /**
     * Acquires the snapshot of the tick with the given options and returns the number of the
     * acquisition. The caller chooses the snapshot of the tick from a {@linkplain #catalog() catalog}.
     * Returns NOT_ACQUIRED, and uses no number, if a move of the tick made the snapshot leave
     * the window after the choice: the caller chooses again from the new catalog. It does not
     * remove the expired acquisitions: the caller calls {@link #removeExpired} after it and
     * takes its result, because the new acquisition can already be expired. Any thread can
     * call it.
     */
    long acquire(StorageSnapshot2 snapshot, long tick, AcquireOptions options) {
        return acquisitions.acquire(snapshot, tick, options);
    }

    /**
     * Admits a query: returns the acquired snapshot, which the query then reads, and adds
     * a use to it. The query must call {@link #endQuery} when it ends, also if it fails.
     * Fails with IllegalStateException if the acquisition was released or is expired, and with
     * IllegalArgumentException if this storage never made it or if its tick is not the given
     * tick. Any thread can call it.
     */
    StorageSnapshot2 admit(long acquisitionId, long tick) {
        return acquisitions.admit(acquisitionId, tick);
    }

    /**
     * Ends a query admitted by {@link #admit}: removes its use, which can end the
     * snapshot. Returns true if this destroys the removed partition. Any thread can call it.
     */
    boolean endQuery(StorageSnapshot2 snapshot) {
        return destroyedIf(acquisitions.endQuery(snapshot));
    }

    /**
     * Releases an acquisition: removes its use, which can end the snapshot. Releasing it
     * again, or after its timeout, does nothing. It also removes the expired acquisitions.
     * Returns true if this destroys the removed partition. Fails with IllegalArgumentException if
     * this storage never made it. Any thread can call it.
     */
    boolean release(long acquisitionId) {
        return destroyedIf(acquisitions.release(acquisitionId));
    }

    /**
     * Removes the expired acquisitions and their uses, which can end their snapshots.
     * Returns true if this destroys the removed partition. The node calls it after each
     * acquisition and commit. Any thread can call it.
     */
    boolean removeExpired() {
        return destroyedIf(acquisitions.removeExpired());
    }

    // Inspection and monitoring.

    /**
     * Returns the number of acquisitions not yet released or removed after the timeout:
     * an expired acquisition counts until the next acquisition, release, commit or move of the tick.
     * Any thread can call it.
     */
    int acquisitionCount() {
        return acquisitions.count();
    }

    /**
     * Returns the number of published snapshots that did not end yet: the snapshots in the
     * window and the ones out of it that still have uses. Any thread can call it.
     */
    int liveSnapshotCount() {
        return lifetime.liveCount();
    }

    /**
     * Returns the number of coordinate buffers in the pool, ready for the next commits: it
     * shows how many buffers the ended snapshots keep for reuse. Any thread can call it.
     */
    int pooledBufferCount() {
        return coordinateBuffers.size();
    }

    /**
     * Returns the number of pages that the commits copied into the coordinate buffers since
     * the creation: it shows how much the commits copy. Only the writer can call it.
     */
    long copiedPageCount() {
        return slots.copiedPageCount();
    }

    /**
     * Confirms the writes of the writer in a new commit of the current tick and returns its
     * snapshot, which replaces the one that waits for the move of the tick. The caller holds
     * writerLock.
     */
    private StorageSnapshot2 commitWrites() {
        // A sequence is used once, also by a commit that fails: its preparation can have
        // made read-only the nodes of the structures made with it.
        commitSequence++;
        SlotSnapshot2 slotSnapshot = null;
        IndexSnapshot2 indexSnapshot = null;
        MetadataSnapshot metadataSnapshot = null;
        StorageSnapshot2 snapshot;
        try {
            step = PartitionComponent.SLOTS;
            slotSnapshot = slotCommits.prepareCommit(commitSequence);
            step = PartitionComponent.INDEX;
            indexSnapshot = index.prepareCommit(commitSequence);
            step = PartitionComponent.METADATA;
            metadataSnapshot = metadataCommits.prepareCommit(commitSequence);
            step = PartitionComponent.UNKNOWN;
            snapshot = new StorageSnapshot2(slotSnapshot, indexSnapshot, metadataSnapshot);
        } catch (Throwable failure) {
            if (slotSnapshot != null) {
                abandon(slotCommits, slotSnapshot, failure);
            }
            if (indexSnapshot != null) {
                abandon(index, indexSnapshot, failure);
            }
            if (metadataSnapshot != null) {
                abandon(metadataCommits, metadataSnapshot, failure);
            }
            throw failed(PartitionOperation.COMMIT, failure);
        }
        // The acceptance cannot fail: all the structures take the commit, and then the partition.
        slotCommits.acceptCommit(slotSnapshot);
        index.acceptCommit(indexSnapshot);
        metadataCommits.acceptCommit(metadataSnapshot);
        // Replaced before the move of the tick, if not null: no reader ever saw it. It gives
        // back its buffer only now, so a commit that fails leaves it whole.
        StorageSnapshot2 replaced = pending;
        lastCommitted = snapshot;
        pending = snapshot;
        lastCommitTick = catalog.tick();
        if (replaced != null) {
            lifetime.replaced(replaced);
        }
        return snapshot;
    }

    /**
     * Gives back the resources of a snapshot that the commit does not accept. A failure here
     * goes with the failure of the commit, which the caller gets.
     */
    private static <S> void abandon(TransactionalComponent<S> component, S prepared, Throwable failure) {
        try {
            component.abandonCommit(prepared);
        } catch (Throwable abandonFailure) {
            suppress(failure, abandonFailure);
        }
    }

    /**
     * Adds the other failure to the suppressed ones of the failure, if it can: the same
     * instance cannot suppress itself, and the list of the suppressed failures needs memory.
     * The caller always throws the failure, also when this adds nothing.
     */
    private static void suppress(Throwable failure, Throwable other) {
        if (other == failure) {
            return;
        }
        try {
            failure.addSuppressed(other);
        } catch (Throwable ignored) {
            // Only a detail of the diagnosis is lost: the failure goes to the caller as it is.
        }
    }

    // Steps of the writes: the slots change first, then the index learns the change.

    private void insert(long entityId, Point2 position) {
        step = PartitionComponent.SLOTS;
        int slot = slots.insert(entityId, position, nextCommit());
        step = PartitionComponent.INDEX;
        index.inserted(slot, position);
    }

    private void move(int slot, Point2 to) {
        step = PartitionComponent.SLOTS;
        Point2 from = slots.positionAt(slot);
        slots.write(slot, to, nextCommit());
        step = PartitionComponent.INDEX;
        index.moved(slot, from, to);
    }

    /**
     * Removes an entity from the slots and deletes its metadata, then tells the index that its slot is free and, if
     * the last entity moved into that slot, where it went.
     */
    private void remove(long entityId) {
        int slot = slots.slotOf(entityId);
        Point2 position = slots.positionAt(slot);
        int last = slots.size() - 1;
        step = PartitionComponent.SLOTS;
        slots.remove(entityId, nextCommit());
        step = PartitionComponent.METADATA;
        metadata.removeEntity(entityId);
        step = PartitionComponent.INDEX;
        index.removed(slot, position);
        if (slot != last) {
            index.relocated(last, slot, slots.positionAt(slot));
        }
    }

    /** Returns the commit that confirms the writes made now: the next one in the sequence. */
    private long nextCommit() {
        return commitSequence + 1;
    }

    private void checkLimits(long entityId, Point2 position) {
        if (!limits.contains(position)) {
            throw new IllegalArgumentException("Position of entity " + entityId + " is outside the limits "
                    + limits.min() + ", " + limits.max() + ": " + position);
        }
    }

    /** Returns the slot of an entity. Fails with NoSuchElementException if the ID is not in the slots. */
    private int existingSlot(long entityId) {
        int slot = slots.slotOf(entityId);
        if (slot == SlotSnapshot2.NONE) {
            throw new NoSuchElementException("Unknown entity ID: " + entityId);
        }
        return slot;
    }

    /**
     * Records the destruction of the partition if lastEnded is true: the last published
     * snapshot ended, and the lifetime already emptied the pool. Returns lastEnded. Only the
     * thread that ended the last snapshot gets true, so this happens once.
     */
    private boolean destroyedIf(boolean lastEnded) {
        if (lastEnded) {
            state = PartitionState.DESTROYED;
        }
        return lastEnded;
    }
}
