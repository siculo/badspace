package badspace.service.partition.slots;

import badspace.common.geometry.Point2;
import badspace.service.index.SlotView2;
import badspace.service.transaction.TransactionalComponent;

/**
 * The slots of the writer of a 2D partition: the state that the next commit confirms.
 * Entities are stored in primitive arrays: slotEntityIds[i] has coordinates coords[2*i],
 * coords[2*i+1]. A {@link SlotTable} gives the slot of each ID. A removal moves the last
 * entity into the free slot, so the arrays stay compact and the order of the entities can
 * change.
 * <p>
 * The spatial index of the partition reads the slots through {@link SlotView2}; the slots do
 * not know the index. The partition changes the slots and then tells the index, so the
 * methods let it read what it needs before and after a change: the slot of an ID, the
 * position in a slot and the size.
 * <p>
 * The slots are grouped in pages of PAGE_SLOTS slots, and each page keeps the commit that
 * confirms its last change. The methods that change slots get that commit from the partition,
 * which owns the commit sequence. The snapshot of a commit copies only the pages changed after
 * the commit of the buffer it gets, and shares the IDs and the table of the last snapshot when
 * no insertion or removal happened.
 * <p>
 * The slots are a {@link TransactionalComponent}: {@link #prepareCommit} makes the snapshot
 * of a commit, {@link #acceptCommit} makes it the base of the next one, {@link #abandonCommit}
 * gives back the buffer of a snapshot that the commit does not accept, and {@link #restore}
 * brings the slots back to a snapshot with the same numbering, so the index can go back to
 * its own snapshot of the same commit.
 * <p>
 * Only the writer uses the slots, while it holds the lock of the partition: the slots take no
 * lock of their own. The snapshots they make never change, so other threads can read them.
 */
public final class MutableSlots2 implements SlotView2, TransactionalComponent<SlotSnapshot2> {

    private static final int DIMENSIONS = 2;
    private static final int INITIAL_CAPACITY = 16;
    /** The slots in a page: a commit copies the coordinates of a changed page as a whole. */
    public static final int PAGE_SLOTS = 64;

    // Slot: entity ID and position. coords holds DIMENSIONS values per slot (x, y),
    // so slot i is slotEntityIds[i] and coords[DIMENSIONS * i ...]. The capacity of
    // coords is always DIMENSIONS times the capacity of slotEntityIds.
    private long[] slotEntityIds = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private SlotTable slotById = new SlotTable();
    private int size;
    // The pool of the partition, where the commits take the buffers of the coordinates.
    private final CoordinateBufferPool coordinateBuffers;
    // Makes the arrays of the growth and of the snapshots.
    private final SlotArrays arrays;
    // The snapshot of the last accepted commit, or of the last restore: the next snapshot
    // shares its IDs, its table and its buffer when they do not change.
    private SlotSnapshot2 lastSnapshot = SlotSnapshot2.empty();
    // For each page of slots, the commit sequence of the commit that confirms its last change:
    // page p has the slots from p * PAGE_SLOTS.
    private long[] pageChanges = new long[pageCount(INITIAL_CAPACITY)];
    // The pages that the commits copied into a buffer since the creation.
    private long copiedPages;
    /** True after an insertion or a removal not yet in a snapshot. */
    private boolean idsChanged;
    /** True after a change of the coordinates not yet in a snapshot. */
    private boolean coordinatesChanged;

    /**
     * Creates empty slots for the writer.
     *
     * @param coordinateBuffers the pool of the partition, where the commits take the buffers
     *     of the coordinates
     */
    public MutableSlots2(CoordinateBufferPool coordinateBuffers) {
        this(coordinateBuffers, SlotArrays.DEFAULT);
    }

    /**
     * Creates empty slots for the writer that make their arrays with the given arrays. The
     * tests give arrays that fail.
     */
    MutableSlots2(CoordinateBufferPool coordinateBuffers, SlotArrays arrays) {
        this.coordinateBuffers = coordinateBuffers;
        this.arrays = arrays;
    }

