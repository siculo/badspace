package badspace.service.partition;

import badspace.common.geometry.Point3;
import badspace.service.index.SlotView3;

/**
 * The slots of a 3D partition at a commit. It never changes, so many threads
 * can read it while the writer goes on. The arrays are as long as the slots,
 * without the free capacity of the writer.
 * <p>
 * The coordinates are copied at each commit. The IDs and the table from ID to
 * slot change only with insertions and removals, so a commit without them
 * shares the arrays and the table of the snapshot before it.
 */
final class SlotSnapshot3 implements SlotView3 {

    private static final int DIMENSIONS = 3;

    private final int size;
    // Slot: entity ID and position, as in the storage. coords holds DIMENSIONS values
    // per slot (x, y, z), so slot i is slotEntityIds[i] and coords[DIMENSIONS * i ...].
    /** Package-private so that the tests can check what the snapshots share. */
    final long[] slotEntityIds;
    final double[] coords;
    final SlotTable slotById;

    SlotSnapshot3(long[] slotEntityIds, double[] coords, SlotTable slotById) {
        this.size = slotEntityIds.length;
        this.slotEntityIds = slotEntityIds;
        this.coords = coords;
        this.slotById = slotById;
    }

    /** Returns the slot of the ID, or {@link SlotTable#NONE} if the ID is not in the snapshot. */
    int slotOf(long entityId) {
        return slotById.get(entityId);
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
    public Point3 positionAt(int slot) {
        int base = slot * DIMENSIONS;
        return new Point3(coords[base], coords[base + 1], coords[base + 2]);
    }
}
