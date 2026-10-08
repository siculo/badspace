package badspace.service.index;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;

/**
 * Version of a 2D index at a commit. It never changes after the commit, so it
 * can be read while the writer goes on changing the index. The index speaks of
 * the slots of the same commit, so each query gets the slots of that commit.
 */
public interface IndexVersion2 {

    /** Returns the number of the commit of this version. */
    long commit();

    /** Returns the slots of the entities inside the region, border included, in any order. */
    int[] findInRegion(Region2 region, SlotView2 slots);

    /**
     * Returns the slots of the {@code count} entities nearest to the point,
     * ordered by distance and then by ID. {@code count} must be positive.
     */
    int[] findNearest(Point2 point, int count, SlotView2 slots);
}
