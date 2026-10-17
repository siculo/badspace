package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.service.index.SlotView2;
import java.util.Arrays;

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
 * Only the writer uses the slots, while it holds the lock of the partition: the slots take no
 * lock of their own. The snapshots they make never change, so other threads can read them.
 */
final class MutableSlots2 implements SlotView2 {

    private static final int DIMENSIONS = 2;
    private static final int INITIAL_CAPACITY = 16;
    /** The slots in a page: a commit copies the coordinates of a changed page as a whole. */
    static final int PAGE_SLOTS = 64;

    // Slot: entity ID and position. coords holds DIMENSIONS values per slot (x, y),
    // so slot i is slotEntityIds[i] and coords[DIMENSIONS * i ...]. The capacity of
    // coords is always DIMENSIONS times the capacity of slotEntityIds.
    private long[] slotEntityIds = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private final SlotTable slotById = new SlotTable();
    private int size;
    // For each page of slots, the commit sequence of the commit that confirms its last change:
    // page p has the slots from p * PAGE_SLOTS.
    private long[] pageChanges = new long[pageCount(INITIAL_CAPACITY)];
    // The pages that the commits copied into a buffer since the creation.
    private long copiedPages;
    /** True after an insertion or a removal not yet in a snapshot. */
    private boolean idsChanged;
    /** True after a change of the coordinates not yet in a snapshot. */
    private boolean coordinatesChanged;

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

    /** Returns the slot of the ID, or {@link SlotTable#NONE} if the ID is not in the slots. */
    int slotOf(long entityId) {
        return slotById.get(entityId);
    }

    /** Returns true if there are writes after the last snapshot. */
    boolean hasChanges() {
        return idsChanged || coordinatesChanged;
    }

    /** Returns true if the coordinates changed after the last snapshot. */
    boolean coordinatesChanged() {
        return coordinatesChanged;
    }

    /** Makes room for at least needed slots, so the next insertions do not grow the arrays. */
    void ensureCapacity(int needed) {
        if (needed <= slotEntityIds.length) {
            return;
        }
        int capacity = slotEntityIds.length;
        while (capacity < needed) {
            capacity *= 2;
        }
        slotEntityIds = Arrays.copyOf(slotEntityIds, capacity);
        coords = Arrays.copyOf(coords, capacity * DIMENSIONS);
        pageChanges = Arrays.copyOf(pageChanges, pageCount(capacity));
    }

    /**
     * Adds an entity, whose ID must not be in the slots, in a new slot after the last one and
     * returns the slot. The commit is the one that confirms the change.
     */
    int insert(long entityId, Point2 position, long commit) {
        ensureCapacity(size + 1);
        int slot = size++;
        slotEntityIds[slot] = entityId;
        write(slot, position, commit);
        slotById.put(entityId, slot);
        idsChanged = true;
        return slot;
    }

    /** Sets the position in a slot. The commit is the one that confirms the change. */
    void write(int slot, Point2 position, long commit) {
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
    void remove(long entityId, long commit) {
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
     * Returns a buffer from the pool with the coordinates of the slots at the given commit.
     * It copies into the buffer the pages of slots changed after the commit of the state it
     * holds, and records that it holds the given commit. A new buffer holds no commit, so it
     * gets all the pages. The slots that the buffer did not have were inserted after its
     * commit, so their pages are changed too.
     */
    CoordinateBuffer copyChangedPages(CoordinateBufferPool pool, long commit) {
        CoordinateBuffer buffer = pool.take(size * DIMENSIONS, coords.length);
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

    /**
     * Returns the snapshot of the slots with the given coordinates, and records that the
     * slots have no changes after it. Without insertions or removals after the last snapshot,
     * the new one shares its IDs and its table.
     */
    SlotSnapshot2 snapshot(CoordinateBuffer coordinates, SlotSnapshot2 last) {
        SlotSnapshot2 snapshot = idsChanged
                ? new SlotSnapshot2(Arrays.copyOf(slotEntityIds, size), coordinates, slotById.copy())
                : last.withCoordinates(coordinates);
        idsChanged = false;
        coordinatesChanged = false;
        return snapshot;
    }

    /** Returns the number of pages that the snapshots copied into the buffers since the creation. */
    long copiedPageCount() {
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
