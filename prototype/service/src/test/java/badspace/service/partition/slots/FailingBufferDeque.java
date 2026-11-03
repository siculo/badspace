package badspace.service.partition.slots;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * The deque of a pool of coordinate buffers for the tests: when the test sets a failure, the
 * next buffer that goes back to the pool throws it instead, once, as if the memory ran out.
 */
final class FailingBufferDeque extends ConcurrentLinkedDeque<CoordinateBuffer> {

    // The failure of the next give back, if not null. Only the test thread uses it.
    Error addFailure;

    @Override
    public void addFirst(CoordinateBuffer buffer) {
        Error failure = addFailure;
        addFailure = null;
        if (failure != null) {
            throw failure;
        }
        super.addFirst(buffer);
    }
}
