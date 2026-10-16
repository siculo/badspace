package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The lock of the writer of a partition, for the tests: it tells when a thread starts to wait
 * for it because another thread holds it. So a test knows that a move of the tick waits for a
 * commit without looking at the state of the threads.
 */
final class ObservedLock extends ReentrantLock {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final CountDownLatch waiting = new CountDownLatch(1);

    @Override
    public void lock() {
        if (isLocked() && !isHeldByCurrentThread()) {
            waiting.countDown();
        }
        super.lock();
    }

    /** Waits until a thread starts to wait for the lock, and fails after the timeout. */
    void awaitWaiting() throws InterruptedException {
        assertTrue(waiting.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "no thread waited for the lock");
    }
}
