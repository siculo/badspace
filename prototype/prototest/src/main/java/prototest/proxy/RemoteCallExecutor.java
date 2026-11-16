package prototest.proxy;

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
 * Executes concurrent remote calls with request IDs, retries and local recovery state.
 * Each node proxy owns a separate executor. Calls are synchronous for the application,
 * but each call runs its transport operation on a private virtual worker and receives
 * its result through a {@link CompletableFuture}.
 *
 * <h2>Errors and retries</h2>
 * <ul>
 *   <li>Service errors, such as a missing partition, reach the caller without making
 *       recovery necessary.</li>
 *   <li>{@link RetryableCommunicationException} causes a retry with the same request ID
 *       while the retry timeout allows it. A successful retry leaves the executor usable
 *       unless another call has already failed the session.</li>
 *   <li>When the retry timeout expires, the caller receives a
 *       {@link NodeRecoveryRequiredException} with the last retryable error as its cause.</li>
 *   <li>A {@code NodeRecoveryRequiredException} from the transport reaches the caller
 *       unchanged and makes recovery necessary.</li>
 *   <li>Other pending calls in the failed session receive a
 *       {@link ConnectionUnavailableException} with the first final error as its cause.
 *       They do not wait for the transport to return.</li>
 *   <li>New calls in a failed session are rejected with
 *       {@code ConnectionUnavailableException} before invoking the transport.</li>
 * </ul>
 *
 * <p>The retry timeout uses monotonic time and starts when the transport worker begins
 * its work. Time is checked after a retryable error: this timeout limits retries but
 * does not cancel a blocked transport call. Final errors are treated conservatively.
 * Even a last error reporting a lost request does not prove that earlier attempts
 * failed to reach the node.
 *
 * <h2>Concurrent calls and cancellation</h2>
 * <p>Call registration, result delivery and session failure use the same lock. Transport
 * operations run outside that lock. A result completed before the session fails remains
 * valid; calls still pending receive the failure. Results already delivered are not
 * withdrawn, and server effects are not rolled back.
 *
 * <p>The first final error completes the other pending results immediately, without
 * transport waits or polling. Delivery still depends on local thread scheduling.
 * Cancellation also attempts to interrupt private transport workers, which can release
 * simulated waits such as {@link Thread#sleep(long)}. Application callers are never
 * interrupted by another call. A transport that ignores interruption may keep running
 * and may still produce effects on the server, but cannot replace a completed result.
 *
 * <p>If an application caller is interrupted while waiting, its interrupt flag is
 * preserved and the call fails with {@code NodeRecoveryRequiredException}: its remote
 * outcome is uncertain. A caller already interrupted at entry also fails the session
 * without starting its transport operation. Notifying other callers of a session
 * failure does not change their interrupt flags.
 *
 * <h2>Local recovery</h2>
 * <p>{@link #recoveryRequired()} reports whether the current session has failed.
 * {@link #recover()} replaces a failed session with a healthy one without waiting for
 * old workers to finish. It does nothing while healthy. Old calls remain failed, stop
 * retrying and cannot change the new session through late results or errors. The UUID
 * and request sequence are retained across recovery to avoid reusing request IDs
 * already registered on the server.
 *
 * <p>Recovery only resets this executor's local state. It does not restore the remote
 * node from a persistent snapshot or reset other proxies connected to the same node.
 */
final class RemoteCallExecutor {

    // Concurrent calls share this counter; retries keep their locally allocated ID.
    private final AtomicLong idGenerator;
    private final UUID proxyId = UUID.randomUUID();
    private final long timeout;
    // Session state and pending call registration are protected by stateLock.
    private final Object stateLock = new Object();
    private Session session = new Session();

    /**
     * Creates an independent call session and request ID sequence.
     *
     * @param timeout the retry timeout in milliseconds
     * @throws IllegalArgumentException if timeout is negative
     */
    RemoteCallExecutor(long timeout) {
        this.idGenerator = new AtomicLong();
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout: " + timeout);
        }
        this.timeout = TimeUnit.MILLISECONDS.toNanos(timeout);
    }

    /**
     * Reports whether a final protocol error requires recovery.
     *
     * @return whether the current call session has failed
     */
    boolean recoveryRequired() {
        synchronized (stateLock) {
            return session.failure != null;
        }
    }

    /**
     * Clears the local error so new calls can proceed. Does nothing while healthy.
     * Old calls remain cancelled. This does not restore or change the remote node.
     */
    void recover() {
        synchronized (stateLock) {
            if (session.failure != null) {
                session = new Session();
            }
        }
    }

    private RequestId nextRequestId() {
        long sequence = idGenerator.getAndUpdate(Math::incrementExact);
        return new RequestId(proxyId, sequence);
    }

    /**
     * Executes an operation that does not return a value.
     *
     * @param operation the transport operation, receiving the same ID on every retry
     * @throws ConnectionUnavailableException if the session is already failed or
     *         another pending call fails it
     * @throws NodeRecoveryRequiredException if this call has a final protocol error,
     *         exhausts its retry timeout or its caller is interrupted
     */
    void execute(Consumer<RequestId> operation) {
        executeAndReturn(requestId -> {
            operation.accept(requestId);
            return null;
        });
    }

    /**
     * Executes an operation and waits for its result.
     *
     * @param operation the transport operation, receiving the same ID on every retry
     * @param <T> the result type
     * @return the transport result, which may be null
     * @throws ConnectionUnavailableException if the session is already failed or
     *         another pending call fails it
     * @throws NodeRecoveryRequiredException if this call has a final protocol error,
     *         exhausts its retry timeout or its caller is interrupted
     */
    <T> T executeAndReturn(Function<RequestId, T> operation) {
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
