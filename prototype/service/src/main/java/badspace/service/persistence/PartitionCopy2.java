package badspace.service.persistence;

import badspace.common.geometry.Point2;
import java.util.List;
import java.util.Map;

/**
 * An autonomous copy of the content of a 2D partition snapshot: the IDs, the positions and
 * the application metadata of its entities, at a tick. The node makes it as an internal
 * reader, while it holds the snapshot, and then it does not depend on the life of the
 * snapshot any more: the copy owns its data and nothing changes them. The persistence writes
 * it to a save, and the load builds a partition back from it.
 * <p>
 * The copy is immutable and its data have one value per entity, at the same index: entity i
 * has {@link #entityId(int) entityId(i)}, {@link #position(int) position(i)} and
 * {@link #metadata(int) metadata(i)}.
 */
public final class PartitionCopy2 {

    private static final int DIMENSIONS = 2;

    private final long tick;
    // Entity: id, position and metadata. entityIds has one value per entity; coords has two
    // per entity (x, y), so entity i is entityIds[i] and coords[2 * i], coords[2 * i + 1];
    // metadata has one map per entity. All have the same entity at the same index.
    private final long[] entityIds;
    private final double[] coords;
    private final List<Map<String, Long>> metadata;

    /**
     * Creates a copy that owns the arrays and the list: the caller must not change them after.
     * entityIds and metadata must have the same length, and coords must have twice that length.
     */
    public PartitionCopy2(long tick, long[] entityIds, double[] coords, List<Map<String, Long>> metadata) {
        if (coords.length != DIMENSIONS * entityIds.length || metadata.size() != entityIds.length) {
            throw new IllegalArgumentException("Mismatched lengths: " + entityIds.length + " entities, "
                    + coords.length + " coordinates, " + metadata.size() + " metadata");
        }
        this.tick = tick;
        this.entityIds = entityIds;
        this.coords = coords;
        this.metadata = metadata;
    }

    /** Returns the tick of the snapshot of the copy. */
    public long tick() {
        return tick;
    }

    /** Returns the number of entities in the copy. */
    public int size() {
        return entityIds.length;
    }

    /** Returns the ID of the entity at the given index. */
    public long entityId(int index) {
        return entityIds[index];
    }

    /** Returns the position of the entity at the given index. */
    public Point2 position(int index) {
        int base = DIMENSIONS * index;
        return new Point2(coords[base], coords[base + 1]);
    }

    /** Returns the application metadata of the entity at the given index, empty if it has none. */
    public Map<String, Long> metadata(int index) {
        return metadata.get(index);
    }
}
