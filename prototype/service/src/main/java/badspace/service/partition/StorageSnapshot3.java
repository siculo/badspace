package badspace.service.partition;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.Entity3;
import badspace.service.index.IndexSnapshot3;
import java.util.ArrayList;
import java.util.List;

/**
 * A 3D partition at a commit: the slots and the index of the same commit. The
 * storage makes a snapshot at each commit, and the next move of the tick publishes
 * it to the readers as the snapshot of the tick of the commit, and of the following
 * ticks without a commit.
 * Its data do not change while it has uses, so many threads can read it at the same
 * time while the writer goes on. The readers outside the node reach it only through an
 * acquisition. Its {@linkplain #uses() uses} tell when the snapshot ends: then the buffer
 * of its coordinates goes to a later commit, so a thread reads it only while it holds a
 * use, an acquisition or an admitted query.
 */
final class StorageSnapshot3 {

    private final SlotSnapshot3 slots;
    private final IndexSnapshot3 index;
    private final SnapshotUses uses = new SnapshotUses();

    StorageSnapshot3(SlotSnapshot3 slots, IndexSnapshot3 index) {
        this.slots = slots;
        this.index = index;
    }

    /** Returns the empty snapshot of a new partition, before the first commit. */
    static StorageSnapshot3 empty() {
        SlotSnapshot3 slots = new SlotSnapshot3(new long[0], new double[0], new SlotTable());
        return new StorageSnapshot3(slots, IndexSnapshot3.empty());
    }

    SlotSnapshot3 slots() {
        return slots;
    }

    IndexSnapshot3 index() {
        return index;
    }

    /** Returns the uses of the snapshot and its place in the retention window. */
    SnapshotUses uses() {
        return uses;
    }

    public int size() {
        return slots.size();
    }

    public List<Entity3> getAll(long[] entityIds) {
        List<Entity3> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            int slot = slots.slotOf(id);
            if (slot != SlotTable.NONE) {
                result.add(new Entity3(id, slots.positionAt(slot)));
            }
        }
        return result;
    }

    public List<Entity3> findInRegion(Region3 region) {
        return entitiesAt(index.findInRegion(region, slots));
    }

    public List<Entity3> findNearest(Point3 point, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        if (count == 0) {
            return List.of();
        }
        return entitiesAt(index.findNearest(point, count, slots));
    }

    private List<Entity3> entitiesAt(int[] found) {
        List<Entity3> result = new ArrayList<>(found.length);
        for (int slot : found) {
            result.add(new Entity3(slots.idAt(slot), slots.positionAt(slot)));
        }
        return result;
    }
}
