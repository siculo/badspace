package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.Entity2;
import badspace.service.index.IndexVersion2;
import java.util.ArrayList;
import java.util.List;

/**
 * A 2D partition at a commit: the slots and the index of the same commit. The
 * storage makes a version at each commit and publishes it to the readers.
 * Its data never change, so many threads can read it at the same time while the
 * writer goes on. The readers outside the node reach it only through an
 * acquisition. Its {@linkplain #uses() uses} tell when the version ends.
 */
final class StorageVersion2 {

    private final long commit;
    private final SlotSnapshot2 slots;
    private final IndexVersion2 index;
    private final VersionUses uses = new VersionUses();

    StorageVersion2(long commit, SlotSnapshot2 slots, IndexVersion2 index) {
        this.commit = commit;
        this.slots = slots;
        this.index = index;
    }

    /** Returns the empty version at commit 0, before the first commit. */
    static StorageVersion2 empty() {
        SlotSnapshot2 slots = new SlotSnapshot2(new long[0], new double[0], new SlotTable());
        return new StorageVersion2(0, slots, IndexVersion2.empty());
    }

    long commit() {
        return commit;
    }

    SlotSnapshot2 slots() {
        return slots;
    }

    IndexVersion2 index() {
        return index;
    }

    /** Returns the uses of the version and its place in the retention window. */
    VersionUses uses() {
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
