package prototest.proxy;

import prototest.api.NodeNetworkInterface;
import prototest.api.RequestId;
import prototest.api.errors.network.RetryableCommunicationException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Controls request and response delivery without simulated latency.
 * Each attempt atomically takes the first unused matching plan, in registration order.
 * Retries are separate attempts, even when they have the same request ID. Attempts
 * without a matching plan deliver both messages immediately. Overlapping selectors
 * follow attempt arrival order, not application thread order.
 *
 * <p>Registration, selection and verification use this instance's monitor. Selectors
 * run under that monitor and must be short, side-effect free and non-blocking.
 * Endpoint calls and delivery waits do not hold it. Each exchange point has its own
 * monitor, so unrelated attempts can advance independently.
 *
 * <p>A held message remains held even if its transport worker is interrupted. The
 * interrupt flag is restored after the test releases it. This allows tests to deliver
 * late messages after proxy cancellation or recovery. Tests must release held messages
 * in cleanup, including when an assertion fails.
 *
 * <p>Endpoint exceptions propagate directly without reaching the response point.
 * In particular, losing a response containing a service error is not simulated.
 */
public final class FakeNetworkInterface implements NodeNetworkInterface {
    private final NodeNetworkInterface node;
    // Tests register plans; transport workers select them. Both use this monitor.
    private final List<AttemptPlan> plans = new ArrayList<>();

    /** Delivery of one request or response. */
    public enum Delivery {
        /** Deliver immediately. */
        DELIVER,
        /** Report a lost message immediately. */
        DROP,
        /** Wait until the test chooses delivery or loss. */
        HOLD
    }

    /** Identifies the endpoint method, without deciding how it is executed. */
    public enum Operation {
        CREATE_PARTITION, DESTROY_PARTITION, PARTITION_EXISTS,
        WRITE_TO_PARTITION, COMMIT_PARTITION, READ_PARTITION_SNAPSHOT
    }

    /**
     * Identifies one transport attempt. Retries keep the same request ID.
     *
     * @param operation the endpoint method
     * @param requestId the logical request ID
     * @param partitionName the partition argument, unchanged from the caller
     */
    public record Invocation(Operation operation, RequestId requestId, String partitionName) {
    }

