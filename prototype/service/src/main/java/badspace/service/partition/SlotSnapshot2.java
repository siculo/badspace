package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.service.index.SlotView2;

/**
 * The slots of a 2D partition at a commit. It never changes, so many threads
 * can read it while the writer goes on. The arrays are as long as the slots,
 * without the free capacity of the writer.
 * <p>
 * The coordinates are copied at each commit. The IDs and the table from ID to
 * slot change only with insertions and removals, so a commit without them
 * shares the arrays and the table of the snapshot before it.
 */
final class SlotSnapshot2 implements SlotView2 {

    private static final int DIMENSIONS = 2;

    private final int size;
    /** Package-private so that the tests can check what the snapshots share. */
    final long[] ids;
    final double[] coords;
    final SlotTable slotById;

    SlotSnapshot2(long[] ids, double[] coords, SlotTable slotById) {
        this.size = ids.length;
        this.ids = ids;
        this.coords = coords;
        this.slotById = slotById;
    }

    /** Returns the slot of the ID, or {@link SlotTable#NONE} if the ID is not in the snapshot. */
    int slotOf(long id) {
        return slotById.get(id);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public long idAt(int slot) {
        return ids[slot];
    }

    @Override
    public Point2 positionAt(int slot) {
        int base = slot * DIMENSIONS;
        return new Point2(coords[base], coords[base + 1]);
    }
}
