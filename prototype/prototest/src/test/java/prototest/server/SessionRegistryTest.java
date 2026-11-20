package prototest.server;

import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.NodeRecoveryRequiredException;

import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Checks that requests from different proxies keep separate results and
 * separate expiration limits, also when they arrive out of order.
 *
 * <p>Uses a controlled clock, so no test waits for real time.
 */
public class SessionRegistryTest {

    @Test
    public void expirationOfOneProxyDoesNotRejectAnotherProxysLowerSequence() {
        var clock = new TestClock();
        var register = new SessionRegistry(100, 2000, clock);
        var firstToken = register.open();
        var secondToken = register.open();
        var expired = new RequestId(firstToken, 20);
        register.executeOnce(expired, () -> {});
        clock.now = 101;
        register.executeOnce(new RequestId(firstToken, 21), () -> {});

        String result = register.executeOnceAndReturn(new RequestId(secondToken, 0), () -> "accepted");

        assertEquals("accepted", result);
        assertThrows(NodeRecoveryRequiredException.class,
                () -> register.executeOnce(expired, () -> fail("Expired request ran again")));
    }

    @Test
    public void anotherProxyDoesNotHideTheExpirationLimit() {
        var clock = new TestClock();
        var register = new SessionRegistry(100, 2000, clock);
        var firstToken = register.open();
        var secondToken = register.open();
        var first = new RequestId(firstToken, 20);
        var second = new RequestId(secondToken, 0);
        register.executeOnce(first, () -> {});
        clock.now = 101;
        register.executeOnce(second, () -> {});
        clock.now = 202;
        register.executeOnce(new RequestId(secondToken, 1), () -> {});

        assertThrows(NodeRecoveryRequiredException.class,
                () -> register.executeOnce(first, () -> fail("Expired request ran again")));
        assertThrows(NodeRecoveryRequiredException.class,
                () -> register.executeOnce(second, () -> fail("Expired request ran again")));
    }

    @Test
    public void generationOrderDoesNotRequireTheSameArrivalOrderBeforeExpiration() {
        var register = new SessionRegistry(100, 2000, new TestClock());
        var firstToken = register.open();
        var newer = new RequestId(firstToken, 1);
        var older = new RequestId(firstToken, 0);

        assertEquals("newer", register.executeOnceAndReturn(newer, () -> "newer"));
        assertEquals("older", register.executeOnceAndReturn(older, () -> "older"));
        assertEquals("older", register.executeOnceAndReturn(older, () -> {
            throw new AssertionError("Duplicate request ran again");
        }));
    }

    @Test
    public void resultsAreRetainedSeparatelyForEachProxy() {
        var register = new SessionRegistry(100, 2000, new TestClock());
        var firstToken = register.open();
        var secondToken = register.open();
        var first = new RequestId(firstToken, 0);
        var second = new RequestId(secondToken, 0);

        assertEquals("first", register.executeOnceAndReturn(first, () -> "first"));
        assertEquals("second", register.executeOnceAndReturn(second, () -> "second"));
        assertEquals("first", register.executeOnceAndReturn(first, () -> {
            throw new AssertionError("Duplicate request ran again");
        }));
        assertEquals("second", register.executeOnceAndReturn(second, () -> {
            throw new AssertionError("Duplicate request ran again");
        }));
    }

    @Test
    public void sessionTimeoutBeginsWhenTheLastResultIsRemoved() {
        var clock = new TestClock();
        var register = new SessionRegistry(100, 200, clock);
        var token = register.open();
        register.executeOnce(new RequestId(token, 0), () -> {});
        clock.now = 50;
        register.executeOnce(new RequestId(token, 1), () -> {});

        clock.now = 101;
        register.removeExpired();
        clock.now = 250;
        register.removeExpired();

        clock.now = 449;
        assertThrows(NodeRecoveryRequiredException.class,
                () -> register.executeOnce(new RequestId(token, 1), () -> fail("Expired request ran again")));
        clock.now = 450;
        var error = assertThrows(NodeRecoveryRequiredException.class,
                () -> register.executeOnce(new RequestId(token, 2), () -> fail("Expired session ran a new request")));
        assertEquals("Session %s is unavailable".formatted(token), error.getMessage());
    }

    @Test
    public void aNewRequestKeepsAnIdleSessionOpen() {
        var clock = new TestClock();
        var register = new SessionRegistry(100, 200, clock);
        var token = register.open();

        clock.now = 199;
        register.executeOnce(new RequestId(token, 0), () -> {});
        clock.now = 200;
        assertEquals("accepted", register.executeOnceAndReturn(new RequestId(token, 1), () -> "accepted"));
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
        var server = new NodeServer();
        try (var endpoint = new ServerNodeNetworkInterface(server, 100, sessionTimeout,
                time.withZone(ZoneOffset.UTC))) {
            var token = endpoint.connect();
            now.set(sessionTimeout);
            assertTrue(timeReadForCleanup.await(5, TimeUnit.SECONDS));
            // Make the token young again from the viewpoint of admission. Only
            // the periodic cleanup could have removed it while the clock was advanced.
            now.set(0);
            assertThrows(NodeRecoveryRequiredException.class,
                    () -> endpoint.createPartition(new RequestId(token, 0), "expired"));
            assertFalse(server.partitionExists("expired"));
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
