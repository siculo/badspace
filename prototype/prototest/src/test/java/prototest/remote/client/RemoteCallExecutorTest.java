package prototest.remote.client;

import org.junit.Test;

import prototest.remote.api.RequestId;
import prototest.remote.api.SessionNetworkInterface;
import prototest.remote.api.errors.NodeRecoveryRequiredException;
import prototest.remote.api.errors.RetryableCommunicationException;
import prototest.remote.api.errors.SessionUnavailableException;
import prototest.remote.network.FakeNetworkInterface;
import prototest.remote.server.ServerSessionEndpoint;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static prototest.remote.network.FakeNetworkInterface.any;

public class RemoteCallExecutorTest {
    public interface CounterWrites {
        long increment(RequestId requestId);
    }

    public interface CounterReads {
        String read(RequestId requestId);
    }

    @Test
    public void separateApplicationContractsShareASessionAndRetryWithoutRepeatingTheWrite() {
        var counter = new AtomicLong();
        try (var endpoint = new ServerSessionEndpoint(60000)) {
            var sessionNetwork = FakeNetworkInterface.wrap(SessionNetworkInterface.class, endpoint).build();
            CounterWrites writes = id -> endpoint.executeOnceAndReturn(id, counter::incrementAndGet);
            CounterReads reads = id -> endpoint.executeOnceAndReturn(id, () -> Long.toString(counter.get()));
            var writeNetwork = FakeNetworkInterface.wrap(CounterWrites.class, writes)
                    .on(remote -> remote.increment(any())).onFirstCall().dropResponse();
            var remoteWrites = writeNetwork.build();
            var remoteReads = FakeNetworkInterface.wrap(CounterReads.class, reads).build();
            var calls = new RemoteCallExecutor(sessionNetwork, 60000);
            calls.connect();
            try {
                assertEquals(1L, (long) calls.executeAndReturn(remoteWrites::increment));
                assertEquals("1", calls.executeAndReturn(remoteReads::read));
                writeNetwork.assertAllRulesUsedAndCallsFinished();
            } finally {
                calls.disconnect();
            }
        }
    }

    @Test
    public void rejectionOnRetryLeavesTheFirstAttemptUncertain() {
        try (var endpoint = new ServerSessionEndpoint(100)) {
            var calls = new RemoteCallExecutor(endpoint, 100, () -> 0L);
            calls.connect();
            var attempts = new AtomicLong();
            var expired = new SessionUnavailableException("Session expired");
            var failure = assertThrows(NodeRecoveryRequiredException.class, () -> calls.execute(id -> {
                if (attempts.getAndIncrement() == 0) {
                    throw new RetryableCommunicationException("Response lost");
                }
                throw expired;
            }));
            assertEquals(NodeRecoveryRequiredException.class, failure.getClass());
            assertSame(expired, failure.getCause());
        }
    }

    /**
     * A controlled clock makes the first loss occur one nanosecond before the
     * deadline and the second loss occur at the deadline. The executor allows
     * one retry, then reports its failure as the cause and requires recovery.
     */
    @Test
    public void aPositiveTimeoutAllowsARetryAndStopsAtTheDeadline() {
        long timeoutMillis = 10;
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        var now = new AtomicLong();
        try (var endpoint = new ServerSessionEndpoint(timeoutMillis)) {
            var calls = new RemoteCallExecutor(endpoint, timeoutMillis, now::get);
            calls.connect();
            var firstFailure = new RetryableCommunicationException("First request lost");
            var lastFailure = new RetryableCommunicationException("Retry request lost");

            var error = assertThrows(NodeRecoveryRequiredException.class, () -> calls.execute(requestId -> {
                if (now.get() == 0) {
                    now.set(timeoutNanos - 1);
                    throw firstFailure;
                }
                if (now.get() < timeoutNanos) {
                    now.set(timeoutNanos);
                    throw lastFailure;
                }
                fail("A request was sent after the timeout");
            }));

            assertEquals("Unrecoverable timeout", error.getMessage());
            assertSame(lastFailure, error.getCause());
            assertTrue(calls.recoveryRequired());
        }
    }
}
