package prototest.proxy;

import prototest.api.NodeNetworkInterface;
import prototest.api.errors.network.RetryableCommunicationException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Builds a controllable {@link NodeNetworkInterface} without simulated latency.
 * Methods are selected by invoking them on a recording proxy, for example
 * {@code wrap(endpoint).on(n -> n.createPartition(null, null)).onFirstCall().dropRequest().build()}.
 * Selector arguments are ignored; {@link RuleBuilder#withArgs(Predicate)} filters real arguments.
 *
 * <p>Every entry into the generated proxy counts as a call, including retries and lost
 * requests. Counts are per method, shared by all threads and independent of argument
 * filters and matching rules. The first matching rule wins. Rules remain registered;
 * unmatched calls deliver both messages. Build freezes configuration and is allowed once.
 *
 * <p>Configuration is single-threaded. After build, observation and network calls can
 * use the same builder from different threads. Call registration and rule selection
 * are atomic under this builder's monitor.
 * History conditions see entries strictly before the current call, including failed
 * and still pending calls. They do not imply completion or success. Argument predicates
 * run under the monitor and must be short, side-effect free and non-blocking.
 * Endpoint calls and message waits run outside that monitor.
 *
 * <p>{@link #awaitCall(Selector, int, Duration)} provides separate delivery controls for
 * each invocation, including those selected by an every-call rule. A held message ignores
 * transport interruption until the test releases it, then restores the interrupt flag.
 * Tests must release held messages in cleanup, including after assertion failures.
 * Endpoint exceptions propagate directly without reaching the response point, so a lost
 * response containing a service error is not simulated.
 */
public final class FakeNetworkInterface {
    private final NodeNetworkInterface node;
    // After build, counts, rule usage and call registration use this monitor.
    private final List<Rule> rules = new ArrayList<>();
    private final Map<Method, Integer> counts = new HashMap<>();
    private final Map<Method, List<Attempt>> calls = new HashMap<>();
    private boolean built;

    /** Delivery of one request or response. */
    public enum Delivery {
        /** Deliver immediately. */
        DELIVER,
        /** Report a lost message immediately. */
        DROP,
        /** Wait until the test chooses delivery or loss. */
        HOLD
    }

    /** Selects exactly one network method by invoking it; supplied arguments are ignored. */
    @FunctionalInterface
    public interface Selector {
        /**
         * Invokes the method to identify on a recording proxy, without calling the endpoint.
         *
         * @param node the recording proxy
         */
        void accept(NodeNetworkInterface node);
    }

    /**
     * Immutable metadata for one entry into the generated proxy.
     *
     * @param method the selected interface method
     * @param args actual arguments, copied on construction and access
     * @param callNumber the one-based method entry number, including retries
     */
    public record Invocation(Method method, Object[] args, int callNumber) {
        /** Copies the argument array to keep observations and predicates from changing the call. */
        public Invocation {
            args = args.clone();
        }

        /**
         * Returns a copy of the argument array.
         *
         * @return the arguments at method entry
         */
        @Override
        public Object[] args() {
            return args.clone();
        }
    }

    private record Selection(Invocation invocation, Map<Method, Integer> history) {
    }

    private static final class Rule {
        private final Method method;
        private final IntPredicate frequency;
        private final Predicate<Selection> condition;
        private final Delivery request;
        private final Delivery response;
        private boolean used;

        private Rule(Method method, IntPredicate frequency, Predicate<Selection> condition,
                     Delivery request, Delivery response) {
            this.method = method;
            this.frequency = frequency;
            this.condition = condition;
            this.request = request;
            this.response = response;
        }
    }

    private FakeNetworkInterface(NodeNetworkInterface node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    /**
     * Starts configuration of a network around an endpoint.
     *
     * @param node the endpoint invoked after request delivery
     * @return a new builder with independent history and controls
     * @throws NullPointerException if node is null
     */
    public static FakeNetworkInterface wrap(NodeNetworkInterface node) {
        return new FakeNetworkInterface(node);
    }

    /**
     * Starts a rule for a network method. By default it applies on every call.
     *
     * @param selector invokes exactly one network method; arguments are ignored
     * @return the rule builder
     * @throws IllegalArgumentException if the selector does not invoke exactly one network method
     * @throws IllegalStateException if the network was already built
     */
    public synchronized RuleBuilder on(Selector selector) {
        checkMutable();
        return new RuleBuilder(this, capture(selector));
    }

    /**
     * Freezes configuration and generates the network proxy. Object methods use proxy
     * identity and do not enter call history. No rules means normal delivery on every call.
     *
     * @return the generated network interface
     * @throws IllegalStateException if build was already called
     */
    public synchronized NodeNetworkInterface build() {
        checkMutable();
        var proxy = (NodeNetworkInterface) Proxy.newProxyInstance(
                NodeNetworkInterface.class.getClassLoader(),
                new Class<?>[]{NodeNetworkInterface.class}, (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> instance == args[0];
                            case "hashCode" -> System.identityHashCode(instance);
                            default -> "FakeNetworkInterface";
                        };
                    }
                    return invoke(method, args == null ? new Object[0] : args);
                });
        built = true;
        return proxy;
    }

    /**
     * Waits for a method entry. The returned attempt has its own request and response
     * points; use awaitArrival on a point before deciding its delivery. This method
     * observes calls without consuming them, and can be used repeatedly for the same number.
     *
     * @param selector the method to observe; selector arguments are ignored
     * @param number the one-based entry number for that method
     * @param timeout maximum real wait; zero only checks existing calls
     * @return the registered attempt
     * @throws IllegalArgumentException if number is not positive, timeout is negative,
     *         or the selector does not invoke exactly one network method
     * @throws IllegalStateException if build was not called or the call does not arrive in time
     * @throws InterruptedException if the observing test thread is interrupted
     */
    public synchronized Attempt awaitCall(Selector selector, int number, Duration timeout)
            throws InterruptedException {
        requireBuilt();
        requirePositive(number);
        Method method = capture(selector);
        long limit = waitLimit(timeout);
        long start = System.nanoTime();
        while (calls.getOrDefault(method, List.of()).size() < number) {
            long remaining = limit - (System.nanoTime() - start);
            if (remaining <= 0) {
                throw new IllegalStateException("Call " + number + " did not arrive: " + method.getName());
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return calls.get(method).get(number - 1);
    }

    /**
     * Asserts that each rule matched at least once and all registered calls finished.
     * An every-call rule needs only one match. This does not wait for future calls or
     * for the proxy's caller to process a result.
     *
     * @throws AssertionError if a rule was unused or a call is pending
     * @throws IllegalStateException if build was not called
     */
    public synchronized void assertAllRulesUsedAndCallsFinished() {
        requireBuilt();
        for (int index = 0; index < rules.size(); index++) {
            Rule rule = rules.get(index);
            if (!rule.used) {
                throw new AssertionError("Unused network rule #" + (index + 1) + ": " + rule.method.getName());
            }
        }
        for (List<Attempt> attempts : calls.values()) {
            for (Attempt attempt : attempts) {
                if (!attempt.finished) {
                    Invocation invocation = attempt.invocation;
                    throw new AssertionError("Unfinished call: " + invocation.method().getName()
                            + " #" + invocation.callNumber() + " with arguments "
                            + Arrays.toString(invocation.args()));
                }
            }
        }
    }

    private synchronized Attempt register(Method method, Object[] args) {
        Map<Method, Integer> history = Map.copyOf(counts);
        int number = Math.incrementExact(counts.getOrDefault(method, 0));
        counts.put(method, number);
        var invocation = new Invocation(method, args, number);
        var selection = new Selection(invocation, history);
        Attempt attempt = null;
        try {
            Delivery request = Delivery.DELIVER;
            Delivery response = Delivery.DELIVER;
            for (Rule rule : rules) {
                if (rule.method.equals(method) && rule.frequency.test(number) && rule.condition.test(selection)) {
                    rule.used = true;
                    request = rule.request;
                    response = rule.response;
                    break;
                }
            }
            attempt = new Attempt(invocation, request, response);
            return attempt;
        } finally {
            if (attempt == null) {
                // A failing user predicate still counts as an entry and must remain observable.
                attempt = new Attempt(invocation, Delivery.DELIVER, Delivery.DELIVER);
                attempt.finish();
            }
            calls.computeIfAbsent(method, ignored -> new ArrayList<>()).add(attempt);
            notifyAll();
        }
    }

    private Object invoke(Method method, Object[] args) throws Throwable {
        Attempt attempt = register(method, args);
        try {
            attempt.request.pass(attempt.invocation, "Request missing");
            Object result;
            try {
                result = method.invoke(node, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
            attempt.response.pass(attempt.invocation, "Response missing");
            return result;
        } finally {
            attempt.finish();
        }
    }

    private void checkMutable() {
        if (built) {
            throw new IllegalStateException("Network configuration is already built");
        }
    }

    private void requireBuilt() {
        if (!built) {
            throw new IllegalStateException("Network has not been built");
        }
    }

    private static void requirePositive(int number) {
        if (number <= 0) {
            throw new IllegalArgumentException("Non-positive call number: " + number);
        }
    }

    private static long waitLimit(Duration timeout) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Negative wait timeout: " + timeout);
        }
        return timeout.toNanos();
    }

    private static Method capture(Selector selector) {
        Objects.requireNonNull(selector, "selector");
        Method[] selected = new Method[1];
        int[] count = new int[1];
        var recorder = (NodeNetworkInterface) Proxy.newProxyInstance(
                NodeNetworkInterface.class.getClassLoader(),
                new Class<?>[]{NodeNetworkInterface.class}, (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        throw new IllegalArgumentException("Selector must invoke a network method");
                    }
                    count[0]++;
                    selected[0] = method;
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
        selector.accept(recorder);
        if (count[0] != 1) {
            throw new IllegalArgumentException("Selector must invoke exactly one network method: " + count[0]);
        }
        return selected[0];
    }

    /** Configures the frequency, conditions and delivery of a persistent rule. */
    public static final class RuleBuilder {
        private final FakeNetworkInterface parent;
        private final Method method;
        // Rule builders are only used during single-threaded configuration.
        private IntPredicate frequency = number -> true;
        private Predicate<Selection> condition = selection -> true;

        private RuleBuilder(FakeNetworkInterface parent, Method method) {
            this.parent = parent;
            this.method = method;
        }

        /**
         * Applies on every call, replacing any earlier frequency selector.
         *
         * @return this rule builder
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder onEveryCall() {
            parent.checkMutable();
            frequency = number -> true;
            return this;
        }

        /**
         * Applies only on the first method entry.
         *
         * @return this rule builder
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder onFirstCall() {
            return onCall(1);
        }

        /**
         * Applies only on the given method entry, replacing an earlier frequency selector.
         *
         * @param number the one-based entry number, independent of argument filters
         * @return this rule builder
         * @throws IllegalArgumentException if number is not positive
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder onCall(int number) {
            parent.checkMutable();
            requirePositive(number);
            frequency = current -> current == number;
            return this;
        }

        /**
         * Applies from the given method entry onwards.
         *
         * @param number the first included entry number
         * @return this rule builder
         * @throws IllegalArgumentException if number is not positive
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder onCallsFrom(int number) {
            parent.checkMutable();
            requirePositive(number);
            frequency = current -> current >= number;
            return this;
        }

        /**
         * Applies on the first given number of entries.
         *
         * @param number the number of included entries
         * @return this rule builder
         * @throws IllegalArgumentException if number is not positive
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder onFirstCalls(int number) {
            parent.checkMutable();
            requirePositive(number);
            frequency = current -> current <= number;
            return this;
        }

        /**
         * Adds a condition that the method had an earlier entry, completed or not.
         *
         * @param selector the method to check, ignoring selector arguments
         * @return this rule builder
         * @throws IllegalArgumentException if the selector does not identify one network method
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder onlyIfCalled(Selector selector) {
            parent.checkMutable();
            Method other = capture(selector);
            condition = condition.and(selection -> selection.history.getOrDefault(other, 0) > 0);
            return this;
        }

        /**
         * Adds a condition that the method had no earlier entry.
         *
         * @param selector the method to check, ignoring selector arguments
         * @return this rule builder
         * @throws IllegalArgumentException if the selector does not identify one network method
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder onlyIfNeverCalled(Selector selector) {
            return onlyIfCalledTimes(selector, 0);
        }

        /**
         * Adds a condition on the number of earlier method entries.
         *
         * @param selector the method to check, ignoring selector arguments
         * @param number the exact prior count, including failed and pending calls
         * @return this rule builder
         * @throws IllegalArgumentException if number is negative or the selector is invalid
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder onlyIfCalledTimes(Selector selector, int number) {
            parent.checkMutable();
            if (number < 0) {
                throw new IllegalArgumentException("Negative prior call count: " + number);
            }
            Method other = capture(selector);
            condition = condition.and(selection -> selection.history.getOrDefault(other, 0) == number);
            return this;
        }

        /**
         * Adds an argument filter without changing how method entries are counted.
         *
         * @param predicate a predicate receiving a copy of the real argument array
         * @return this rule builder
         * @throws NullPointerException if predicate is null
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder withArgs(Predicate<Object[]> predicate) {
            Objects.requireNonNull(predicate, "predicate");
            return and(invocation -> predicate.test(invocation.args()));
        }

        /**
         * Adds a free condition. All conditions combine with AND.
         *
         * @param predicate a short, non-blocking predicate on immutable call metadata
         * @return this rule builder
         * @throws NullPointerException if predicate is null
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder and(Predicate<Invocation> predicate) {
            parent.checkMutable();
            Objects.requireNonNull(predicate, "predicate");
            condition = condition.and(selection -> predicate.test(selection.invocation));
            return this;
        }

        /**
         * Registers normal delivery for both messages.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface deliver() {
            return delivery(Delivery.DELIVER, Delivery.DELIVER);
        }

        /**
         * Registers request loss without endpoint execution.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface dropRequest() {
            return delivery(Delivery.DROP, Delivery.DELIVER);
        }

        /**
         * Registers response loss after successful endpoint execution.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface dropResponse() {
            return delivery(Delivery.DELIVER, Delivery.DROP);
        }

        /**
         * Holds each matching request for a separate test decision.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface holdRequest() {
            return delivery(Delivery.HOLD, Delivery.DELIVER);
        }

        /**
         * Holds each matching response for a separate test decision.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface holdResponse() {
            return delivery(Delivery.DELIVER, Delivery.HOLD);
        }

        /**
         * Registers the rule with explicit request and response modes.
         *
         * @param request request delivery mode
         * @param response response delivery mode
         * @return the network builder for the next rule or build
         * @throws NullPointerException if either mode is null
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface delivery(Delivery request, Delivery response) {
            parent.checkMutable();
            parent.rules.add(new Rule(method, frequency, condition,
                    Objects.requireNonNull(request, "request"), Objects.requireNonNull(response, "response")));
            return parent;
        }
    }

    /** Observation and delivery controls for one attempt. */
    public static final class Attempt {
        private final Invocation invocation;
        private final ExchangePoint request;
        private final ExchangePoint response;
        // The worker publishes completion to observers and assertAllRulesUsedAndCallsFinished().
        private volatile boolean finished;

        private Attempt(Invocation invocation, Delivery request, Delivery response) {
            this.invocation = invocation;
            this.request = new ExchangePoint(request);
            this.response = new ExchangePoint(response);
        }

        /**
         * Returns the immutable metadata captured at entry.
         *
         * @return the invocation, including its method call number
         */
        public Invocation invocation() {
            return invocation;
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
