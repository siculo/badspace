package badspace.service.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.service.index.IndexSnapshot2;
import badspace.service.index.SlotView2;
import badspace.service.index.SpatialIndex2;

/**
 * A real index whose snapshots stop findInRegion at the gate, if there is one,
 * before they read the index. The commit stops at the commit gate, and the restore at the
 * restore gate, if there is one.
 * The preparation of the commit throws the prepare failure, the notifications of the
 * changes throw the notification failure and the restore throws the restore failure, before
 * the real index does anything, if there is one. The other methods give the real index its
 * own snapshots.
 */
final class GatedIndex2 implements SpatialIndex2 {

    private final SpatialIndex2 index;
    // Set by the test thread before the query starts, read by the query thread.
    volatile Gate gate;
    // Set by the test thread before the commit starts, read by the writer thread.
    volatile Gate commitGate;
    // Set by the test thread before the commit starts, read by the writer thread: the first
    // insertion or move of the batch stops there.
    volatile Gate notificationGate;
    // Set by the test thread before the restore starts, read by the writer thread.
    volatile Gate restoreGate;
    // The failures that the methods throw, if not null: an exception or an Error. Set by the
    // test thread before the operation starts, read by the writer thread.
    volatile Throwable prepareFailure;
    volatile Throwable notificationFailure;
    volatile Throwable restoreFailure;

    GatedIndex2(SpatialIndex2 index) {
        this.index = index;
    }

    @Override
    public void inserted(int slot, Point2 position) {
        pass(notificationGate);
        throwIfSet(notificationFailure);
        index.inserted(slot, position);
    }

    @Override
    public void moved(int slot, Point2 from, Point2 to) {
        pass(notificationGate);
        throwIfSet(notificationFailure);
        index.moved(slot, from, to);
    }

    @Override
    public void removed(int slot, Point2 position) {
        throwIfSet(notificationFailure);
        index.removed(slot, position);
    }

    @Override
    public void relocated(int from, int to, Point2 position) {
        throwIfSet(notificationFailure);
        index.relocated(from, to, position);
    }

    @Override
    public IndexSnapshot2 prepareCommit(long n) {
        pass(commitGate);
        throwIfSet(prepareFailure);
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
        pass(restoreGate);
        throwIfSet(restoreFailure);
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

    private static void pass(Gate gate) {
        if (gate != null) {
            try {
                gate.pass();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static void throwIfSet(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
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
