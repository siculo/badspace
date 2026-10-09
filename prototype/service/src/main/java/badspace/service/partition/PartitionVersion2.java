package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.Entity2;
import badspace.service.index.IndexVersion2;
import java.util.ArrayList;
import java.util.List;

/**
 * A 2D partition at a commit: the slots and the index of the same commit. It
 * never changes, so its reads do not see the writes after the commit. The
 * storage makes a version at each commit.
 */
record PartitionVersion2(long commit, SlotSnapshot2 slots, IndexVersion2 index) {

    List<Entity2> getAll(long[] entityIds) {
        List<Entity2> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            int slot = slots.slotOf(id);
            if (slot != SlotTable.NONE) {
                result.add(new Entity2(id, slots.positionAt(slot)));
            }
        }
        return result;
    }

    List<Entity2> findInRegion(Region2 region) {
        return entitiesAt(index.findInRegion(region, slots));
    }

    List<Entity2> findNearest(Point2 point, int count) {
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
