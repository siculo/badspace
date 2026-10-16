package badspace.service.partition;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A pool of coordinate buffers for the tests: the first thread that gives back a buffer
 * stops at the gate, if there is one, before the buffer is in the pool. The other threads
 * go on.
 */
final class GatedBufferPool extends ConcurrentLinkedQueue<double[]> {

    // Set by the test thread before the buffer comes back, read by the thread that gives it back.
    volatile Gate gate;
    private final AtomicBoolean stopped = new AtomicBoolean();

    @Override
    public boolean add(double[] buffer) {
        Gate current = gate;
        if (current != null && stopped.compareAndSet(false, true)) {
            try {
                current.pass();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        return super.add(buffer);
    }
}
