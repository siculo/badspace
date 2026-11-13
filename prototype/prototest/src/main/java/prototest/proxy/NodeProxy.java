package prototest.proxy;

import prototest.api.Node;
import prototest.api.NodeNetworkInterface;
import prototest.api.RequestId;
import prototest.api.errors.network.ConnectionUnavailableException;
import prototest.api.errors.network.RetryableCommunicationException;
import prototest.api.errors.network.NodeRecoveryRequiredException;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Allows concurrent calls to a node. A final protocol error requires node recovery
 * and blocks further attempts through this proxy. Service errors leave it usable.
 * A final error completes pending calls with ConnectionUnavailableException without
 * waiting for the transport. Recovery starts a new session; late transport results
 * cannot change it. Application threads are never interrupted by another call.
 */
public class NodeProxy implements Node {

    private final NodeNetworkInterface node;
    // Concurrent calls share this counter; retries keep their locally allocated ID.
    private final AtomicLong idGenerator;
    private final UUID proxyId = UUID.randomUUID();
    private final long timeout;
    // Session state and pending call registration are protected by stateLock.
    private final Object stateLock = new Object();
    private Session session = new Session();


    public NodeProxy(NodeNetworkInterface node, long timeout) {
        this.node = node;
        this.idGenerator = new AtomicLong();
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout: " + timeout);
        }
        this.timeout = TimeUnit.MILLISECONDS.toNanos(timeout);
    }

    /** Returns whether a final protocol error has made node recovery necessary. */
    public boolean recoveryRequired() {
        synchronized (stateLock) {
            return session.failure != null;
        }
    }

    /**
     * Clears the local error so new calls can proceed. Does nothing while healthy.
     * Old calls remain cancelled. This does not restore or change the remote node.
     */
    public void recover() {
        synchronized (stateLock) {
            if (session.failure != null) {
                session = new Session();
            }
        }
    }

    @Override
    public void createPartition(String name) {
        execute((requestId) -> node.createPartition(requestId, name));
    }

    @Override
    public void destroyPartition(String name) {
        execute((requestId) -> node.destroyPartition(requestId, name));
    }

    @Override
    public boolean partitionExists(String name) {
        return executeAndReturn((requestId) -> node.partitionExists(requestId, name));
    }

    @Override
    public void writeToPartition(String name, String data) {
        execute((requestId) -> node.writeToPartition(requestId, name, data));
    }

    @Override
    public void commitPartition(String name) {
        execute((requestId) -> node.commitPartition(requestId, name));
    }

    @Override
    public String readPartitionSnapshot(String name) {
        return executeAndReturn((requestId) -> node.readPartitionSnapshot(requestId, name));
    }

    private RequestId nextRequestId() {
        long sequence = idGenerator.getAndUpdate(Math::incrementExact);
        return new RequestId(proxyId, sequence);
    }

    private void execute(Consumer<RequestId> operation) {
        executeAndReturn(requestId -> {
            operation.accept(requestId);
            return null;
        });
    }

    private <T> T executeAndReturn(Function<RequestId, T> operation) {
        Call<T> call;
        synchronized (stateLock) {
            if (session.failure != null) {
                throw new ConnectionUnavailableException(session.failure);
            }
            RequestId requestId = nextRequestId();
            call = new Call<>(session);
            session.calls.add(call);
            if (Thread.currentThread().isInterrupted()) {
                failSession(call, new NodeRecoveryRequiredException("Call interrupted"));
            } else {
                call.worker = Thread.ofVirtual().unstarted(() -> runCall(call, requestId, operation));
                try {
                    call.worker.start();
                } catch (RuntimeException | Error error) {
                    session.calls.remove(call);
                    throw error;
                }
            }
        }
        try {
            return call.result.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            var failure = new NodeRecoveryRequiredException("Call interrupted", error);
            failSession(call, failure);
            throw failure;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (error.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new IllegalStateException("Unexpected call failure", error.getCause());
        } finally {
            synchronized (stateLock) {
                call.session.calls.remove(call);
            }
        }
    }

    private <T> void runCall(Call<T> call, RequestId requestId, Function<RequestId, T> operation) {
        long startTime = System.nanoTime();
        try {
            while (!call.result.isDone()) {
                try {
                    T result = operation.apply(requestId);
                    synchronized (stateLock) {
                        call.result.complete(result);
                    }
                    return;
                } catch (RetryableCommunicationException error) {
                    if (System.nanoTime() - startTime >= timeout) {
                        failSession(call, new NodeRecoveryRequiredException("Unrecoverable timeout", error));
                        return;
                    }
                } catch (NodeRecoveryRequiredException error) {
                    failSession(call, error);
                    return;
                }
            }
        } catch (RuntimeException | Error error) {
            synchronized (stateLock) {
                call.result.completeExceptionally(error);
            }
        }
    }

    private void failSession(Call<?> failedCall, NodeRecoveryRequiredException error) {
        synchronized (stateLock) {
            Session failedSession = failedCall.session;
            if (failedSession.failure != null) {
                return;
            }
            failedSession.failure = error;
            for (Call<?> call : failedSession.calls) {
                if (!call.result.isDone()) {
                    call.result.completeExceptionally(call == failedCall
                            ? error : new ConnectionUnavailableException(error));
                    // Only private transport workers are interrupted, never application callers.
                    if (call.worker != null && call.worker != Thread.currentThread()) {
                        call.worker.interrupt();
                    }
                }
            }
        }
    }

    private static final class Session {
        private final Set<Call<?>> calls = new HashSet<>();
        private NodeRecoveryRequiredException failure;
    }

    private static final class Call<T> {
        private final Session session;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private Thread worker;

        private Call(Session session) {
            this.session = session;
        }
    }
}
