package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.Entity2;
import badspace.service.index.IndexSnapshot2;
import java.util.ArrayList;
import java.util.List;

/**
 * A 2D partition at a commit: the slots and the index of the same commit. The
 * storage makes a snapshot at each commit, and the next move of the tick publishes
 * it to the readers as the snapshot of the tick of the commit, and of the following
 * ticks without a commit.
 * Its data never change, so many threads can read it at the same time while the
 * writer goes on. The readers outside the node reach it only through an
 * acquisition. Its {@linkplain #uses() uses} tell when the snapshot ends.
 */
final class StorageSnapshot2 {

    // The number of the commit in the sequence of the commits of the partition: it is
    // only for the copy-on-write of the index, and never leaves the node.
    private final long commitSequence;
    private final SlotSnapshot2 slots;
    private final IndexSnapshot2 index;
    private final SnapshotUses uses = new SnapshotUses();

    StorageSnapshot2(long commitSequence, SlotSnapshot2 slots, IndexSnapshot2 index) {
        this.commitSequence = commitSequence;
        this.slots = slots;
        this.index = index;
    }

    /** Returns the empty snapshot of a new partition, at commit sequence 0, before the first commit. */
    static StorageSnapshot2 empty() {
        SlotSnapshot2 slots = new SlotSnapshot2(new long[0], new double[0], new SlotTable());
        return new StorageSnapshot2(0, slots, IndexSnapshot2.empty());
    }

    long commitSequence() {
        return commitSequence;
    }

    SlotSnapshot2 slots() {
        return slots;
    }

    IndexSnapshot2 index() {
        return index;
    }

    /** Returns the uses of the snapshot and its place in the retention window. */
    SnapshotUses uses() {
        return uses;
    }

    public int size() {
        return slots.size();
    }

    public List<Entity2> getAll(long[] entityIds) {
        List<Entity2> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            int slot = slots.slotOf(id);
            if (slot != SlotTable.NONE) {
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
