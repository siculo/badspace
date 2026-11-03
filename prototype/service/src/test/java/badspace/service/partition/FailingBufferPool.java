package badspace.service.partition;

import badspace.service.partition.slots.CoordinateBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A pool of coordinate buffers for the tests: when the test sets a failure, the writer that
 * takes a buffer gets it instead, as if the allocation of the buffer failed.
 */
final class FailingBufferPool extends ConcurrentLinkedDeque<CoordinateBuffer> {

    // Set by the test thread before the commit, read by the writer thread.
    volatile RuntimeException takeFailure;

    @Override
    public CoordinateBuffer pollFirst() {
        RuntimeException failure = takeFailure;
        if (failure != null) {
            throw failure;
        }
        return super.pollFirst();
    }
}
