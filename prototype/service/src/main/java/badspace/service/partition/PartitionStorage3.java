package badspace.service.partition;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.Ownership;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.RetentionWindow;
import badspace.common.geometry.Point3;
import badspace.service.index.SlotView3;
import badspace.service.index.SpatialIndex3;
import badspace.service.index.SpatialIndexes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * A 3D partition on its node: it coordinates the writer, the moves of the tick and the
 * readers. It has a single writer and is not thread-safe, except {@link #setTick},
 * {@link #catalog()}, {@link #tick()}, {@link #creationTick()}, the ownership and the
 * methods of the acquisitions.
 * <p>
 * The partition keeps the protocols that involve more than one part, and gives the details
 * to its components:
 * <ul>
 *   <li>{@link MutableSlots3}: the entities of the writer, in primitive arrays, and the
 *       snapshots of the slots;</li>
 *   <li>the spatial index, which reads the slots through their view; the partition tells it
 *       each change, after the change of the slots;</li>
 *   <li>{@link SnapshotAcquisitions3}: the acquisitions, the queries and the timeouts;</li>
 *   <li>{@link SnapshotLifetime3}: the end of the snapshots and the count of the live ones;</li>
 *   <li>{@link CoordinateBufferPool}: the buffers of the coordinates, which the commits take
 *       and the ended snapshots give back.</li>
 * </ul>
 * <p>
 * Each write, commit and request of removal takes the generation of the owner and first
 * checks it, under the lock of the writer, together with all the input; then it applies
 * the write. So a failed write, or one with a generation that is not current, does not
 * change the partition. The positions of the entities must be in the limits of the index:
 * writes outside them fail, so the indices never see them. Nobody queries the state of the
 * writer: queries read the snapshots, through the acquisitions.
 * <p>
 * Writes are grouped in commits. Each commit makes a {@link StorageSnapshot3}: the snapshot
 * of the slots and the snapshot of the index of the same commit. Each commit advances an
 * internal sequence for the copy-on-write of the index and for the pages of the slots: the
 * partition owns it, and gives the slots the commit of each change. This sequence is separate
 * from the logical tick: one tick can have many commits. The coordinates are copied only in
 * the pages changed after the commit of the buffer that the commit takes from the pool; the IDs
 * and the table from ID to slot only after insertions or removals, else the new snapshot shares
 * them with the one before. A commit without changes of the coordinates shares the buffer of
 * the snapshot before it. If the pool has no buffer large enough, the commit allocates one:
 * the writer never waits for a reader, only the memory grows.
 * <p>
 * The partition has a tick: the logical time of the partition, which the node moves forward.
 * The snapshot of the last commit of a tick is not published at the commit: the move of the
 * tick publishes it in the {@link SnapshotCatalog3}, as the snapshot of that tick and of the
 * ticks without a commit after it. A later commit in the same tick replaces the snapshot that
 * waits for the move, and gives back its buffer at once, because no reader ever saw it. The new
 * partition has the empty snapshot as if it had a commit at its creation tick. The catalog
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
 * queries (see {@link SnapshotUses}). The event that removes the last of these ends it: the
 * move of the tick that moves the window, the release or the removal after the timeout of the
 * last acquisition, the end of the last query. The removal of the partition goes through the
 * {@linkplain PartitionState states}. {@link #requestRemoval} is the last operation of the
 * writer: no more writes or commits. The next move of the tick makes the removal effective at
 * the new tick, the removal tick: the snapshots of the ticks before it stay acquirable while
 * they are in the window. When the last snapshot ends the partition is destroyed: the method
 * that ends it returns true, so the node can remove the partition. It happens once, and only
 * after the removal is effective. The partition has no reference to the node.
 */
final class PartitionStorage3 {

    /** The value that {@code acquire} returns when the snapshot left the window. */
    static final long NOT_ACQUIRED = SnapshotAcquisitions3.NOT_ACQUIRED;
    /** The value that {@code claimOwnership} returns when the generation is not the expected one. */
    static final long NOT_CLAIMED = -1;

    // Orders the moves of the tick and the operations that change the partition: each of
    // them runs while holding it. The readers never take it.
    private final ReentrantLock writerLock;
    // The state of the writer, which the next commit confirms. Changed only while holding
    // writerLock; the index reads it through its view of the slots.
    private final MutableSlots3 slots = new MutableSlots3();
    private final SpatialIndex3 index;
    private final CoordinateLimits limits;
    // The number of the last commit in the sequence. Only the writer uses it.
    private long commitSequence;
    // The snapshot of the last commit, the empty snapshot before the first commit. Only the
    // writer uses it, as the base of the next snapshot and of the rollback.
    private StorageSnapshot3 lastCommitted;
    // The snapshot that the next move of the tick publishes: the one of the last commit of
    // the current tick, or null if the tick has no commit. Written by the commit, read and
    // cleared by the move of the tick, both while holding writerLock.
    private StorageSnapshot3 pending;
    // Replaced by the move of the tick while holding writerLock; any thread reads it. The
    // volatile write publishes the new tick and the new window together.
    private volatile SnapshotCatalog3 catalog;
    // The state of the ownership. Written by claim and release while holding writerLock, read
    // by any thread: the volatile write publishes the generation and the owner together.
    private volatile Ownership ownership = Ownership.INITIAL;
    // The state of the removal. Written while holding writerLock: by the request of removal,
    // by the move of the tick that makes it effective, and by the end of the last snapshot
    // (which needs no lock: nothing else changes the state after RETIRED). Read by any thread.
    private volatile PartitionState state = PartitionState.ACTIVE;
    // The buffers of the coordinates. The commits take from the pool, and the lifetime gives
    // back the buffers of the ended snapshots: both use this instance.
    private final CoordinateBufferPool coordinateBuffers;
    private final SnapshotLifetime3 lifetime;
    private final SnapshotAcquisitions3 acquisitions;

    /** Creates a storage that uses the linear scan as index. */
    PartitionStorage3() {
        this(IndexConfig.linearScan());
    }

    /** Creates a storage with the given index, and with its limits, and the default number of retained ticks. */
    PartitionStorage3(IndexConfig index) {
        this(index, RetentionWindow.DEFAULT_RETAINED_TICKS);
    }

    /** Creates a storage with the given index, and with its limits, that keeps retainedTicks retained ticks. */
    PartitionStorage3(IndexConfig index, int retainedTicks) {
        this(index, retainedTicks, MonotonicClock.SYSTEM);
    }

    /**
     * Creates a storage at tick 0 with the given index, and with its limits, that keeps
     * retainedTicks retained ticks and measures the timeouts with the clock.
     */
    PartitionStorage3(IndexConfig index, int retainedTicks, MonotonicClock clock) {
        this(index, retainedTicks, clock, 0);
    }

    /**
     * Creates a storage at the given tick with the given index, and with its limits, that keeps
     * retainedTicks retained ticks and measures the timeouts with the clock.
     */
    PartitionStorage3(IndexConfig index, int retainedTicks, MonotonicClock clock, long creationTick) {
        this(index.limits(), slots -> SpatialIndexes.create(index, slots), retainedTicks, clock,
                creationTick);
    }

    /**
     * Creates a storage with the given limits, the index made by the factory
     * and the default number of retained ticks. The factory gets the slots of
     * the writer, so the index can read them.
     */
    PartitionStorage3(CoordinateLimits limits, Function<SlotView3, SpatialIndex3> indexFactory) {
        this(limits, indexFactory, RetentionWindow.DEFAULT_RETAINED_TICKS);
    }

    /**
     * Creates a storage with the given limits, the index made by the factory and
     * retainedTicks retained ticks. The factory gets the slots of the writer, so the
     * index can read them.
     */
    PartitionStorage3(CoordinateLimits limits, Function<SlotView3, SpatialIndex3> indexFactory,
            int retainedTicks) {
        this(limits, indexFactory, retainedTicks, MonotonicClock.SYSTEM);
    }

    /**
     * Creates a storage with the given limits, the index made by the factory,
     * retainedTicks retained ticks and the clock of the timeouts. The factory
     * gets the slots of the writer, so the index can read them.
     */
    PartitionStorage3(CoordinateLimits limits, Function<SlotView3, SpatialIndex3> indexFactory,
            int retainedTicks, MonotonicClock clock) {
        this(limits, indexFactory, retainedTicks, clock, 0);
    }

    /**
     * Creates a storage at the given tick with the given limits, the index made by the factory,
     * retainedTicks retained ticks and the clock of the timeouts. The factory gets the
     * slots of the writer, so the index can read them.
     */
    PartitionStorage3(CoordinateLimits limits, Function<SlotView3, SpatialIndex3> indexFactory,
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
    PartitionStorage3(CoordinateLimits limits, Function<SlotView3, SpatialIndex3> indexFactory,
            int retainedTicks, MonotonicClock clock, long creationTick, ReentrantLock writerLock,
            CoordinateBufferPool coordinateBuffers) {
        this.writerLock = writerLock;
        this.limits = limits;
        this.index = indexFactory.apply(slots);
        this.coordinateBuffers = coordinateBuffers;
        this.lifetime = new SnapshotLifetime3(coordinateBuffers);
        this.acquisitions = new SnapshotAcquisitions3(clock, lifetime);
        this.catalog = SnapshotCatalog3.empty(creationTick, retainedTicks);
        // As if the partition had a commit at its creation tick: the first move of the tick
        // publishes the empty snapshot, unless a commit replaces it.
        this.lastCommitted = StorageSnapshot3.empty();
        this.pending = lastCommitted;
    }

    // Writes of the owner.

    void insertAll(long generation, List<Entity3> entities) {
        writerLock.lock();
        try {
            checkOwner(generation);
            Set<Long> seen = new HashSet<>();
            for (Entity3 e : entities) {
                if (slots.slotOf(e.id()) != SlotTable.NONE || !seen.add(e.id())) {
                    throw new IllegalArgumentException("Duplicate entity ID: " + e.id());
                }
                checkLimits(e);
            }
            slots.ensureCapacity(slots.size() + entities.size());
            for (Entity3 e : entities) {
                insert(e.id(), e.position());
            }
        } finally {
            writerLock.unlock();
        }
    }

    void updateAll(long generation, List<Entity3> entities) {
        writerLock.lock();
        try {
            checkOwner(generation);
            for (Entity3 e : entities) {
                existingSlot(e.id());
                checkLimits(e);
            }
            for (Entity3 e : entities) {
                move(slots.slotOf(e.id()), e.position());
            }
        } finally {
            writerLock.unlock();
        }
    }

    void removeAll(long generation, long[] entityIds) {
        writerLock.lock();
        try {
            checkOwner(generation);
            Set<Long> seen = new HashSet<>();
            for (long id : entityIds) {
                existingSlot(id);
                if (!seen.add(id)) {
                    throw new IllegalArgumentException("Duplicate entity ID: " + id);
                }
            }
            for (long id : entityIds) {
                remove(id);
            }
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
     * it. Fails with OwnershipChangedException if generation is not the one of the owner.
     */
    StorageSnapshot3 commit(long generation) {
        StorageSnapshot3 snapshot;
        writerLock.lock();
        try {
            checkOwner(generation);
            commitSequence++;
            // Replaced before the move of the tick, if not null: no reader ever saw it.
            StorageSnapshot3 replaced = pending;
            CoordinateBuffer coordinates;
            if (slots.coordinatesChanged()) {
                if (replaced != null) {
                    // Its buffer can go to this commit, with only the pages changed since.
                    lifetime.replaced(replaced);
                    replaced = null;
                }
                coordinates = slots.copyChangedPages(coordinateBuffers, commitSequence);
            } else {
                coordinates = lastCommitted.slots().coordinateBuffer();
            }
            snapshot = new StorageSnapshot3(slots.snapshot(coordinates, lastCommitted.slots()),
                    index.commit(commitSequence));
            if (replaced != null) {
                // After the new snapshot became a user of the shared buffer.
                lifetime.replaced(replaced);
            }
            lastCommitted = snapshot;
            pending = snapshot;
        } finally {
            writerLock.unlock();
        }
        return snapshot;
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
        SnapshotCatalog3 current;
        SnapshotCatalog3 next;
        StorageSnapshot3 published;
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

    // Ownership.

    /** Returns the state of the ownership. Any thread can call it. */
    Ownership ownership() {
        return ownership;
    }

    /**
     * Makes the caller the owner if the generation is still expectedGeneration: the generation
     * grows by one and the writes not committed are discarded. Returns the new generation, or
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
            discardUncommittedWrites();
            ownership = new Ownership(current.generation() + 1, true);
            return current.generation() + 1;
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Leaves the ownership if the generation is the one of the current owner: the generation
     * grows by one, the partition has no owner and the writes not committed are discarded.
     * Otherwise, and after the removal was requested, it does nothing. Any thread can call it.
     */
    void releaseOwnership(long generation) {
        writerLock.lock();
        try {
            Ownership current = ownership;
            if (state != PartitionState.ACTIVE || !current.owned() || current.generation() != generation) {
                return;
            }
            discardUncommittedWrites();
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
     * Brings the state of the writer back to the last commit: removes the entities that the
     * commit does not have, inserts again the ones it has and moves the others back to their
     * positions in it. It uses the same steps as the writes, so the index learns each change
     * like for any write; the slots can end in a different order than in the commit. The next
     * commit copies what changed, as after any write. It does nothing if there are no writes
     * after the last commit. The caller holds writerLock.
     */
    private void discardUncommittedWrites() {
        if (!slots.hasChanges()) {
            return;
        }
        SlotSnapshot3 committed = lastCommitted.slots();
        List<Long> added = new ArrayList<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            if (committed.slotOf(slots.idAt(slot)) == SlotTable.NONE) {
                added.add(slots.idAt(slot));
            }
        }
        for (long entityId : added) {
            remove(entityId);
        }
        slots.ensureCapacity(committed.size());
        for (int committedSlot = 0; committedSlot < committed.size(); committedSlot++) {
            long entityId = committed.idAt(committedSlot);
            Point3 position = committed.positionAt(committedSlot);
            int slot = slots.slotOf(entityId);
            if (slot == SlotTable.NONE) {
                insert(entityId, position);
            } else if (!slots.positionAt(slot).equals(position)) {
                move(slot, position);
            }
        }
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
    SnapshotCatalog3 catalog() {
        return catalog;
    }

    // Readers. The acquisitions do not take writerLock; the methods that can end the last
    // snapshot record the destruction of the partition and return true.

    /**
     * Acquires the snapshot of the tick with the default timeout and returns the number of the
     * acquisition, or NOT_ACQUIRED if the snapshot left the window.
     */
    long acquire(StorageSnapshot3 snapshot, long tick) {
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
    long acquire(StorageSnapshot3 snapshot, long tick, AcquireOptions options) {
        return acquisitions.acquire(snapshot, tick, options);
    }

    /**
     * Admits a query: returns the acquired snapshot, which the query then reads, and adds
     * a use to it. The query must call {@link #endQuery} when it ends, also if it fails.
     * Fails with IllegalStateException if the acquisition was released or is expired, and with
     * IllegalArgumentException if this storage never made it or if its tick is not the given
     * tick. Any thread can call it.
     */
    StorageSnapshot3 admit(long acquisitionId, long tick) {
        return acquisitions.admit(acquisitionId, tick);
    }

    /**
     * Ends a query admitted by {@link #admit}: removes its use, which can end the
     * snapshot. Returns true if this destroys the removed partition. Any thread can call it.
     */
    boolean endQuery(StorageSnapshot3 snapshot) {
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

    // Steps of the writes: the slots change first, then the index learns the change.

    private void insert(long entityId, Point3 position) {
        int slot = slots.insert(entityId, position, nextCommit());
        index.inserted(slot, position);
    }

    private void move(int slot, Point3 to) {
        Point3 from = slots.positionAt(slot);
        slots.write(slot, to, nextCommit());
        index.moved(slot, from, to);
    }

    /**
     * Removes an entity from the slots, then tells the index that its slot is free and, if
     * the last entity moved into that slot, where it went.
     */
    private void remove(long entityId) {
        int slot = slots.slotOf(entityId);
        Point3 position = slots.positionAt(slot);
        int last = slots.size() - 1;
        slots.remove(entityId, nextCommit());
        index.removed(slot, position);
        if (slot != last) {
            index.relocated(last, slot, slots.positionAt(slot));
        }
    }

    /** Returns the commit that confirms the writes made now: the next one in the sequence. */
    private long nextCommit() {
        return commitSequence + 1;
    }

    private void checkLimits(Entity3 e) {
        if (!limits.contains(e.position())) {
            throw new IllegalArgumentException("Position of entity " + e.id() + " is outside the limits "
                    + limits.min() + ", " + limits.max() + ": " + e.position());
        }
    }

    /** Returns the slot of an entity. Fails with NoSuchElementException if the ID is not in the slots. */
    private int existingSlot(long entityId) {
        int slot = slots.slotOf(entityId);
        if (slot == SlotTable.NONE) {
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
