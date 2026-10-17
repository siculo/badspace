package badspace.service.partition.snapshot;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.Entity2;
import badspace.service.index.IndexSnapshot2;
import badspace.service.partition.slots.SlotSnapshot2;
import java.util.ArrayList;
import java.util.List;

/**
 * A 2D partition at a commit: the slots and the index of the same commit. The
 * storage makes a snapshot at each commit, and the next move of the tick publishes
 * it to the readers as the snapshot of the tick of the commit, and of the following
 * ticks without a commit.
 * Its data do not change while it has uses, so many threads can read it at the same
 * time while the writer goes on. The readers outside the node reach it only through an
 * acquisition. Its {@linkplain #uses() uses} tell when the snapshot ends: then the buffer
 * of its coordinates goes to a later commit, so a thread reads it only while it holds a
 * use, an acquisition or an admitted query.
 */
public final class StorageSnapshot2 {

    private final SlotSnapshot2 slots;
    private final IndexSnapshot2 index;
    private final SnapshotUses uses = new SnapshotUses();

    /** Creates a snapshot from the slots and index of the same commit. */
    public StorageSnapshot2(SlotSnapshot2 slots, IndexSnapshot2 index) {
        this.slots = slots;
        this.index = index;
    }

    /** Returns the empty snapshot of a new partition, before the first commit. */
    public static StorageSnapshot2 empty() {
        SlotSnapshot2 slots = SlotSnapshot2.empty();
        return new StorageSnapshot2(slots, IndexSnapshot2.empty());
    }

    /** Returns the slots. The caller must hold a use while it reads them. */
    public SlotSnapshot2 slots() {
        return slots;
    }

    IndexSnapshot2 index() {
        return index;
    }

    /** Returns the uses of the snapshot and its place in the retention window. */
    SnapshotUses uses() {
        return uses;
    }

    /** Returns whether the snapshot ended. Any thread can call it. */
    public boolean isEnded() {
        return uses.isEnded();
    }

    public int size() {
        return slots.size();
    }

    public List<Entity2> getAll(long[] entityIds) {
        List<Entity2> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            int slot = slots.slotOf(id);
            if (slot != SlotSnapshot2.NONE) {
                result.add(new Entity2(id, slots.positionAt(slot)));
            }
        }
        return result;
    }

    public List<Entity2> findInRegion(Region2 region) {
        return entitiesAt(index.findInRegion(region, slots));
    }

    public List<Entity2> findNearest(Point2 point, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        if (count == 0) {
            return List.of();
        }
        return entitiesAt(index.findNearest(point, count, slots));
    }

    private List<Entity2> entitiesAt(int[] found) {
        List<Entity2> result = new ArrayList<>(found.length);
        for (int slot : found) {
            result.add(new Entity2(slots.idAt(slot), slots.positionAt(slot)));
        }
        return result;
    }
}
