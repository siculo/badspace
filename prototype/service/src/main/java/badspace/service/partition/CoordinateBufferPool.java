package badspace.service.partition;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * The coordinate buffers of a partition that no snapshot uses, ready for the next commits.
 * The partition owns one pool: the writer takes buffers from it when it prepares a snapshot,
 * and the thread that ends the last snapshot of a buffer, any thread, gives the buffer back
 * (see {@code SnapshotLifetime2} and {@code SnapshotLifetime3}). The pool counts coordinates,
 * not slots, so it does not depend on the number of dimensions.
 * <p>
 * Buffers come back at the head and the writer takes from the head, so it gets first the
 * buffer that came back last, which usually has the most recent state. The deque does not
 * block, so the writer never waits for the thread that gives a buffer back.
 */
final class CoordinateBufferPool {

    private final ConcurrentLinkedDeque<CoordinateBuffer> buffers;

    /** Creates an empty pool. */
    CoordinateBufferPool() {
        this(new ConcurrentLinkedDeque<>());
    }

    /**
     * Creates a pool on the given empty deque. The tests give a deque that stops a thread
     * that gives back a buffer.
     */
    CoordinateBufferPool(ConcurrentLinkedDeque<CoordinateBuffer> buffers) {
        this.buffers = buffers;
    }

    /**
     * Returns a buffer for at least length coordinates: one from the pool, or a new one with
     * newLength coordinates if the pool has none large enough. The buffers that are too small
     * are dropped: the partition has more entities than when they were made. Only the writer
     * calls it.
     */
    CoordinateBuffer take(int length, int newLength) {
        CoordinateBuffer buffer;
        while ((buffer = buffers.pollFirst()) != null) {
            if (buffer.values().length >= length) {
                return buffer;
            }
        }
        return new CoordinateBuffer(newLength);
    }

    /**
     * Puts back a buffer that no snapshot uses any more. The empty buffer of the empty
     * snapshot is not kept. Any thread can call it.
     */
    void giveBack(CoordinateBuffer buffer) {
        if (buffer.values().length > 0) {
            buffers.addFirst(buffer);
        }
    }

    /** Drops all the buffers: no commit will use them again. Any thread can call it. */
    void clear() {
        buffers.clear();
    }

    /** Returns the number of buffers in the pool. Any thread can call it. */
    int size() {
        return buffers.size();
    }
}
