package badspace.remote.server;

import badspace.remote.api.RequestId;
import badspace.remote.api.SessionNetworkInterface;
import badspace.remote.api.errors.NodeRecoveryRequiredException;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Runs independent requests concurrently and reuses outcomes for duplicate requests. */
public final class ServerSessionEndpoint implements SessionNetworkInterface, AutoCloseable {
    private static final long DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(30).toMillis();

    private final SessionRegistry sessions;
    private final ScheduledExecutorService cleanup;

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
        cleanup.scheduleWithFixedDelay(sessions::removeExpired, cleanupInterval, cleanupInterval,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public UUID connect() {
        return sessions.open();
    }

    @Override
    public UUID recover(UUID previousToken) {
        sessions.revoke(previousToken);
        return sessions.open();
    }

    @Override
    public void disconnect(UUID token) {
        sessions.revoke(token);
    }

    /** Revokes all sessions, waits for their active operations and stops cleanup. */
    @Override
    public void close() {
        try {
            sessions.close();
        } finally {
            cleanup.shutdown();
        }
    }

    /**
     * Runs a new operation and saves its outcome.
     * A retry returns normally or throws the saved application exception without running
     * the operation again.
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
     * Runs a new request concurrently with other requests. A duplicate waits for the
     * original operation if it is still in progress, then receives the same outcome.
     * Saves return values and application runtime exceptions for the same retention time.
     * Communication exceptions and other throwables propagate without being saved.
     *
     * @param requestId the ID reused by retries of this operation
     * @param operation the operation to run for a new request
     * @param <T> the result type
     * @return the new or saved result
     * @throws NodeRecoveryRequiredException if the session or request is unavailable
     */
    public <T> T executeOnceAndReturn(RequestId requestId, Supplier<T> operation) {
        return sessions.executeOnceAndReturn(requestId, operation);
    }
}
