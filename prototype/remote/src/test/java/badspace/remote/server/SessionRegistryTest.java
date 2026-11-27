package badspace.remote.server;

import org.junit.jupiter.api.Test;

import badspace.remote.api.RequestId;
import badspace.remote.api.errors.NodeRecoveryRequiredException;
import badspace.remote.api.errors.SessionUnavailableException;

import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that requests from different sessions keep separate results and
 * separate expiration limits, also when they arrive out of order.
 *
 * <p>Uses a controlled clock, so no test waits for real time.
 */
public class SessionRegistryTest {

    @Test
    public void expirationInOneSessionDoesNotRejectAnotherSessionsLowerSequence() {
        var clock = new TestClock();
        var registry = new SessionRegistry(100, 2000, clock);
        var firstToken = registry.open();
        var secondToken = registry.open();
        var expired = new RequestId(firstToken, 20);
        registry.executeOnceAndReturn(expired, () -> null);
        clock.now = 101;
        registry.executeOnceAndReturn(new RequestId(firstToken, 21), () -> null);

        String result = registry.executeOnceAndReturn(new RequestId(secondToken, 0), () -> "accepted");

        assertEquals("accepted", result);
        assertThrows(NodeRecoveryRequiredException.class,
                () -> registry.executeOnceAndReturn(expired, () -> { throw new AssertionError("Expired request ran again"); }));
    }

    @Test
    public void anotherSessionDoesNotHideTheExpirationLimit() {
        var clock = new TestClock();
        var registry = new SessionRegistry(100, 2000, clock);
        var firstToken = registry.open();
        var secondToken = registry.open();
        var first = new RequestId(firstToken, 20);
        var second = new RequestId(secondToken, 0);
        registry.executeOnceAndReturn(first, () -> null);
        clock.now = 101;
        registry.executeOnceAndReturn(second, () -> null);
        clock.now = 202;
        registry.executeOnceAndReturn(new RequestId(secondToken, 1), () -> null);

        assertThrows(NodeRecoveryRequiredException.class,
                () -> registry.executeOnceAndReturn(first, () -> { throw new AssertionError("Expired request ran again"); }));
        assertThrows(NodeRecoveryRequiredException.class,
                () -> registry.executeOnceAndReturn(second, () -> { throw new AssertionError("Expired request ran again"); }));
    }

    @Test
    public void generationOrderDoesNotRequireTheSameArrivalOrderBeforeExpiration() {
        var registry = new SessionRegistry(100, 2000, new TestClock());
        var firstToken = registry.open();
        var newer = new RequestId(firstToken, 1);
        var older = new RequestId(firstToken, 0);

        assertEquals("newer", registry.executeOnceAndReturn(newer, () -> "newer"));
        assertEquals("older", registry.executeOnceAndReturn(older, () -> "older"));
        assertEquals("older", registry.executeOnceAndReturn(older, () -> {
            throw new AssertionError("Duplicate request ran again");
        }));
    }

    @Test
    public void resultsAreRetainedSeparatelyForEachSession() {
        var registry = new SessionRegistry(100, 2000, new TestClock());
        var firstToken = registry.open();
        var secondToken = registry.open();
        var first = new RequestId(firstToken, 0);
        var second = new RequestId(secondToken, 0);

        assertEquals("first", registry.executeOnceAndReturn(first, () -> "first"));
        assertEquals("second", registry.executeOnceAndReturn(second, () -> "second"));
        assertEquals("first", registry.executeOnceAndReturn(first, () -> {
            throw new AssertionError("Duplicate request ran again");
        }));
        assertEquals("second", registry.executeOnceAndReturn(second, () -> {
            throw new AssertionError("Duplicate request ran again");
        }));
    }

    @Test
    public void sessionTimeoutBeginsWhenTheLastResultIsRemoved() {
        var clock = new TestClock();
        var registry = new SessionRegistry(100, 200, clock);
        var token = registry.open();
        registry.executeOnceAndReturn(new RequestId(token, 0), () -> null);
        clock.now = 50;
        registry.executeOnceAndReturn(new RequestId(token, 1), () -> null);

        clock.now = 101;
        registry.removeExpired();
        clock.now = 250;
        registry.removeExpired();

        clock.now = 449;
        assertThrows(NodeRecoveryRequiredException.class,
                () -> registry.executeOnceAndReturn(new RequestId(token, 1), () -> { throw new AssertionError("Expired request ran again"); }));
        clock.now = 450;
        var error = assertThrows(SessionUnavailableException.class,
                () -> registry.executeOnceAndReturn(new RequestId(token, 2), () -> { throw new AssertionError("Expired session ran a new request"); }));
        assertEquals("Session %s is unavailable".formatted(token), error.getMessage());
    }

    @Test
    public void aNewRequestKeepsAnIdleSessionOpen() {
        var clock = new TestClock();
        var registry = new SessionRegistry(100, 200, clock);
        var token = registry.open();

        clock.now = 199;
        registry.executeOnceAndReturn(new RequestId(token, 0), () -> null);
        clock.now = 200;
        assertEquals("accepted", registry.executeOnceAndReturn(new RequestId(token, 1), () -> "accepted"));
    }

    @Test
    public void periodicCleanupRemovesAnEmptySessionWithoutIncomingRequests() throws Exception {
        var timeReadForCleanup = new CountDownLatch(1);
        var now = new AtomicLong();
        long sessionTimeout = 10;
        InstantSource time = () -> {
            long current = now.get();
            if (current == sessionTimeout) {
                timeReadForCleanup.countDown();
            }
            return Instant.ofEpochMilli(current);
        };
        try (var endpoint = new ServerSessionEndpoint(100, sessionTimeout, time.withZone(ZoneOffset.UTC))) {
            var token = endpoint.connect();
            now.set(sessionTimeout);
            assertTrue(timeReadForCleanup.await(5, TimeUnit.SECONDS));
            // Make the token young again from the viewpoint of admission. Only
            // the periodic cleanup could have removed it while the clock was advanced.
            now.set(0);
            assertThrows(NodeRecoveryRequiredException.class,
                    () -> endpoint.executeOnce(new RequestId(token, 0), () -> fail("Expired session ran a request")));
        }
    }

    private static final class TestClock extends Clock {
        private long now;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }
}
