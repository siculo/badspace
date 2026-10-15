package badspace.service.partition;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.RetentionWindow;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.service.index.SlotView2;
import badspace.service.index.SpatialIndex2;
import badspace.service.index.SpatialIndexes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Entities of a 2D partition. It has a single writer and is not thread-safe,
 * except {@link #catalog()}, {@link #lastSnapshot()}, {@link #tick()},
 * {@link #creationTick()} and the methods of the acquisitions.
 * Entities are stored in primitive arrays: slotEntityIds[i] has coordinates
 * coords[2*i], coords[2*i+1]. A {@link SlotTable} gives the slot of each ID.
 * Removal moves the last entity into the free slot, so the arrays stay compact
 * and the order of the entities can change.
 * Each write first checks all the input, then applies it, so a failed write
 * does not change the partition.
 * Queries go to the spatial index of the partition, which the storage informs
 * of each change. The index finds the slots and the storage builds the entities.
 * The positions of the entities must be in the limits of the index: writes
 * outside them fail, so the indices never see them.
 * Writes are grouped in commits, whose numbers always grow. Each commit makes
 * a {@link StorageSnapshot2}: a snapshot of the slots and the snapshot of the
 * index of the same commit. The coordinates are copied at each commit; the
 * IDs and the table from ID to slot only after insertions or removals, else
 * the new snapshot shares them with the one before.
 * <p>
 * The commits have a number in a sequence that only grows, for the copy-on-write
 * of the index.
 * <p>
 * The storage has a tick: the logical time of the partition, which the node moves
 * forward. The snapshot of the last commit of a tick is not published at the
 * commit: the move of the tick publishes it in the {@link SnapshotCatalog2}, as
 * the snapshot of that tick and of the ticks without a commit after it. The new
 * partition has the empty snapshot as if it had a commit at its creation tick.
 * The catalog holds the same snapshots, not copies, for the ticks of the
 * retention window.
 * <p>
 * The reads of the storage see the state of the writer, with the writes not
 * yet committed, and only the writer can call them. The other threads read
 * the {@linkplain #catalog() catalog}: the move of the tick publishes it with a
 * single write of a volatile field, so a reader sees either the catalog before
 * or the whole new catalog, never a new tick with an old window. For now the
 * writer and the thread that moves the tick must not work at the same time.
 * <p>
 * The storage also keeps the acquisitions of the snapshots: each one has a number
 * and holds its snapshot, also after the snapshot leaves the window, until it is
 * released or expires. The timeouts are measured with the {@link MonotonicClock}
 * of the node. At each acquisition, release, commit and move of the tick the storage
 * removes the expired acquisitions, so a reader that never releases does not keep its
 * snapshot in memory after the timeout.
 * <p>
 * Each snapshot ends when it is out of the window, with no valid acquisitions and
 * no admitted queries (see {@link SnapshotUses}). The event that removes the last of
 * these ends it: the move of the tick that moves the window, the release or the removal
 * after the timeout of the last acquisition, the end of the last query. The storage counts
 * the published snapshots that did not end yet.
 * <p>
 * The removal of the partition has two steps. {@link #markRemoved()} marks the storage
 * as removed: no more writes, commits or new acquisitions, and the window closes, so
 * every snapshot leaves it. The acquisitions made before stay readable. When the last
 * snapshot ends the partition is finished: the method that ends it returns true, so the
 * node can remove the storage. It happens once, and only after the mark. The storage
 * has no reference to the node.
 */
final class PartitionStorage2 implements SlotView2 {

    private static final int DIMENSIONS = 2;
    private static final int INITIAL_CAPACITY = 16;
    /** The value that {@code acquire} returns when the snapshot left the window. */
    static final long NOT_ACQUIRED = 0;

    // Slot: entity ID and position. coords holds DIMENSIONS values per slot (x, y),
    // so slot i is slotEntityIds[i] and coords[DIMENSIONS * i ...]. The capacity of
    // coords is always DIMENSIONS times the capacity of slotEntityIds.
    private long[] slotEntityIds = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private final SlotTable slotById = new SlotTable();
    private int size;
    // The thread that moves the tick replaces the catalog at each move, and any thread reads
    // it. The volatile write publishes the new tick and the new window together.
    private volatile SnapshotCatalog2 catalog;
    // The snapshot of the last commit, the empty snapshot before the first commit. Only the
    // writer uses it, as the base of the next snapshot.
    private StorageSnapshot2 lastCommitted;
    // The snapshot that the next move of the tick publishes: the one of the last commit of
    // the current tick, or null if the tick has no commit. Written by the writer, read and
    // cleared by the thread that moves the tick.
    private StorageSnapshot2 pending;
    // The number of the last commit in the sequence. Only the writer uses it.
    private long commitSequence;
    // Acquisition number -> acquired snapshot with its timeout. Any thread acquires, reads
    // and releases: the map makes each of these steps atomic, and a query keeps its own
    // reference to the snapshot, so a release or an expiry during the query does not stop it.
    private final ConcurrentHashMap<Long, AcquisitionEntry2> acquisitions = new ConcurrentHashMap<>();
    // The number of the last acquisition. Numbers start from 1 and are never used again.
    private final AtomicLong lastAcquisitionId = new AtomicLong();
    // The published snapshots that did not end yet. The move of the tick that publishes a
    // snapshot adds one; the thread that ends a snapshot, any thread, removes one. Only a
    // published snapshot can end, and after the first move of the tick the last snapshot is
    // always in the window until the mark of removal, so the count reaches 0 only after the
    // mark, and only once.
    private final AtomicInteger liveSnapshots = new AtomicInteger();
    // Written once by the writer, in markRemoved(), before the window closes. Read by the
    // threads that acquire: an acquisition that finds its snapshot out of the window after
    // the mark sees the mark too.
    private volatile boolean removed;
    /** True after an insertion or a removal not yet in a snapshot. */
    private boolean idsChanged;
    private final CoordinateLimits limits;
    private final SpatialIndex2 index;
    private final MonotonicClock clock;

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
     * and the default number of retained ticks. The factory gets this storage,
     * so the index can read it during the queries.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory) {
        this(limits, indexFactory, RetentionWindow.DEFAULT_RETAINED_TICKS);
    }

    /**
     * Creates a storage with the given limits, the index made by the factory and
     * retainedTicks retained ticks. The factory gets this storage, so the index can
     * read it during the queries.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory,
            int retainedTicks) {
        this(limits, indexFactory, retainedTicks, MonotonicClock.SYSTEM);
    }

    /**
     * Creates a storage with the given limits, the index made by the factory,
     * retainedTicks retained ticks and the clock of the timeouts. The factory
     * gets this storage, so the index can read it during the queries.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory,
            int retainedTicks, MonotonicClock clock) {
        this(limits, indexFactory, retainedTicks, clock, 0);
    }

    /**
     * Creates a storage at the given tick with the given limits, the index made by the factory,
     * retainedTicks retained ticks and the clock of the timeouts. The factory gets this
     * storage, so the index can read it during the queries.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory,
            int retainedTicks, MonotonicClock clock, long creationTick) {
        this.clock = clock;
        this.limits = limits;
        this.index = indexFactory.apply(this);
        this.catalog = SnapshotCatalog2.empty(creationTick, retainedTicks);
        // As if the partition had a commit at its creation tick: the first move of the tick
        // publishes the empty snapshot, unless a commit replaces it.
        this.lastCommitted = StorageSnapshot2.empty();
        this.pending = lastCommitted;
    }

    void insertAll(List<Entity2> entities) {
        Set<Long> seen = new HashSet<>();
        for (Entity2 e : entities) {
            if (slotById.contains(e.id()) || !seen.add(e.id())) {
                throw new IllegalArgumentException("Duplicate entity ID: " + e.id());
            }
            checkLimits(e);
        }
        ensureCapacity(size + entities.size());
        for (Entity2 e : entities) {
            int slot = size++;
            slotEntityIds[slot] = e.id();
            write(slot, e.position());
            slotById.put(e.id(), slot);
            idsChanged = true;
            index.inserted(slot, e.position());
        }
    }

    List<Entity2> getAll(long[] entityIds) {
        List<Entity2> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            int slot = slotById.get(id);
            if (slot != SlotTable.NONE) {
                result.add(new Entity2(id, positionAt(slot)));
            }
        }
        return result;
    }

    void updateAll(List<Entity2> entities) {
        for (Entity2 e : entities) {
            slotOf(e.id());
            checkLimits(e);
        }
        for (Entity2 e : entities) {
            int slot = slotById.get(e.id());
            Point2 from = positionAt(slot);
            write(slot, e.position());
            index.moved(slot, from, e.position());
        }
    }

    void removeAll(long[] entityIds) {
        Set<Long> seen = new HashSet<>();
        for (long id : entityIds) {
            slotOf(id);
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Duplicate entity ID: " + id);
            }
        }
        for (long id : entityIds) {
            remove(id);
        }
    }

    List<Entity2> findInRegion(Region2 region) {
        return entitiesAt(index.findInRegion(region));
    }

    List<Entity2> findNearest(Point2 point, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        if (count == 0) {
            return List.of();
        }
        return entitiesAt(index.findNearest(point, count));
    }

    @Override
    public int size() {
        return size;
    }

    CoordinateLimits limits() {
        return limits;
    }

    /** Returns k: the number of retained ticks. */
    int retainedTicks() {
        return catalog.retainedTicks();
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
     * Confirms the writes in the current tick and returns the snapshot of the commit. The next
     * move of the tick publishes it, unless a later commit in the same tick replaces it. It
     * also removes the expired acquisitions. Only the writer can call it.
     */
    StorageSnapshot2 commit() {
        commitSequence++;
        StorageSnapshot2 snapshot = new StorageSnapshot2(commitSequence, slotSnapshot(), index.commit(commitSequence));
        idsChanged = false;
        lastCommitted = snapshot;
        pending = snapshot;
        removeExpired();
        return snapshot;
    }

    /**
     * Moves the tick of the partition forward to the given tick. The move publishes the snapshot
     * of the last commit of the current tick, if there is one, as the snapshot of the current
     * tick; the ticks without a commit keep the snapshot before. The snapshots whose ticks are
     * all before the new window leave it, and the ones with no uses end. It also removes the
     * expired acquisitions. The same tick again does nothing. Returns true if this finishes a
     * partition marked as removed. Fails with IllegalArgumentException if the tick is less than
     * the tick of the partition. Only the thread that moves the tick of the node calls it.
     */
    boolean setTick(long tick) {
        SnapshotCatalog2 current = catalog;
        if (tick < current.tick()) {
            throw new IllegalArgumentException("Tick " + tick + " is less than the tick " + current.tick());
        }
        if (tick == current.tick()) {
            return false;
        }
        StorageSnapshot2 published = pending;
        pending = null;
        SnapshotCatalog2 next = current.advance(tick, published);
        if (published != null) {
            liveSnapshots.incrementAndGet();
        }
        // The publication of the new tick and window.
        catalog = next;
        // After the publication, so a new acquisition finds the snapshot either in the
        // window or out of it, and in that case it chooses again from the new window.
        int kept = next.size() - (published != null ? 1 : 0);
        boolean finished = false;
        for (int i = 0; i < current.size() - kept; i++) {
            if (current.snapshotAt(i).uses().leaveWindow()) {
                finished |= snapshotEnded();
            }
        }
        return removeExpired() | finished;
    }

    /**
     * Marks the partition as removed and closes the window: every snapshot in the window
     * leaves it, and the snapshots with no uses end. After this the node allows no writes,
     * commits or new acquisitions; the acquisitions made before stay readable until they
     * are released or expire. Returns true if the partition is finished: no snapshot is
     * left. Only the writer can call it, once.
     */
    boolean markRemoved() {
        removed = true;
        pending = null;
        SnapshotCatalog2 closing = catalog;
        catalog = closing.closed();
        // With no published snapshot there are no acquisitions either: the partition is finished.
        boolean finished = closing.size() == 0;
        for (int i = 0; i < closing.size(); i++) {
            if (closing.snapshotAt(i).uses().leaveWindow()) {
                finished |= snapshotEnded();
            }
        }
        return finished;
    }

    /** Returns true if the partition is marked as removed. Any thread can call it. */
    boolean isRemoved() {
        return removed;
    }

    /**
     * Returns the catalog of the current tick, with the snapshots in the retention
     * window. Unlike the other methods, any thread can call it.
     */
    SnapshotCatalog2 catalog() {
        return catalog;
    }

    /**
     * Returns the last published snapshot, of the tick before the current one, or null before
     * the first move of the tick. Unlike the other methods, any thread can call it.
     */
    StorageSnapshot2 lastSnapshot() {
        return catalog.latest();
    }

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
     * Returns NOT_ACQUIRED, and uses no number, if a commit made the snapshot leave the
     * window after the choice: the caller chooses again from the new catalog, or fails if
     * the partition is {@linkplain #isRemoved() removed}. It also
     * removes the expired acquisitions. Any thread can call it.
     */
    long acquire(StorageSnapshot2 snapshot, long tick, AcquireOptions options) {
        // Checking the window and adding the use is a single step, so a snapshot that
        // left the window is never acquired again.
        if (!snapshot.uses().addUseIfInWindow()) {
            return NOT_ACQUIRED;
        }
        long acquisitionId = lastAcquisitionId.incrementAndGet();
        acquisitions.put(acquisitionId, AcquisitionEntry2.of(snapshot, tick, options, clock.nanoTime()));
        // The new acquisition keeps its snapshot, so this cannot finish the partition.
        removeExpired();
        return acquisitionId;
    }

    /**
     * Admits a query: returns the acquired snapshot, which the query then reads, and adds
     * a use to it. The query must call {@link #endQuery} when it ends, also if it fails.
     * Checking the acquisition and taking the snapshot is a single step: the query can end
     * also if the acquisition is released or expires in the meantime. Fails with
     * IllegalStateException if the acquisition was released or is expired, and with
     * IllegalArgumentException if this storage never made it or if its tick is not the given
     * tick. Any thread can call it.
     */
    StorageSnapshot2 admit(long acquisitionId, long tick) {
        // A single read of the entry: a removal at the same time cannot make the
        // check and the snapshot disagree.
        AcquisitionEntry2 entry = acquisitions.get(acquisitionId);
        if (entry == null || entry.isExpired(clock.nanoTime())) {
            throw missingAcquisition(acquisitionId);
        }
        if (entry.tick() != tick) {
            throw new IllegalArgumentException("Acquisition " + acquisitionId + " is of tick " + entry.tick()
                    + ", not " + tick);
        }
        // A release at the same time can end the snapshot after the read of the entry:
        // then the query fails, as after the release.
        if (!entry.snapshot().uses().addUseIfNotEnded()) {
            throw missingAcquisition(acquisitionId);
        }
        return entry.snapshot();
    }

    /**
     * Ends a query admitted by {@link #admit}: removes its use, which can end the
     * snapshot. Returns true if this finishes the partition. Any thread can call it.
     */
    boolean endQuery(StorageSnapshot2 snapshot) {
        return removeUse(snapshot);
    }

    /**
     * Releases an acquisition: removes its use, which can end the snapshot. Releasing it
     * again, or after its timeout, does nothing. It also removes the expired acquisitions.
     * Returns true if this finishes the partition. Fails with IllegalArgumentException if
     * this storage never made it. Any thread can call it.
     */
    boolean release(long acquisitionId) {
        checkMade(acquisitionId);
        boolean finished = false;
        // Only one thread gets the entry: a second release or the removal after the
        // timeout finds nothing, so the use is removed once.
        AcquisitionEntry2 entry = acquisitions.remove(acquisitionId);
        if (entry != null) {
            finished = removeUse(entry.snapshot());
        }
        return removeExpired() | finished;
    }

    /**
     * Returns the number of acquisitions not yet released or removed after the timeout:
     * an expired acquisition counts until the next acquisition, release, commit or move of the tick.
     * Any thread can call it.
     */
    int acquisitionCount() {
        return acquisitions.size();
    }

    /**
     * Returns the number of published snapshots that did not end yet: the snapshots in the
     * window and the ones out of it that still have uses. Any thread can call it.
     */
    int liveSnapshotCount() {
        return liveSnapshots.get();
    }

    @Override
    public long idAt(int slot) {
        return slotEntityIds[slot];
    }

    @Override
    public Point2 positionAt(int slot) {
        int base = slot * DIMENSIONS;
        return new Point2(coords[base], coords[base + 1]);
    }

    private SlotSnapshot2 slotSnapshot() {
        double[] coordsCopy = Arrays.copyOf(coords, size * DIMENSIONS);
        if (!idsChanged) {
            SlotSnapshot2 last = lastCommitted.slots();
            return new SlotSnapshot2(last.slotEntityIds, coordsCopy, last.slotById);
        }
        return new SlotSnapshot2(Arrays.copyOf(slotEntityIds, size), coordsCopy, slotById.copy());
    }

    /**
     * Removes the expired acquisitions and their uses, which can end their snapshots.
     * Returns true if this finishes the partition. The reader of an expired acquisition
     * may never come back, so the storage does not wait for it. It scans all the
     * acquisitions; the map does not block the other threads. Any thread can call it:
     * the node calls it for the partitions marked as removed, which have no events of
     * their own.
     */
    boolean removeExpired() {
        boolean finished = false;
        long now = clock.nanoTime();
        for (Map.Entry<Long, AcquisitionEntry2> e : acquisitions.entrySet()) {
            // remove(key, value) succeeds for one thread only, like the release.
            if (e.getValue().isExpired(now) && acquisitions.remove(e.getKey(), e.getValue())) {
                finished |= removeUse(e.getValue().snapshot());
            }
        }
        return finished;
    }

    /** Removes a use of the snapshot. Returns true if this finishes the partition. */
    private boolean removeUse(StorageSnapshot2 snapshot) {
        return snapshot.uses().removeUse() && snapshotEnded();
    }

    /**
     * The point where a snapshot ends: nobody uses it and nobody can ask for it again.
     * Called once for each snapshot, by the thread that ends it. Returns true if it was
     * the last snapshot: the partition is finished.
     */
    private boolean snapshotEnded() {
        return liveSnapshots.decrementAndGet() == 0;
    }

    private void checkMade(long acquisitionId) {
        if (!isMade(acquisitionId)) {
            throw new IllegalArgumentException("Unknown acquisition: " + acquisitionId);
        }
    }

    /**
     * Returns the error for an acquisition that admit cannot find: IllegalArgumentException
     * if this storage never made it, IllegalStateException if it was released or is expired.
     * After the removal of an expired entry the storage cannot tell the two cases apart.
     */
    private RuntimeException missingAcquisition(long acquisitionId) {
        if (!isMade(acquisitionId)) {
            return new IllegalArgumentException("Unknown acquisition: " + acquisitionId);
        }
        return new IllegalStateException("Acquisition was released or is expired: " + acquisitionId);
    }

    /** Returns true if this storage made the acquisition: its number was given by acquire. */
    private boolean isMade(long acquisitionId) {
        return acquisitionId >= 1 && acquisitionId <= lastAcquisitionId.get();
    }

    private void remove(long entityId) {
        int slot = slotById.remove(entityId);
        idsChanged = true;
        Point2 position = positionAt(slot);
        int last = size - 1;
        if (slot != last) {
            slotEntityIds[slot] = slotEntityIds[last];
            System.arraycopy(coords, last * DIMENSIONS, coords, slot * DIMENSIONS, DIMENSIONS);
            slotById.put(slotEntityIds[slot], slot);
        }
        size--;
        index.removed(slot, position);
        if (slot != last) {
            index.relocated(last, slot, positionAt(slot));
        }
    }

    private void checkLimits(Entity2 e) {
        if (!limits.contains(e.position())) {
            throw new IllegalArgumentException("Position of entity " + e.id() + " is outside the limits "
                    + limits.min() + ", " + limits.max() + ": " + e.position());
        }
    }

    private List<Entity2> entitiesAt(int[] slots) {
        List<Entity2> result = new ArrayList<>(slots.length);
        for (int slot : slots) {
            result.add(new Entity2(slotEntityIds[slot], positionAt(slot)));
        }
        return result;
    }

    private int slotOf(long entityId) {
        int slot = slotById.get(entityId);
        if (slot == SlotTable.NONE) {
            throw new NoSuchElementException("Unknown entity ID: " + entityId);
        }
        return slot;
    }

    private void write(int slot, Point2 p) {
        int base = slot * DIMENSIONS;
        coords[base] = p.x();
        coords[base + 1] = p.y();
    }

    private void ensureCapacity(int needed) {
        if (needed <= slotEntityIds.length) {
            return;
        }
        int capacity = slotEntityIds.length;
        while (capacity < needed) {
            capacity *= 2;
        }
        slotEntityIds = Arrays.copyOf(slotEntityIds, capacity);
        coords = Arrays.copyOf(coords, capacity * DIMENSIONS);
    }
}
