package badspace.service.partition.snapshot;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The uses of a snapshot and its place in the retention window. The uses are the
 * valid acquisitions and the admitted queries. The snapshot ends when it is out of
 * the window and has no uses: nobody uses it and nobody can ask for it again.
 * It ends only once, and an ended snapshot never gets a new use.
 * <p>
 * Every change is a single atomic step, so any thread can call any method. The
 * method that ends the snapshot returns true, and only one call can do it.
 */
final class SnapshotUses {

    // The state of an ended snapshot.
    private static final int ENDED = -1;
    // Bit of the state set when the snapshot is out of the window.
    private static final int OUT_OF_WINDOW = 1;
    // The state counts the uses in the bits above OUT_OF_WINDOW.
    private static final int ONE_USE = 2;

    // ENDED, or uses * ONE_USE + (OUT_OF_WINDOW if the snapshot left the window).
    // A new snapshot is in the window with no uses.
    private final AtomicInteger state = new AtomicInteger();

    /**
     * Adds a use for an acquisition, only if the snapshot is still in the window.
     * Returns false, and adds nothing, if the snapshot left the window.
     */
    boolean addUseIfInWindow() {
        while (true) {
            int current = state.get();
            if (current == ENDED || (current & OUT_OF_WINDOW) != 0) {
                return false;
            }
            if (state.compareAndSet(current, current + ONE_USE)) {
                return true;
            }
        }
    }

    /**
     * Adds a use for a query, also if the snapshot left the window. Returns false,
     * and adds nothing, if the snapshot ended.
     */
    boolean addUseIfNotEnded() {
        while (true) {
            int current = state.get();
            if (current == ENDED) {
                return false;
            }
            if (state.compareAndSet(current, current + ONE_USE)) {
                return true;
            }
        }
    }

    /**
     * Removes a use, added before by one of the add methods. Returns true if this
     * ends the snapshot: it was the last use and the snapshot is out of the window.
     */
    boolean removeUse() {
        while (true) {
            int current = state.get();
            if (current < ONE_USE) {
                throw new IllegalStateException("Snapshot has no uses to remove");
            }
            int next = current - ONE_USE;
            if (next == OUT_OF_WINDOW) {
                next = ENDED;
            }
            if (state.compareAndSet(current, next)) {
                return next == ENDED;
            }
        }
    }

    /**
     * Marks that the snapshot left the window: it happens once, when the tick advances and
     * moves the window. Returns true if this ends the snapshot, because it has no uses.
     */
    boolean leaveWindow() {
        while (true) {
            int current = state.get();
            if (current == ENDED || (current & OUT_OF_WINDOW) != 0) {
                throw new IllegalStateException("Snapshot already left the window");
            }
            int next = current == 0 ? ENDED : current | OUT_OF_WINDOW;
            if (state.compareAndSet(current, next)) {
                return next == ENDED;
            }
        }
    }

    /** Returns true if the snapshot ended. */
    boolean isEnded() {
        return state.get() == ENDED;
    }
}
