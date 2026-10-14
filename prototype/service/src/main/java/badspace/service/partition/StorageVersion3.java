package badspace.service.partition;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.Entity3;
import badspace.service.index.IndexVersion3;
import java.util.ArrayList;
import java.util.List;

/**
 * A 3D partition at a commit: the slots and the index of the same commit. The
 * storage makes a version at each commit and publishes it to the readers.
 * Its data never change, so many threads can read it at the same time while the
 * writer goes on. The readers outside the node reach it only through an
 * acquisition. Its {@linkplain #uses() uses} tell when the version ends.
 */
final class StorageVersion3 {

    private final long commit;
    private final SlotSnapshot3 slots;
    private final IndexVersion3 index;
    private final VersionUses uses = new VersionUses();

    StorageVersion3(long commit, SlotSnapshot3 slots, IndexVersion3 index) {
        this.commit = commit;
        this.slots = slots;
        this.index = index;
    }

    /** Returns the empty version at commit 0, before the first commit. */
    static StorageVersion3 empty() {
        SlotSnapshot3 slots = new SlotSnapshot3(new long[0], new double[0], new SlotTable());
        return new StorageVersion3(0, slots, IndexVersion3.empty());
    }

    long commit() {
        return commit;
    }

    SlotSnapshot3 slots() {
        return slots;
    }

    IndexVersion3 index() {
        return index;
    }

    /** Returns the uses of the version and its place in the retention window. */
    VersionUses uses() {
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