    /**
     * Creates a controllable network around an endpoint.
     *
     * @param node the endpoint to invoke after request delivery
     * @throws NullPointerException if node is null
     */
    public FakeNetworkInterface(NodeNetworkInterface node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    /**
     * Registers a plan for one matching attempt. Later registrations do not change
     * attempts that already selected their plan.
     *
     * @param selector matches the operation, request ID or partition
     * @param request request delivery mode
     * @param response response delivery mode
     * @return the plan's observation and release points
     * @throws NullPointerException if any argument is null
     */
    public synchronized AttemptPlan expect(Predicate<Invocation> selector,
                                            Delivery request, Delivery response) {
        var plan = new AttemptPlan(Objects.requireNonNull(selector, "selector"),
                Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(response, "response"));
        plans.add(plan);
        return plan;
    }

    /**
     * Checks that every registered plan was consumed and its attempt has finished.
     * A dropped request or an endpoint exception can finish an attempt without a response.
     *
     * @throws IllegalStateException if a plan is unused or an attempt is still running
     */
    public synchronized void verify() {
        for (int index = 0; index < plans.size(); index++) {
            AttemptPlan plan = plans.get(index);
            if (!plan.selected) {
                throw new IllegalStateException("Unused attempt plan: " + index);
            }
            if (!plan.finished) {
                throw new IllegalStateException("Unfinished attempt plan: " + index);
            }
        }
    }

    @Override
    public void createPartition(RequestId requestId, String name) {
        execute(new Invocation(Operation.CREATE_PARTITION, requestId, name),
                () -> node.createPartition(requestId, name));
    }

    @Override
    public void destroyPartition(RequestId requestId, String name) {
        execute(new Invocation(Operation.DESTROY_PARTITION, requestId, name),
                () -> node.destroyPartition(requestId, name));
    }

    @Override
    public boolean partitionExists(RequestId requestId, String name) {
        return executeAndReturn(new Invocation(Operation.PARTITION_EXISTS, requestId, name),
                () -> node.partitionExists(requestId, name));
    }

    @Override
    public void writeToPartition(RequestId requestId, String name, String data) {
        execute(new Invocation(Operation.WRITE_TO_PARTITION, requestId, name),
                () -> node.writeToPartition(requestId, name, data));
    }

    @Override
    public void commitPartition(RequestId requestId, String name) {
        execute(new Invocation(Operation.COMMIT_PARTITION, requestId, name),
                () -> node.commitPartition(requestId, name));
    }

    @Override
    public String readPartitionSnapshot(RequestId requestId, String name) {
        return executeAndReturn(new Invocation(Operation.READ_PARTITION_SNAPSHOT, requestId, name),
                () -> node.readPartitionSnapshot(requestId, name));
    }

    private synchronized AttemptPlan select(Invocation invocation) {
        for (AttemptPlan plan : plans) {
            if (!plan.selected && plan.selector.test(invocation)) {
                plan.selected = true;
                return plan;
            }
        }
        return null;
    }

    private void execute(Invocation invocation, Runnable operation) {
        executeAndReturn(invocation, () -> {
            operation.run();
            return null;
        });
    }

    private <T> T executeAndReturn(Invocation invocation, Supplier<T> operation) {
        AttemptPlan plan = select(invocation);
        if (plan == null) {
            return operation.get();
        }
        try {
            plan.request.pass(invocation, "Request missing");
            T result = operation.get();
            plan.response.pass(invocation, "Response missing");
            return result;
        } finally {
            plan.finish();
        }
    }

    /** Observation and delivery controls for one attempt. */
    public static final class AttemptPlan {
        private final Predicate<Invocation> selector;
        private final ExchangePoint request;
        private final ExchangePoint response;
        // Selection uses the network monitor. The worker publishes completion to verify().
        private boolean selected;
        private volatile boolean finished;

        private AttemptPlan(Predicate<Invocation> selector, Delivery request, Delivery response) {
            this.selector = selector;
            this.request = new ExchangePoint(request);
            this.response = new ExchangePoint(response);
        }

        /**
         * Waits until delivery finishes or the attempt ends with an error. This does
         * not wait for the proxy to process the result or for its caller to finish.
         *
         * @param timeout maximum real waiting time; zero only checks completion
         * @throws IllegalArgumentException if timeout is negative
         * @throws IllegalStateException if the attempt does not finish before timeout
         * @throws InterruptedException if the observing test thread is interrupted
         */
        public synchronized void awaitCompletion(Duration timeout) throws InterruptedException {
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("Negative completion timeout: " + timeout);
            }
            long remaining = timeout.toNanos();
            long start = System.nanoTime();
            while (!finished) {
                if (remaining <= 0) {
                    throw new IllegalStateException("Attempt did not finish within " + timeout);
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = timeout.toNanos() - (System.nanoTime() - start);
            }
        }

        private synchronized void finish() {
            finished = true;
            notifyAll();
        }

        /**
         * Returns the point before invoking the endpoint.
         *
         * @return the request delivery point
         */
        public ExchangePoint request() {
            return request;
        }

        /**
         * Returns the point after a successful endpoint call, before returning its result.
         *
         * @return the response delivery point
         */
        public ExchangePoint response() {
            return response;
        }
    }

    /**
     * Observes arrival and releases a held message. Arrival remains observable after
     * delivery or loss. Decisions require arrival and can be made only once on a HOLD point.
     */
    public static final class ExchangePoint {
        // Worker arrival and test decisions are read and written under this monitor.
        private Invocation invocation;
        private Delivery delivery;

        private ExchangePoint(Delivery delivery) {
            this.delivery = delivery;
        }

        /**
         * Waits for this message to arrive without releasing it. A point that cannot be
         * reached, such as the response to a dropped request, also times out.
         *
         * @param timeout maximum real waiting time; zero only checks current arrival
         * @return the arriving attempt
         * @throws IllegalArgumentException if timeout is negative
         * @throws IllegalStateException if the message does not arrive before timeout
         * @throws InterruptedException if the observing test thread is interrupted
         */
        public synchronized Invocation awaitArrival(Duration timeout) throws InterruptedException {
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("Negative arrival timeout: " + timeout);
            }
            long remaining = timeout.toNanos();
            long start = System.nanoTime();
            while (invocation == null) {
                if (remaining <= 0) {
                    throw new IllegalStateException("Message did not arrive within " + timeout);
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = timeout.toNanos() - (System.nanoTime() - start);
            }
            return invocation;
        }

        /**
         * Releases a held message for delivery.
         *
         * @throws IllegalStateException if the message has not arrived or is not held
         */
        public synchronized void deliver() {
            decide(Delivery.DELIVER);
        }

        /**
         * Releases a held message as lost, causing a retryable communication error.
         *
         * @throws IllegalStateException if the message has not arrived or is not held
         */
        public synchronized void drop() {
            decide(Delivery.DROP);
        }

        private void decide(Delivery decision) {
            if (invocation == null || delivery != Delivery.HOLD) {
                throw new IllegalStateException("Message is not waiting for a decision");
            }
            delivery = decision;
            notifyAll();
        }

        private synchronized void pass(Invocation invocation, String lossMessage) {
            this.invocation = invocation;
            notifyAll();
            boolean interrupted = false;
            try {
                while (delivery == Delivery.HOLD) {
                    try {
                        wait();
                    } catch (InterruptedException error) {
                        // Cancellation must not prevent tests from delivering late messages.
                        interrupted = true;
                    }
                }
                if (delivery == Delivery.DROP) {
                    throw new RetryableCommunicationException(lossMessage);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
