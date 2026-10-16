package badspace.service.partition;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A buffer with the coordinates of the snapshots of a partition. It knows the commit of the
 * state it holds, so the writer that takes it again from the pool copies only the pages of
 * slots changed after that commit. Consecutive snapshots without changes of the coordinates
 * share the same buffer: it counts the snapshots that use it, and goes back to the pool when
 * the last of them ends.
 */
final class CoordinateBuffer {

    /** The commit sequence of a buffer that holds no state yet: a commit copies all its pages. */
    static final long NO_COMMIT = 0;

    private final double[] values;
    // The commit sequence of the state in values. Written by the writer while it fills the
    // buffer, when no snapshot uses it, and read only by the writer.
    private long commitSequence = NO_COMMIT;
    // The snapshots that use the buffer. The writer adds one when it makes a snapshot; the
    // thread that ends a snapshot, any thread, removes one.
    private final AtomicInteger users = new AtomicInteger();

    CoordinateBuffer(int length) {
        this.values = new double[length];
    }

    double[] values() {
        return values;
    }

    long commitSequence() {
        return commitSequence;
    }

    /** Records that the buffer holds the state of the given commit. Only the writer calls it. */
    void holdCommit(long commitSequence) {
        this.commitSequence = commitSequence;
    }

    /** Adds a snapshot that uses the buffer. */
    void addUser() {
        users.incrementAndGet();
    }

    /**
     * Removes a snapshot that used the buffer. Returns true if it was the last one: then
     * nobody reads the buffer, and it can go back to the pool.
     */
    boolean removeUser() {
        int left = users.decrementAndGet();
        if (left < 0) {
            throw new IllegalStateException("Coordinate buffer has no users to remove");
        }
        return left == 0;
    }
}
