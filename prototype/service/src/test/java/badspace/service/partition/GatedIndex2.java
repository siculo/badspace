package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.service.index.IndexSnapshot2;
import badspace.service.index.SlotView2;
import badspace.service.index.SpatialIndex2;

/**
 * A real index whose snapshots stop findInRegion at the gate, if there is one,
 * before they read the index. The commit stops at the commit gate, if there is one.
 * The preparation of the commit throws the prepare failure, if there is one. The other
 * methods give the real index its own snapshots.
 */
final class GatedIndex2 implements SpatialIndex2 {

    private final SpatialIndex2 index;
    // Set by the test thread before the query starts, read by the query thread.
    volatile Gate gate;
    // Set by the test thread before the commit starts, read by the writer thread.
    volatile Gate commitGate;
    // Set by the test thread before the commit starts, read by the writer thread: if not
    // null, the preparation of the commit throws it after the commit gate.
    volatile RuntimeException prepareFailure;

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
    public IndexSnapshot2 prepareCommit(long n) {
        Gate currentCommitGate = commitGate;
        if (currentCommitGate != null) {
            try {
                currentCommitGate.pass();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        RuntimeException failure = prepareFailure;
        if (failure != null) {
            throw failure;
        }
        return new GatedSnapshot(index.prepareCommit(n));
    }

    @Override
    public void acceptCommit(IndexSnapshot2 prepared) {
        index.acceptCommit(((GatedSnapshot) prepared).snapshot);
    }

    @Override
    public void abandonCommit(IndexSnapshot2 prepared) {
        index.abandonCommit(((GatedSnapshot) prepared).snapshot);
    }

    @Override
    public void restore(IndexSnapshot2 confirmed) {
        index.restore(confirmed instanceof GatedSnapshot gated ? gated.snapshot : confirmed);
    }

    @Override
    public int[] findInRegion(Region2 region) {
        return index.findInRegion(region);
    }

    @Override
    public int[] findNearest(Point2 point, int count) {
        return index.findNearest(point, count);
    }

    /** A snapshot of the real index whose findInRegion stops at the gate. */
    private final class GatedSnapshot implements IndexSnapshot2 {

        private final IndexSnapshot2 snapshot;

        GatedSnapshot(IndexSnapshot2 snapshot) {
            this.snapshot = snapshot;
        }

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
    }
}
