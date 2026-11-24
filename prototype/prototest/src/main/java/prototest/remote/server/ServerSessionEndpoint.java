package prototest.remote.server;

import prototest.remote.api.RequestId;
import prototest.remote.api.SessionNetworkInterface;
import prototest.remote.api.errors.NodeRecoveryRequiredException;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Serializes session changes and request execution. */
public final class ServerSessionEndpoint implements SessionNetworkInterface, AutoCloseable {
    private static final long DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(30).toMillis();

    // Session changes, request execution and cleanup share this endpoint's monitor.
    private final SessionRegistry sessions;
    private final ScheduledExecutorService cleanup;
    private boolean closed;

    /**
     * Starts an endpoint with a 30-minute timeout for empty sessions.
     *
     * @param requestTimeout the retention time for saved results in milliseconds
     */
    public ServerSessionEndpoint(long requestTimeout) {
        this(requestTimeout, Clock.systemUTC());
    }

    /**
     * Starts an endpoint with a 30-minute timeout for empty sessions and a supplied clock.
     *
     * @param requestTimeout the retention time for saved results in milliseconds
     * @param clock the clock used to measure both retention times
     */
    public ServerSessionEndpoint(long requestTimeout, Clock clock) {
        this(requestTimeout, DEFAULT_SESSION_TIMEOUT, clock);
    }

    /**
     * Starts an endpoint with separate retention times for results and empty sessions.
     *
     * @param requestTimeout the retention time for saved results in milliseconds
     * @param sessionTimeout the positive retention time for empty sessions in milliseconds
     */
    public ServerSessionEndpoint(long requestTimeout, long sessionTimeout) {
        this(requestTimeout, sessionTimeout, Clock.systemUTC());
    }

    /**
     * Starts an endpoint and its periodic cleanup task.
     *
     * @param requestTimeout the retention time for saved results in milliseconds
     * @param sessionTimeout the positive retention time for empty sessions in milliseconds
     * @param clock the clock used to measure both retention times
     */
    public ServerSessionEndpoint(long requestTimeout, long sessionTimeout, Clock clock) {
        sessions = new SessionRegistry(requestTimeout, sessionTimeout, clock);
        cleanup = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("session-cleanup").factory());
        long cleanupInterval = Math.max(1, Math.min(requestTimeout, sessionTimeout));
        cleanup.scheduleWithFixedDelay(this::removeExpiredSessions, cleanupInterval, cleanupInterval,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized UUID connect() {
        requireOpen();
        return sessions.open();
    }

    @Override
    public synchronized UUID recover(UUID previousToken) {
        requireOpen();
        sessions.revoke(previousToken);
        return sessions.open();
    }

    @Override
    public synchronized void disconnect(UUID token) {
        sessions.revoke(token);
    }

    /** Stops cleanup and revokes all sessions. Further connections are rejected. */
    @Override
    public synchronized void close() {
        closed = true;
        sessions.revokeAll();
        cleanup.shutdown();
    }

    private synchronized void removeExpiredSessions() {
        sessions.removeExpired();
    }

    private void requireOpen() {
        if (closed) {
            throw new NodeRecoveryRequiredException("Server endpoint is closed");
        }
    }

    /**
     * Runs a new operation under the session lock and records its successful completion.
     * Retries of a completed operation return without running it again.
     *
     * @param requestId the ID reused by retries of this operation
     * @param operation the operation to run for a new request
     * @throws NodeRecoveryRequiredException if the session or request is unavailable
     */
    public void executeOnce(RequestId requestId, Runnable operation) {
        executeOnceAndReturn(requestId, () -> {
            operation.run();
            return null;
        });
    }

    /**
     * Runs a new request or returns its saved result while holding the session lock.
     * Exceptions propagate to the caller. Only successful results are saved.
     *
     * @param requestId the ID reused by retries of this operation
     * @param operation the operation to run for a new request
     * @param <T> the result type
     * @return the new or saved result
     * @throws NodeRecoveryRequiredException if the session or request is unavailable
     */
    public synchronized <T> T executeOnceAndReturn(RequestId requestId, Supplier<T> operation) {
        return sessions.executeOnceAndReturn(requestId, operation);
    }
}
