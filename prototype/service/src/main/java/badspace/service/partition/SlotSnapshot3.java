package badspace.service.partition;

import badspace.common.geometry.Point3;
import badspace.service.index.SlotView3;

/**
 * The slots of a 3D partition at a commit. It never changes while it is in use, so
 * many threads can read it while the writer goes on. The IDs are as long as the slots;
 * the coordinates are in a buffer of the storage, which can be longer.
 * <p>
 * The coordinates are in a {@link CoordinateBuffer} from the pool of the storage: each
 * commit with changes of the coordinates copies the changed pages into a buffer, a commit
 * without them shares the buffer of the snapshot before it. The snapshot is a user of its
 * buffer, which goes back to the pool when the last snapshot that uses it ends. The IDs and the table from ID to
 * slot change only with insertions and removals, so a commit without them
 * shares the arrays and the table of the snapshot before it.
 */
final class SlotSnapshot3 implements SlotView3 {

    private static final int DIMENSIONS = 3;

    private final int size;
    // Slot: entity ID and position, as in the storage. coords holds DIMENSIONS values
    // per slot (x, y, z), so slot i is slotEntityIds[i] and coords[DIMENSIONS * i ...].
    // coords holds at least DIMENSIONS * size values; the ones after them mean nothing.
    private final long[] slotEntityIds;
    private final double[] coords;
    private final CoordinateBuffer coordinateBuffer;
    private final SlotTable slotById;

    /** Creates a snapshot that becomes a user of the coordinate buffer. */
    SlotSnapshot3(long[] slotEntityIds, CoordinateBuffer coordinateBuffer, SlotTable slotById) {
        this.size = slotEntityIds.length;
        this.slotEntityIds = slotEntityIds;
        this.coords = coordinateBuffer.values();
        this.coordinateBuffer = coordinateBuffer;
        this.slotById = slotById;
        coordinateBuffer.addUser();
    }

    /**
     * Returns the buffer of the coordinates, so the storage can share it with the next
     * snapshot, or put it back in its pool when the last snapshot that uses it ends.
     */
    CoordinateBuffer coordinateBuffer() {
        return coordinateBuffer;
    }

    /** Creates a snapshot that shares the unchanged IDs and uses the given coordinates. */
    SlotSnapshot3 withCoordinates(CoordinateBuffer coordinates) {
        return new SlotSnapshot3(slotEntityIds, coordinates, slotById);
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
