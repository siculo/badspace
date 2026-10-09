package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.Entity2;
import badspace.common.partition.PartitionVersion2;
import badspace.service.index.IndexVersion2;
import java.util.ArrayList;
import java.util.List;

/**
 * A 2D partition at a commit: the slots and the index of the same commit. The
 * storage makes a version at each commit and publishes it to the readers.
 */
record StorageVersion2(long commit, SlotSnapshot2 slots, IndexVersion2 index) implements PartitionVersion2 {

    /** Returns the empty version at commit 0, before the first commit. */
    static StorageVersion2 empty() {
        SlotSnapshot2 slots = new SlotSnapshot2(new long[0], new double[0], new SlotTable());
        return new StorageVersion2(0, slots, IndexVersion2.empty());
    }

    @Override
    public int size() {
        return slots.size();
    }

    @Override
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

    @Override
    public List<Entity2> findInRegion(Region2 region) {
        return entitiesAt(index.findInRegion(region, slots));
    }

    @Override
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
