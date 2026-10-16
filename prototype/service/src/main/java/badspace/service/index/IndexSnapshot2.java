package badspace.service.index;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;

/**
 * Snapshot of a 2D index at a commit. It never changes after the commit, so it
 * can be read while the writer goes on changing the index. The index speaks of
 * the slots of the same commit, so each query gets the slots of that commit.
 */
public interface IndexSnapshot2 {

    /**
     * Returns the snapshot of an index with no entities, at commit sequence 0: the
     * snapshot of a partition before its first commit. It reads all the slots
     * it gets, so it works with the empty slots of commit sequence 0 for every kind
     * of index.
     */
    static IndexSnapshot2 empty() {
        return LinearScanIndex2.EMPTY;
    }

    /** Returns the internal commit sequence of this snapshot, not a logical tick. */
    long commitSequence();

    /** Returns the slots of the entities inside the region, border included, in any order. */
    int[] findInRegion(Region2 region, SlotView2 slots);

    /**
     * Returns the slots of the {@code count} entities nearest to the point,
     * ordered by distance and then by ID. {@code count} must be positive.
     */
    int[] findNearest(Point2 point, int count, SlotView2 slots);
}