    @Override
    public int size() {
        return size;
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

    /** Returns the slot of the ID, or {@link SlotSnapshot2#NONE} if the ID is not in the slots. */
    public int slotOf(long entityId) {
        return slotById.get(entityId);
    }

    /** Returns true if there are writes after the last accepted snapshot or restore. */
    public boolean hasChanges() {
        return idsChanged || coordinatesChanged;
    }

    /** Returns true if the coordinates changed after the last accepted snapshot or restore. */
    public boolean coordinatesChanged() {
        return coordinatesChanged;
    }

    /** Makes room for at least needed slots, so the next insertions do not grow the arrays. */
    public void ensureCapacity(int needed) {
        if (needed <= slotEntityIds.length) {
            return;
        }
        int capacity = slotEntityIds.length;
        while (capacity < needed) {
            capacity *= 2;
        }
        // All the arrays are made before any of them replaces the old one: if an allocation
        // fails, the slots keep their old capacity, which the restore and the next growth trust.
        long[] grownIds = arrays.newLongs(capacity);
        double[] grownCoords = arrays.newDoubles(capacity * DIMENSIONS);
        long[] grownPageChanges = arrays.newLongs(pageCount(capacity));
        System.arraycopy(slotEntityIds, 0, grownIds, 0, slotEntityIds.length);
        System.arraycopy(coords, 0, grownCoords, 0, coords.length);
        System.arraycopy(pageChanges, 0, grownPageChanges, 0, pageChanges.length);
        slotEntityIds = grownIds;
        coords = grownCoords;
        pageChanges = grownPageChanges;
    }

    /**
     * Adds an entity, whose ID must not be in the slots, in a new slot after the last one and
     * returns the slot. The commit is the one that confirms the change.
     */
    public int insert(long entityId, Point2 position, long commit) {
        ensureCapacity(size + 1);
        int slot = size++;
        slotEntityIds[slot] = entityId;
        write(slot, position, commit);
        slotById.put(entityId, slot);
        idsChanged = true;
        return slot;
    }

    /** Sets the position in a slot. The commit is the one that confirms the change. */
    public void write(int slot, Point2 position, long commit) {
        changed(slot, commit);
        int base = slot * DIMENSIONS;
        coords[base] = position.x();
        coords[base + 1] = position.y();
    }

    /**
     * Removes an entity, whose ID must be in the slots. If its slot is not the last one, the
     * entity of the last slot moves into it: then that slot has a new ID and position, and the
     * last slot is free. The commit is the one that confirms the change.
     */
    public void remove(long entityId, long commit) {
        int slot = slotById.remove(entityId);
        idsChanged = true;
        int last = size - 1;
        if (slot != last) {
            slotEntityIds[slot] = slotEntityIds[last];
            System.arraycopy(coords, last * DIMENSIONS, coords, slot * DIMENSIONS, DIMENSIONS);
            changed(slot, commit);
            slotById.put(slotEntityIds[slot], slot);
        }
        size--;
    }

    /**
     * Returns the snapshot of the slots for the commit. If the coordinates changed, the
     * snapshot gets a buffer from the pool with the pages changed after the commit of the
     * buffer; otherwise it shares the buffer of the last accepted snapshot. Without insertions
     * or removals, it shares the IDs and the table of the last accepted snapshot.
     */
    @Override
    public SlotSnapshot2 prepareCommit(long commitSequence) {
        boolean taken = coordinatesChanged;
        CoordinateBuffer coordinates = taken ? copyChangedPages(commitSequence) : lastSnapshot.coordinateBuffer();
        try {
            if (!idsChanged) {
                return lastSnapshot.withCoordinates(coordinates);
            }
            long[] ids = arrays.newLongs(size);
            System.arraycopy(slotEntityIds, 0, ids, 0, size);
            return new SlotSnapshot2(ids, coordinates, slotById.copy());
        } catch (Throwable failure) {
            // No snapshot uses the buffer just taken, so the storage cannot abandon it: it goes
            // back to the pool here. The buffer of the last snapshot stays with its users.
            if (taken) {
                giveBackUnused(coordinates, failure);
            }
            throw failure;
        }
    }

    /**
     * Puts back in the pool a buffer that no snapshot uses, as a buffer that holds no commit:
     * its pages can be newer than the pages of the slots after a restore. A failure here goes
     * with the failure of the preparation, which the caller gets.
     */
    private void giveBackUnused(CoordinateBuffer coordinates, Throwable failure) {
        try {
            coordinates.holdCommit(CoordinateBuffer.NO_COMMIT);
            coordinateBuffers.giveBack(coordinates);
        } catch (Throwable giveBackFailure) {
            suppress(failure, giveBackFailure);
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

    /** Records that the slots have no changes after the snapshot, which the next one can share. */
    @Override
    public void acceptCommit(SlotSnapshot2 prepared) {
        idsChanged = false;
        coordinatesChanged = false;
        lastSnapshot = prepared;
    }

    /**
     * Removes the snapshot from the users of its buffer. A buffer that the snapshot took from
     * the pool has no other users, so it goes back to the pool as a buffer that holds no
     * commit: the slots can go back to an older commit without marking the pages that change,
     * so the next commit must copy all the pages into it. A buffer shared with the last
     * accepted snapshot keeps its other users.
     */
    @Override
    public void abandonCommit(SlotSnapshot2 prepared) {
        CoordinateBuffer coordinates = prepared.coordinateBuffer();
        if (coordinates.removeUser()) {
            coordinates.holdCommit(CoordinateBuffer.NO_COMMIT);
            coordinateBuffers.giveBack(coordinates);
        }
    }

    /**
     * Copies the IDs, the coordinates and the table of the snapshot, so each entity goes
     * back to its slot in the snapshot. It does nothing if the snapshot is the last one and
     * there are no writes after it. The pages keep the commit of their last change: a page
     * changed after the snapshot holds its content again, and the next commit copies it,
     * like after a write. The snapshot does not need to come from these slots.
     */
    @Override
    public void restore(SlotSnapshot2 confirmed) {
        if (confirmed == lastSnapshot && !hasChanges()) {
            return;
        }
        int count = confirmed.size();
        ensureCapacity(count);
        confirmed.copyInto(slotEntityIds, coords);
        slotById = confirmed.copyOfTable();
        size = count;
        idsChanged = false;
        coordinatesChanged = false;
        lastSnapshot = confirmed;
    }

    /**
     * Returns a buffer from the pool with the coordinates of the slots at the given commit.
     * It copies into the buffer the pages of slots changed after the commit of the state it
     * holds, and records that it holds the given commit. A new buffer holds no commit, so it
     * gets all the pages. The slots that the buffer did not have were inserted after its
     * commit, so their pages are changed too.
     */
    private CoordinateBuffer copyChangedPages(long commit) {
        CoordinateBuffer buffer = coordinateBuffers.take(size * DIMENSIONS, coords.length);
        double[] values = buffer.values();
        long held = buffer.commitSequence();
        int pages = pageCount(size);
        for (int page = 0; page < pages; page++) {
            if (pageChanges[page] > held) {
                int from = page * PAGE_SLOTS * DIMENSIONS;
                int length = (Math.min(size, (page + 1) * PAGE_SLOTS) - page * PAGE_SLOTS) * DIMENSIONS;
                System.arraycopy(coords, from, values, from, length);
                copiedPages++;
            }
        }
        buffer.holdCommit(commit);
        return buffer;
    }

    /** Returns the number of pages that the snapshots copied into the buffers since the creation. */
    public long copiedPageCount() {
        return copiedPages;
    }

    /** Records that the coordinates of the slot change in the given commit. */
    private void changed(int slot, long commit) {
        pageChanges[slot / PAGE_SLOTS] = commit;
        coordinatesChanged = true;
    }

    /** Returns the number of pages for the given number of slots. */
    private static int pageCount(int slots) {
        return (slots + PAGE_SLOTS - 1) / PAGE_SLOTS;
    }
}
