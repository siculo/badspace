package badspace.service.partition;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.Entity3;
import badspace.service.index.IndexVersion3;
import java.util.ArrayList;
import java.util.List;

/**
 * A 3D partition at a commit: the slots and the index of the same commit. It
 * never changes, so its reads do not see the writes after the commit. The
 * storage makes a version at each commit.
 */
record PartitionVersion3(long commit, SlotSnapshot3 slots, IndexVersion3 index) {

    List<Entity3> getAll(long[] entityIds) {
        List<Entity3> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            int slot = slots.slotOf(id);
            if (slot != SlotTable.NONE) {
                result.add(new Entity3(id, slots.positionAt(slot)));
            }
        }
        return result;
    }

    List<Entity3> findInRegion(Region3 region) {
        return entitiesAt(index.findInRegion(region, slots));
    }

    List<Entity3> findNearest(Point3 point, int count) {
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
