package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.service.index.IndexSnapshot2;
import badspace.service.index.SlotView2;
import badspace.service.index.SpatialIndex2;

/**
 * A real index whose snapshots stop findInRegion at the gate, if there is one,
 * before they read the index. The commit stops at the commit gate, if there is one.
 */
final class GatedIndex2 implements SpatialIndex2 {

    private final SpatialIndex2 index;
    // Set by the test thread before the query starts, read by the query thread.
    volatile Gate gate;
    // Set by the test thread before the commit starts, read by the writer thread.
    volatile Gate commitGate;

    GatedIndex2(SpatialIndex2 index) {
        this.index = index;
    }

    @Override
    public void inserted(int slot, Point2 position) {
        index.inserted(slot, position);
    }

    @Override
    public void moved(int slot, Point2 from, Point2 to) {
        index.moved(slot, from, to);
    }

    @Override
    public void removed(int slot, Point2 position) {
        index.removed(slot, position);
    }

    @Override
    public void relocated(int from, int to, Point2 position) {
        index.relocated(from, to, position);
    }

    @Override
    public IndexSnapshot2 commit(long n) {
        Gate currentCommitGate = commitGate;
        if (currentCommitGate != null) {
            try {
                currentCommitGate.pass();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        IndexSnapshot2 snapshot = index.commit(n);
        return new IndexSnapshot2() {
            @Override
            public long commitSequence() {
                return snapshot.commitSequence();
            }

            @Override
            public int[] findInRegion(Region2 region, SlotView2 slots) {
                Gate current = gate;
                if (current != null) {
                    try {
                        current.pass();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return snapshot.findInRegion(region, slots);
            }

            @Override
            public int[] findNearest(Point2 point, int count, SlotView2 slots) {
                return snapshot.findNearest(point, count, slots);
            }
        };
    }

    @Override
    public int[] findInRegion(Region2 region) {
        return index.findInRegion(region);
    }

    @Override
    public int[] findNearest(Point2 point, int count) {
        return index.findNearest(point, count);
    }
}
