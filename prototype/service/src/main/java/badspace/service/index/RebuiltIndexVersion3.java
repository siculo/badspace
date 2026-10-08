package badspace.service.index;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import java.util.function.Function;

/**
 * Version of a 3D index made by a full copy: a new index of the same type,
 * built on a copy of the slots at the commit. It is simple but slow, and it is
 * used only by the indices that do not have copy-on-write versions yet.
 */
final class RebuiltIndexVersion3 implements IndexVersion3 {

    private final long commit;
    private final SpatialIndex3 index;

    /** Copies the slots and builds on them the index made by the factory. */
    RebuiltIndexVersion3(long commit, SlotView3 slots, Function<SlotView3, SpatialIndex3> indexFactory) {
        this.commit = commit;
        SlotCopy copy = new SlotCopy(slots);
        this.index = indexFactory.apply(copy);
        for (int slot = 0; slot < copy.size(); slot++) {
            index.inserted(slot, copy.positionAt(slot));
        }
    }

    @Override
    public long commit() {
        return commit;
    }

    // The copied index reads its own copy of the slots, which is equal to the slots of the commit.

    @Override
    public int[] findInRegion(Region3 region, SlotView3 slots) {
        return index.findInRegion(region);
    }

    @Override
    public int[] findNearest(Point3 point, int count, SlotView3 slots) {
        return index.findNearest(point, count);
    }

    private static final class SlotCopy implements SlotView3 {

        private final long[] ids;
        private final Point3[] positions;

        SlotCopy(SlotView3 slots) {
            ids = new long[slots.size()];
            positions = new Point3[slots.size()];
            for (int slot = 0; slot < ids.length; slot++) {
                ids[slot] = slots.idAt(slot);
                positions[slot] = slots.positionAt(slot);
            }
        }

        @Override
        public int size() {
            return ids.length;
        }

        @Override
        public long idAt(int slot) {
            return ids[slot];
        }

        @Override
        public Point3 positionAt(int slot) {
            return positions[slot];
        }
    }
}
