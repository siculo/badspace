package badspace.service.partition;

import badspace.service.partition.slots.CoordinateBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A pool of coordinate buffers for the tests: the first thread that gives back a buffer
 * stops at the gate, if there is one, before the buffer is in the pool. The other threads
 * go on.
 */
final class GatedBufferPool extends ConcurrentLinkedDeque<CoordinateBuffer> {

    // Set by the test thread before the buffer comes back, read by the thread that gives it back.
    volatile Gate gate;
    private final AtomicBoolean stopped = new AtomicBoolean();

    @Override
    public void addFirst(CoordinateBuffer buffer) {
        Gate current = gate;
        if (current != null && stopped.compareAndSet(false, true)) {
            try {
                current.pass();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        super.addFirst(buffer);
    }
}
