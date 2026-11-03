package badspace.service.partition;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.service.index.IndexSnapshot3;
import badspace.service.index.SlotView3;
import badspace.service.index.SpatialIndex3;

/**
 * A real index whose snapshots stop findInRegion at the gate, if there is one,
 * before they read the index. The commit stops at the commit gate, and the restore at the
 * restore gate, if there is one.
 * The preparation of the commit throws the prepare failure, the notifications of the
 * changes throw the notification failure and the restore throws the restore failure, before
 * the real index does anything, if there is one. The other methods give the real index its
 * own snapshots.
 */
final class GatedIndex3 implements SpatialIndex3 {

    private final SpatialIndex3 index;
    // Set by the test thread before the query starts, read by the query thread.
    volatile Gate gate;
    // Set by the test thread before the commit starts, read by the writer thread.
    volatile Gate commitGate;
    // Set by the test thread before the restore starts, read by the writer thread.
    volatile Gate restoreGate;
    // The failures that the methods throw, if not null: an exception or an Error. Set by the
    // test thread before the operation starts, read by the writer thread.
    volatile Throwable prepareFailure;
    volatile Throwable notificationFailure;
    volatile Throwable restoreFailure;

    GatedIndex3(SpatialIndex3 index) {
        this.index = index;
    }

    @Override
    public void inserted(int slot, Point3 position) {
        throwIfSet(notificationFailure);
        index.inserted(slot, position);
    }

    @Override
    public void moved(int slot, Point3 from, Point3 to) {
        throwIfSet(notificationFailure);
        index.moved(slot, from, to);
    }

    @Override
    public void removed(int slot, Point3 position) {
        throwIfSet(notificationFailure);
        index.removed(slot, position);
    }

    @Override
    public void relocated(int from, int to, Point3 position) {
        throwIfSet(notificationFailure);
        index.relocated(from, to, position);
    }

    @Override
    public IndexSnapshot3 prepareCommit(long n) {
        pass(commitGate);
        throwIfSet(prepareFailure);
        return new GatedSnapshot(index.prepareCommit(n));
    }

    @Override
    public void acceptCommit(IndexSnapshot3 prepared) {
        index.acceptCommit(((GatedSnapshot) prepared).snapshot);
    }

    @Override
    public void abandonCommit(IndexSnapshot3 prepared) {
        index.abandonCommit(((GatedSnapshot) prepared).snapshot);
    }

    @Override
    public void restore(IndexSnapshot3 confirmed) {
        pass(restoreGate);
        throwIfSet(restoreFailure);
        index.restore(confirmed instanceof GatedSnapshot gated ? gated.snapshot : confirmed);
    }

    @Override
    public int[] findInRegion(Region3 region) {
        return index.findInRegion(region);
    }

    @Override
    public int[] findNearest(Point3 point, int count) {
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
    private final class GatedSnapshot implements IndexSnapshot3 {

        private final IndexSnapshot3 snapshot;

        GatedSnapshot(IndexSnapshot3 snapshot) {
            this.snapshot = snapshot;
        }

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
    }
}
