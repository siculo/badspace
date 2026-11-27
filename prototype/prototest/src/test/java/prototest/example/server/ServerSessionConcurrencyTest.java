package prototest.example.server;

import badspace.remote.api.RequestId;
import badspace.remote.api.errors.NodeRecoveryRequiredException;
import badspace.remote.api.errors.SessionUnavailableException;
import badspace.remote.server.ServerSessionEndpoint;
import org.junit.After;
import org.junit.Test;

import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.*;

public class ServerSessionConcurrencyTest {
    private static final long WAIT_SECONDS = 5;
    private static final long REQUEST_TIMEOUT = 100;
    private static final long SESSION_TIMEOUT = 200;

    private final AtomicLong now = new AtomicLong();
    private final InstantSource time = () -> Instant.ofEpochMilli(now.get());
    private final ServerSessionEndpoint endpoint =
            new ServerSessionEndpoint(REQUEST_TIMEOUT, SESSION_TIMEOUT, time.withZone(ZoneOffset.UTC));
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch finish = new CountDownLatch(1);
    private final CountDownLatch secondEntered = new CountDownLatch(1);
    private final CountDownLatch finishSecond = new CountDownLatch(1);
    private final ExecutorService workers = Executors.newCachedThreadPool();

    @After
    public void closeEndpoint() throws InterruptedException {
        finish.countDown();
        finishSecond.countDown();
        try {
            endpoint.close();
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    public void independentRequestsRunWhileAnotherRequestIsInProgress() throws Exception {
        var firstToken = endpoint.connect();
        var secondToken = endpoint.connect();
        var first = workers.submit(() -> endpoint.executeOnceAndReturn(new RequestId(firstToken, 0), () -> {
            pause(entered, finish);
            return "first";
        }));
        await(entered);

        var sameSession = workers.submit(() ->
                endpoint.executeOnceAndReturn(new RequestId(firstToken, 1), () -> "same session"));
        var otherSession = workers.submit(() ->
                endpoint.executeOnceAndReturn(new RequestId(secondToken, 0), () -> "other session"));

        assertEquals("same session", sameSession.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("other session", otherSession.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(first.isDone());
        finish.countDown();
        assertEquals("first", first.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void aConcurrentDuplicateWaitsForTheOriginalFailure() throws Exception {
        var requestId = new RequestId(endpoint.connect(), 0);
        var failure = new IllegalStateException("Application operation failed");
        var first = workers.submit(() -> endpoint.executeOnceAndReturn(requestId, () -> {
            pause(entered, finish);
            throw failure;
        }));
        await(entered);

        var duplicate = startWaiting(() -> endpoint.executeOnceAndReturn(requestId, () -> {
            throw new AssertionError("Duplicate operation ran");
        }));
        finish.countDown();

        assertSame(failure, assertThrows(ExecutionException.class,
                () -> first.get(WAIT_SECONDS, TimeUnit.SECONDS)).getCause());
        assertSame(failure, assertThrows(ExecutionException.class,
                () -> duplicate.get(WAIT_SECONDS, TimeUnit.SECONDS)).getCause());
    }

    @Test
    public void recoveryRevokesTheTokenAndWaitsForAllItsActiveOperations() throws Exception {
        var oldToken = endpoint.connect();
        var otherToken = endpoint.connect();
        var first = workers.submit(() -> endpoint.executeOnce(new RequestId(oldToken, 0), () ->
                pause(entered, finish)));
        var second = workers.submit(() -> endpoint.executeOnce(new RequestId(oldToken, 1), () ->
                pause(secondEntered, finishSecond)));
        await(entered);
        await(secondEntered);

        var recovery = startWaiting(() -> endpoint.recover(oldToken));
        var repeatedRevocation = startWaiting(() -> {
            endpoint.disconnect(oldToken);
            return null;
        });
        assertThrows(SessionUnavailableException.class,
                () -> endpoint.executeOnce(new RequestId(oldToken, 2), () -> fail("Revoked request ran")));
        assertEquals("available", endpoint.executeOnceAndReturn(new RequestId(otherToken, 0), () -> "available"));

        finish.countDown();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertFalse(recovery.isDone());
        assertFalse(repeatedRevocation.isDone());
        finishSecond.countDown();
        second.get(WAIT_SECONDS, TimeUnit.SECONDS);

        var newToken = recovery.get(WAIT_SECONDS, TimeUnit.SECONDS);
        repeatedRevocation.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotEquals(oldToken, newToken);
        assertEquals("recovered", endpoint.executeOnceAndReturn(new RequestId(newToken, 0), () -> "recovered"));
    }

    @Test
    public void closingRejectsNewWorkAndWaitsForActiveOperations() throws Exception {
        var token = endpoint.connect();
        var first = workers.submit(() -> endpoint.executeOnce(new RequestId(token, 0), () ->
                pause(entered, finish)));
        await(entered);

        var closing = startWaiting(() -> {
            endpoint.close();
            return null;
        });
        assertThrows(NodeRecoveryRequiredException.class, endpoint::connect);
        assertThrows(SessionUnavailableException.class,
                () -> endpoint.executeOnce(new RequestId(token, 1), () -> fail("Revoked request ran")));

        finish.countDown();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        closing.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    public void aSlowRequestKeepsItsSessionAndItsOutcomeThroughExpirationOfNewerRequests() throws Exception {
        var token = endpoint.connect();
        var slowId = new RequestId(token, 0);
        var laterId = new RequestId(token, 1);
        var slow = workers.submit(() -> endpoint.executeOnceAndReturn(slowId, () -> {
            pause(entered, finish);
            return "slow";
        }));
        await(entered);
        endpoint.executeOnce(laterId, () -> {});
        now.set(REQUEST_TIMEOUT + SESSION_TIMEOUT + 1);
        assertThrows(NodeRecoveryRequiredException.class,
                () -> endpoint.executeOnce(laterId, () -> fail("Expired request ran again")));
        var duplicate = startWaiting(() -> endpoint.executeOnceAndReturn(slowId, () -> {
            throw new AssertionError("Slow request ran again");
        }));

        finish.countDown();
        assertEquals("slow", slow.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("slow", duplicate.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("slow", endpoint.executeOnceAndReturn(slowId, () -> {
            throw new AssertionError("Slow request ran again");
        }));
        now.addAndGet(REQUEST_TIMEOUT + 1);
        assertThrows(NodeRecoveryRequiredException.class,
                () -> endpoint.executeOnce(slowId, () -> fail("Expired request ran again")));
    }

    private static <T> FutureTask<T> startWaiting(Callable<T> call) {
        var task = new FutureTask<>(call);
        var thread = Thread.ofPlatform().daemon().start(task);
        long started = System.nanoTime();
        // Wait until the call has parked, rather than relying on how soon it starts.
        while (thread.getState() != Thread.State.WAITING) {
            assertFalse("Call finished early", task.isDone());
            assertTrue("Call did not wait", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(WAIT_SECONDS));
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return task;
    }

    private static void pause(CountDownLatch entered, CountDownLatch finish) {
        entered.countDown();
        await(finish);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("Timed out waiting for the test gate", latch.await(WAIT_SECONDS * 2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
