package badspace.service.index;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import java.util.function.Function;

/**
 * Version of a 2D index made by a full copy: a new index of the same type,
 * built on a copy of the slots at the commit. It is simple but slow, and it is
 * used only by the indices that do not have copy-on-write versions yet.
 */
final class RebuiltIndexVersion2 implements IndexVersion2 {

    private final long commit;
    private final SpatialIndex2 index;

    /** Copies the slots and builds on them the index made by the factory. */
    RebuiltIndexVersion2(long commit, SlotView2 slots, Function<SlotView2, SpatialIndex2> indexFactory) {
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
    public int[] findInRegion(Region2 region, SlotView2 slots) {
        return index.findInRegion(region);
    }

    @Override
    public int[] findNearest(Point2 point, int count, SlotView2 slots) {
        return index.findNearest(point, count);
    }

    private static final class SlotCopy implements SlotView2 {

        private final long[] ids;
        private final Point2[] positions;

        SlotCopy(SlotView2 slots) {
            ids = new long[slots.size()];
            positions = new Point2[slots.size()];
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
        public Point2 positionAt(int slot) {
            return positions[slot];
        }
    }
}
