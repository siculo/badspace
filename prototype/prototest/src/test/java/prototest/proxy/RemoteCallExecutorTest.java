package prototest.proxy;

import org.junit.Test;

import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.api.errors.network.RetryableCommunicationException;
import prototest.api.errors.network.SessionUnavailableException;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class RemoteCallExecutorTest {
    @Test
    public void rejectionOnRetryLeavesTheFirstAttemptUncertain() {
        var calls = new RemoteCallExecutor(100, () -> 0L);
        calls.connect(UUID::randomUUID);
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
        var calls = new RemoteCallExecutor(timeoutMillis, now::get);
        calls.connect(UUID::randomUUID);
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
