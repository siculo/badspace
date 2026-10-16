package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Stops the queries, or the commit, that reach it until the test opens it. */
final class Gate {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch opened = new CountDownLatch(1);

    void pass() throws InterruptedException {
        entered.countDown();
        assertTrue(opened.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the gate was never opened");
    }

    void awaitEntered() throws InterruptedException {
        assertTrue(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "nothing reached the gate");
    }

    void open() {
        opened.countDown();
    }
}
