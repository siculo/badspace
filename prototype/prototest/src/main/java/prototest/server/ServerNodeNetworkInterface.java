package prototest.server;

import prototest.api.NodeNetworkInterface;
import prototest.api.RequestId;
import prototest.api.errors.network.NodeRecoveryRequiredException;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Serializes session changes and request execution. */
public class ServerNodeNetworkInterface implements NodeNetworkInterface, AutoCloseable {
    private static final long DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(30).toMillis();

    // Network calls and periodic cleanup share this endpoint's monitor.
    private final NodeServer nodeServer;
    private final SessionRegistry sessions;
    private final ScheduledExecutorService cleanup;
    private boolean closed;

    public ServerNodeNetworkInterface(NodeServer nodeServer, long requestTimeout) {
        this(nodeServer, requestTimeout, Clock.systemUTC());
    }

    public ServerNodeNetworkInterface(NodeServer nodeServer, long requestTimeout, Clock clock) {
        this(nodeServer, requestTimeout, DEFAULT_SESSION_TIMEOUT, clock);
    }

    public ServerNodeNetworkInterface(NodeServer nodeServer, long requestTimeout, long sessionTimeout) {
        this(nodeServer, requestTimeout, sessionTimeout, Clock.systemUTC());
    }

    /**
     * Starts an endpoint and its periodic cleanup task.
     *
     * @param nodeServer the server that executes the requests
     * @param requestTimeout the retention time for saved results in milliseconds
     * @param sessionTimeout the positive retention time for empty sessions in milliseconds
     * @param clock the clock used to measure both retention times
     */
    public ServerNodeNetworkInterface(NodeServer nodeServer, long requestTimeout, long sessionTimeout, Clock clock) {
        this.nodeServer = Objects.requireNonNull(nodeServer, "nodeServer");
        sessions = new SessionRegistry(requestTimeout, sessionTimeout, clock);
        cleanup = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("session-cleanup").factory());
        long cleanupInterval = Math.max(1, Math.min(requestTimeout, sessionTimeout));
        cleanup.scheduleWithFixedDelay(this::removeExpiredSessions, cleanupInterval, cleanupInterval,
                TimeUnit.MILLISECONDS);
    }

    public ServerNodeNetworkInterface(long requestTimeout) {
        this(new NodeServer(), requestTimeout);
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

    @Override
    public synchronized void createPartition(RequestId requestId, String name) {
        sessions.executeOnce(
                requestId,
                () -> nodeServer.createPartition(name)
        );
    }

    @Override
    public synchronized void destroyPartition(RequestId requestId, String name) {
        sessions.executeOnce(
                requestId,
                () -> nodeServer.destroyPartition(name)
        );
    }

    @Override
    public synchronized boolean partitionExists(RequestId requestId, String name) {
        return sessions.executeOnceAndReturn(
                requestId,
                () -> nodeServer.partitionExists(name)
        );
    }

    @Override
    public synchronized void writeToPartition(RequestId requestId, String name, String data) {
        sessions.executeOnce(
                requestId,
                () -> nodeServer.writeToPartition(name, data)
        );
    }

    @Override
    public synchronized void commitPartition(RequestId requestId, String name) {
        sessions.executeOnce(
                requestId,
                () -> nodeServer.commitPartition(name)
        );
    }

    @Override
    public synchronized String readPartitionSnapshot(RequestId requestId, String name) {
        return sessions.executeOnceAndReturn(
                requestId,
                () -> nodeServer.readPartitionSnapshot(name)
        );
    }
}
