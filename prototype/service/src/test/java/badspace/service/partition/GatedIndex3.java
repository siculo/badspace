package badspace.service.partition;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.service.index.IndexSnapshot3;
import badspace.service.index.SlotView3;
import badspace.service.index.SpatialIndex3;

/**
 * A real index whose snapshots stop findInRegion at the gate, if there is one,
 * before they read the index. The commit stops at the commit gate, if there is one.
 */
final class GatedIndex3 implements SpatialIndex3 {

    private final SpatialIndex3 index;
    // Set by the test thread before the query starts, read by the query thread.
    volatile Gate gate;
    // Set by the test thread before the commit starts, read by the writer thread.
    volatile Gate commitGate;

    GatedIndex3(SpatialIndex3 index) {
        this.index = index;
    }

    @Override
    public void inserted(int slot, Point3 position) {
        index.inserted(slot, position);
    }

    @Override
    public void moved(int slot, Point3 from, Point3 to) {
        index.moved(slot, from, to);
    }

    @Override
    public void removed(int slot, Point3 position) {
        index.removed(slot, position);
    }

    @Override
    public void relocated(int from, int to, Point3 position) {
        index.relocated(from, to, position);
    }

    @Override
    public IndexSnapshot3 commit(long n) {
        Gate currentCommitGate = commitGate;
        if (currentCommitGate != null) {
            try {
                currentCommitGate.pass();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        IndexSnapshot3 snapshot = index.commit(n);
        return new IndexSnapshot3() {
            @Override
            public long commitSequence() {
                return snapshot.commitSequence();
            }

            @Override
            public int[] findInRegion(Region3 region, SlotView3 slots) {
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
            public int[] findNearest(Point3 point, int count, SlotView3 slots) {
                return snapshot.findNearest(point, count, slots);
            }
        };
    }

    @Override
    public int[] findInRegion(Region3 region) {
        return index.findInRegion(region);
    }

    @Override
    public int[] findNearest(Point3 point, int count) {
        return index.findNearest(point, count);
    }
}
