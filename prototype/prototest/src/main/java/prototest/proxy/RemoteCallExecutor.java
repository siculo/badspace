package prototest.proxy;

import prototest.api.RequestId;
import prototest.api.errors.network.ConnectionUnavailableException;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.api.errors.network.RetryableCommunicationException;
import prototest.api.errors.network.SessionUnavailableException;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
 * <h2>Session lifecycle</h2>
 * <p>The executor starts disconnected. A successful connection or recovery installs
 * a server token and starts a new request sequence. Connection changes run outside the
 * state lock, with new calls blocked until the transition finishes. Old workers retain
 * their original token and cannot change the result or state of a newer session.
 */
final class RemoteCallExecutor {

    private final long timeout;
    private final LongSupplier nanoTime;
    // Callers and workers access the session, transitions and pending calls under stateLock.
    private final Object stateLock = new Object();
    private Session session;
    private boolean transitioning;
    private Thread recoveryCaller;
    private boolean disconnectPending;

    /**
     * Creates a disconnected executor.
     *
     * @param timeout the retry timeout in milliseconds
     * @throws IllegalArgumentException if timeout is negative
     */
    RemoteCallExecutor(long timeout) {
        this(timeout, System::nanoTime);
    }

    /**
     * Creates a call executor with a monotonic time source.
     *
     * @param timeout the retry timeout in milliseconds
     * @param nanoTime the monotonic time source in nanoseconds
     * @throws IllegalArgumentException if timeout is negative
     * @throws NullPointerException if the time source is null
     */
    RemoteCallExecutor(long timeout, LongSupplier nanoTime) {
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout: " + timeout);
        }
        this.timeout = TimeUnit.MILLISECONDS.toNanos(timeout);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Reports whether a final protocol error requires recovery.
     *
     * @return whether the current call session has failed
     */
    boolean recoveryRequired() {
        synchronized (stateLock) {
            return session != null && (session.failure != null || recoveryCaller != null) && !disconnectPending;
        }
    }

    void connect(Supplier<UUID> open) {
        synchronized (stateLock) {
            requireNoTransition();
            if (session != null) {
                throw new IllegalStateException("Proxy must be disconnected to connect");
            }
            transitioning = true;
        }
        openSession(open);
    }

    boolean recover(Function<UUID, UUID> renew, BooleanSupplier repair) {
        UUID previousToken;
        synchronized (stateLock) {
            requireNoTransition();
            if (session == null || disconnectPending) {
                throw new IllegalStateException("Proxy has no session to recover");
            }
            if (session.failure == null) {
                return true;
            }
            previousToken = session.token;
            transitioning = true;
        }
        try {
            var renewed = new Session(Objects.requireNonNull(renew.apply(previousToken), "session token"));
            synchronized (stateLock) {
                session = renewed;
                recoveryCaller = Thread.currentThread();
            }
            try {
                boolean consistent = repair.getAsBoolean();
                synchronized (stateLock) {
                    if (renewed.failure != null) {
                        throw renewed.failure;
                    }
                    if (!consistent) {
                        renewed.failure = new NodeRecoveryRequiredException("Node repair is incomplete");
                    }
                    return consistent;
                }
            } catch (RuntimeException | Error error) {
                synchronized (stateLock) {
                    if (renewed.failure == null) {
                        renewed.failure = new NodeRecoveryRequiredException("Node repair failed", error);
                    }
                }
                throw error;
            }
        } finally {
            synchronized (stateLock) {
                recoveryCaller = null;
                transitioning = false;
            }
        }
    }

    void disconnect(Consumer<UUID> revoke) {
        Session closing;
        synchronized (stateLock) {
            requireNoTransition();
            if (session == null) {
                return;
            }
            transitioning = true;
            disconnectPending = true;
            closing = session;
            if (closing.failure == null) {
                closing.failure = new NodeRecoveryRequiredException("Proxy is disconnecting");
                cancelCalls(closing, null,
                        new ConnectionUnavailableException("Connection unavailable: proxy is disconnecting", null));
            }
        }
        boolean revoked = false;
        try {
            revoke.accept(closing.token);
            revoked = true;
        } finally {
            synchronized (stateLock) {
                if (revoked) {
                    session = null;
                    disconnectPending = false;
                }
                transitioning = false;
            }
        }
    }

    private void openSession(Supplier<UUID> open) {
        Session opened = null;
        try {
            opened = new Session(Objects.requireNonNull(open.get(), "session token"));
        } finally {
            synchronized (stateLock) {
                if (opened != null) {
                    session = opened;
                }
                transitioning = false;
            }
        }
    }

    private void requireNoTransition() {
        if (transitioning) {
            throw new IllegalStateException("A connection change is already in progress");
        }
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
            if ((transitioning && recoveryCaller != Thread.currentThread()) || disconnectPending || session == null) {
                throw new ConnectionUnavailableException("Connection unavailable: proxy has no usable session", null);
            }
            if (session.failure != null) {
                throw new ConnectionUnavailableException(session.failure);
            }
            var requestId = new RequestId(session.token, session.nextSequence);
            session.nextSequence = Math.incrementExact(session.nextSequence);
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
        try {
            boolean retried = false;
            long startTime = nanoTime.getAsLong();
            while (!call.result.isDone()) {
                try {
                    T result = operation.apply(requestId);
                    synchronized (stateLock) {
                        call.result.complete(result);
                    }
                    return;
                } catch (RetryableCommunicationException error) {
                    if (nanoTime.getAsLong() - startTime >= timeout) {
                        failSession(call, new NodeRecoveryRequiredException("Unrecoverable timeout", error));
                        return;
                    }
                    retried = true;
                } catch (SessionUnavailableException error) {
                    failSession(call, retried
                            ? new NodeRecoveryRequiredException("Session unavailable after retry", error)
                            : error);
                    return;
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
            cancelCalls(failedSession, failedCall, new ConnectionUnavailableException(error));
        }
    }

    private void cancelCalls(Session session, Call<?> failedCall, ConnectionUnavailableException unavailable) {
        for (Call<?> call : session.calls) {
            if (!call.result.isDone()) {
                call.result.completeExceptionally(call == failedCall ? session.failure : unavailable);
                // Only private transport workers are interrupted, never application callers.
                if (call.worker != null && call.worker != Thread.currentThread()) {
                    call.worker.interrupt();
                }
            }
        }
    }

    private static final class Session {
        private final UUID token;
        private final Set<Call<?>> calls = new HashSet<>();
        private long nextSequence;
        private NodeRecoveryRequiredException failure;

        private Session(UUID token) {
            this.token = token;
        }
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
