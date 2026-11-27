package badspace.remote.network;

import badspace.remote.api.errors.RetryableCommunicationException;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
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
 * Simulates request and response delivery for a public remote interface.
 * Methods are selected by invoking them on a recording proxy, for example
 * {@code wrap(EchoNetwork.class, echo).on(n -> n.echo(any())).onFirstCall().dropRequest().build()}.
 * Selectors support reference arguments. Every selector argument must use {@link #any()} or {@link #eq(Object)}.
 *
 * <p>Every entry into the generated proxy counts as a call, including retries and lost
 * requests. Frequency and history conditions count calls matching the complete selector,
 * shared by all threads and independent of additional withArgs/and filters and rule outcomes.
 * Invocation.callNumber remains the global entry number for its method. The first matching
 * rule wins. Rules remain registered; unmatched calls deliver both messages.
 * Build freezes configuration and is allowed once.
 *
 * <p>Configuration is single-threaded. After build, observation and network calls can
 * use the same builder from different threads. Call registration and rule selection
 * are atomic under this builder's monitor.
 * History conditions see entries strictly before the current call, including failed
 * and still pending calls. They do not imply completion or success. Argument predicates
 * run under the monitor and must be short, side-effect free and non-blocking.
 * Endpoint calls and message waits run outside that monitor.
 *
 * <p>{@link #awaitAttempt(Selector, int, Duration)} provides separate delivery controls for
 * each invocation, including those selected by an every-call rule. A held message ignores
 * transport interruption until the test releases it, then restores the interrupt flag.
 * Tests should call {@link #releaseHeldMessages()} in cleanup before waiting for workers.
 * Endpoint exceptions propagate directly without reaching the response point, so a lost
 * response containing a service error is not simulated.
 *
 * @param <T> the remote interface
 */
public final class FakeNetworkInterface<T> {
    private static final Duration DEFAULT_OBSERVATION_TIMEOUT = Duration.ofSeconds(5);
    // Each capture owns its context; matcher calls only access the current thread's context.
    private static final ThreadLocal<RecordingContext> RECORDING_CONTEXT = new ThreadLocal<>();

    private final Class<T> contract;
    private final T endpoint;
    private final Duration observationTimeout;
    // After build, rule usage, history and call registration use this monitor.
    private final List<Rule> rules = new ArrayList<>();
    private final Map<Method, List<Attempt>> calls = new HashMap<>();
    private boolean built;
    // Set by releaseHeldMessages() and read by register(), both under this monitor.
    // Once set, it stays set: new attempts never hold their messages.
    private boolean holdsReleased;

    /** Delivery of one request or response. */
    public enum Delivery {
        /** Deliver immediately. */
        DELIVER,
        /** Report a lost message immediately. */
        DROP,
        /** Wait until the test chooses delivery or loss. */
        HOLD
    }

    /**
     * Selects exactly one network method with any()/eq() reference argument matchers.
     * Write each matcher directly in its argument position. Matchers are recorded in evaluation order.
     * The lambda runs only on a recording proxy. Nested recording is not supported.
     *
     * @param <T> the remote interface
     */
    @FunctionalInterface
    public interface Selector<T> {
        /**
         * Invokes the method to identify on a recording proxy, without calling the endpoint.
         *
         * @param proxy the recording proxy
         */
        void accept(T proxy);
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

    private record CapturedSelector(Method method, List<Predicate<Object>> matchers) {
        private boolean matches(Invocation invocation) {
            if (!method.equals(invocation.method)) {
                return false;
            }
            for (int index = 0; index < matchers.size(); index++) {
                if (!matchers.get(index).test(invocation.args[index])) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class RecordingContext {
        private final List<Predicate<Object>> matchers = new ArrayList<>();
        private CapturedSelector selected;

        private Object record(Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                throw new IllegalArgumentException("Selector must invoke a network method");
            }
            if (selected != null) {
                throw new IllegalArgumentException("Selector must invoke exactly one network method");
            }
            int parameterCount = method.getParameterCount();
            if (matchers.size() != parameterCount) {
                throw new IllegalArgumentException("Every selector argument must use any() or eq(): "
                        + method.getName());
            }
            for (Object arg : args) {
                if (arg != null) {
                    throw new IllegalArgumentException("Selector arguments must be matcher placeholders");
                }
            }
            selected = new CapturedSelector(method, List.copyOf(matchers));
            matchers.clear();
            Class<?> resultType = method.getReturnType();
            return resultType.isPrimitive() && resultType != void.class
                    ? Array.get(Array.newInstance(resultType, 1), 0)
                    : null;
        }
    }

    /**
     * Matches any reference argument, including null, while recording a selector.
     * Write a matcher directly in each argument position of the selected call.
     *
     * @param <T> the reference parameter type
     * @return a null placeholder used only by the recording proxy
     * @throws IllegalStateException if no selector is being recorded on this thread
     */
    public static <T> T any() {
        currentContext().matchers.add(value -> true);
        return null;
    }

    /**
     * Matches a reference argument using Objects.equals, including a null expected value.
     * Write a matcher directly in each argument position of the selected call.
     *
     * @param expected the expected argument
     * @param <T> the reference parameter type
     * @return a null placeholder used only by the recording proxy
     * @throws IllegalStateException if no selector is being recorded on this thread
     */
    public static <T> T eq(T expected) {
        currentContext().matchers.add(value -> Objects.equals(expected, value));
        return null;
    }

    private static RecordingContext currentContext() {
        RecordingContext context = RECORDING_CONTEXT.get();
        if (context == null) {
            throw new IllegalStateException("Matchers can only be used while recording a selector");
        }
        return context;
    }

    private static final class Rule {
        private final CapturedSelector selector;
        private final IntPredicate frequency;
        private final Predicate<Invocation> condition;
        private final Delivery request;
        private final Delivery response;
        private boolean used;

        private Rule(CapturedSelector selector, IntPredicate frequency, Predicate<Invocation> condition,
                     Delivery request, Delivery response) {
            this.selector = selector;
            this.frequency = frequency;
            this.condition = condition;
            this.request = request;
            this.response = response;
        }
    }

    private FakeNetworkInterface(Class<T> contract, T endpoint, Duration observationTimeout) {
        this.contract = Objects.requireNonNull(contract, "contract");
        if (!contract.isInterface()) {
            throw new IllegalArgumentException("Expected a network interface: " + contract.getName());
        }
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        waitLimit(observationTimeout);
        this.observationTimeout = observationTimeout;
    }

    /**
     * Starts configuration of a network with a five-second attempt observation timeout.
     *
     * @param contract the public remote interface to simulate
     * @param endpoint the target invoked after request delivery
     * @param <T> the remote interface
     * @return a new builder with independent history and controls
     * @throws NullPointerException if contract or endpoint is null
     * @throws IllegalArgumentException if contract is not an interface
     */
    public static <T> FakeNetworkInterface<T> wrap(Class<T> contract, T endpoint) {
        return wrap(contract, endpoint, DEFAULT_OBSERVATION_TIMEOUT);
    }

    /**
     * Starts configuration with a default timeout for observing attempts.
     *
     * @param contract the public remote interface to simulate
     * @param endpoint the target invoked after request delivery
     * @param observationTimeout maximum real wait for awaitAttempt without an explicit timeout;
     *                           zero only checks existing attempts
     * @param <T> the remote interface
     * @return a new builder with independent history and controls
     * @throws NullPointerException if contract, endpoint or observationTimeout is null
     * @throws IllegalArgumentException if contract is not an interface or observationTimeout is negative
     */
    public static <T> FakeNetworkInterface<T> wrap(Class<T> contract, T endpoint, Duration observationTimeout) {
        return new FakeNetworkInterface<>(contract, endpoint, observationTimeout);
    }

    /**
     * Starts a rule for a network method. By default it applies on every call.
     *
     * @param selector invokes exactly one network method with argument matchers
     * @return the rule builder
     * @throws IllegalArgumentException if the selector does not invoke exactly one network
     *         method with a matcher for every argument
     * @throws IllegalStateException if the network was already built or recording is nested
     */
    public synchronized RuleBuilder<T> on(Selector<T> selector) {
        checkMutable();
        return new RuleBuilder<>(this, capture(selector));
    }

    /**
     * Freezes configuration and generates the network proxy. Object methods use proxy
     * identity and do not enter call history. No rules means normal delivery on every call.
     *
     * @return the generated network interface
     * @throws IllegalStateException if build was already called
     */
    public synchronized T build() {
        checkMutable();
        var proxy = newProxy((instance, method, args) -> {
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
     * Waits for an attempt using this network's default observation timeout.
     * Observing an attempt does not consume it or wait for its completion.
     *
     * @param selector the method and arguments to observe
     * @param number the one-based attempt number matching the complete selector
     * @return the registered attempt
     * @throws IllegalArgumentException if number is not positive or the selector
     *         does not invoke exactly one network method with a matcher for every argument
     * @throws IllegalStateException if build was not called, recording is nested,
     *         or the attempt does not arrive in time
     * @throws InterruptedException if the observing test thread is interrupted
     */
    public Attempt awaitAttempt(Selector<T> selector, int number) throws InterruptedException {
        return awaitAttempt(selector, number, observationTimeout);
    }

    /**
     * Waits for a method entry. The returned attempt has its own request and response
     * points; use awaitArrival on a point before deciding its delivery. This method
     * observes calls without consuming them, and can be used repeatedly for the same number.
     *
     * @param selector the method and arguments to observe
     * @param number the one-based entry number matching the complete selector
     * @param timeout maximum real wait; zero only checks existing calls
     * @return the registered attempt
     * @throws IllegalArgumentException if number is not positive, timeout is negative,
     *         or the selector does not invoke exactly one network method with a matcher for every argument
     * @throws IllegalStateException if build was not called, recording is nested,
     *         or the call does not arrive in time
     * @throws InterruptedException if the observing test thread is interrupted
     */
    public synchronized Attempt awaitAttempt(Selector<T> selector, int number, Duration timeout)
            throws InterruptedException {
        requireBuilt();
        requirePositive(number);
        CapturedSelector captured = capture(selector);
        long limit = waitLimit(timeout);
        long start = System.nanoTime();
        while (true) {
            int matching = 0;
            for (Attempt attempt : calls.getOrDefault(captured.method, List.of())) {
                if (captured.matches(attempt.invocation) && ++matching == number) {
                    return attempt;
                }
            }
            long remaining = limit - (System.nanoTime() - start);
            if (remaining <= 0) {
                throw new IllegalStateException("Matching attempt " + number + " did not arrive: "
                        + captured.method.getName());
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    /**
     * Delivers all held messages and disables holds for the rest of this network's lifetime.
     * This includes messages that have not arrived yet. Existing loss decisions still apply.
     * Repeated calls have the same effect.
     *
     * @throws IllegalStateException if build was not called
     */
    public synchronized void releaseHeldMessages() {
        requireBuilt();
        holdsReleased = true;
        for (var attempts : calls.values()) {
            for (var attempt : attempts) {
                attempt.request.releaseHold();
                attempt.response.releaseHold();
            }
        }
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
                throw new AssertionError("Unused network rule #" + (index + 1) + ": " + rule.selector.method.getName());
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
        int number = Math.incrementExact(calls.getOrDefault(method, List.of()).size());
        var invocation = new Invocation(method, args, number);
        Attempt attempt = null;
        try {
            Delivery request = Delivery.DELIVER;
            Delivery response = Delivery.DELIVER;
            for (Rule rule : rules) {
                if (rule.selector.matches(invocation)
                        && rule.frequency.test(Math.incrementExact(countMatches(rule.selector)))
                        && rule.condition.test(invocation)) {
                    rule.used = true;
                    request = rule.request;
                    response = rule.response;
                    break;
                }
            }
            if (holdsReleased) {
                if (request == Delivery.HOLD) {
                    request = Delivery.DELIVER;
                }
                if (response == Delivery.HOLD) {
                    response = Delivery.DELIVER;
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
                result = method.invoke(endpoint, args);
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

    // Called under this network's monitor, before registering the current invocation.
    private int countMatches(CapturedSelector selector) {
        int count = 0;
        for (Attempt attempt : calls.getOrDefault(selector.method, List.of())) {
            if (selector.matches(attempt.invocation)) {
                count++;
            }
        }
        return count;
    }

    private T newProxy(InvocationHandler handler) {
        return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, handler));
    }

    private CapturedSelector capture(Selector<T> selector) {
        Objects.requireNonNull(selector, "selector");
        if (RECORDING_CONTEXT.get() != null) {
            throw new IllegalStateException("Selector recording cannot be nested");
        }
        var context = new RecordingContext();
        RECORDING_CONTEXT.set(context);
        try {
            var recorder = newProxy((instance, method, args) ->
                    context.record(method, args == null ? new Object[0] : args));
            selector.accept(recorder);
            if (context.selected == null || !context.matchers.isEmpty()) {
                throw new IllegalArgumentException("Selector must contain exactly one call with argument matchers");
            }
            return context.selected;
        } finally {
            RECORDING_CONTEXT.remove();
        }
    }

    /**
     * Configures the frequency, conditions and delivery of a persistent rule.
     *
     * @param <T> the remote interface
     */
    public static final class RuleBuilder<T> {
        private final FakeNetworkInterface<T> parent;
        private final CapturedSelector selector;
        // Rule builders are only used during single-threaded configuration.
        private IntPredicate frequency = number -> true;
        private Predicate<Invocation> condition = invocation -> true;

        private RuleBuilder(FakeNetworkInterface<T> parent, CapturedSelector selector) {
            this.parent = parent;
            this.selector = selector;
        }

        /**
         * Applies on every call, replacing any earlier frequency selector.
         *
         * @return this rule builder
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder<T> onEveryCall() {
            parent.checkMutable();
            frequency = number -> true;
            return this;
        }

        /**
         * Applies only on the first entry matching the complete selector.
         *
         * @return this rule builder
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder<T> onFirstCall() {
            return onCall(1);
        }

        /**
         * Applies only on the given matching entry, replacing an earlier frequency selector.
         *
         * @param number the one-based entry number matching the selector,
         *               independent of withArgs/and filters
         * @return this rule builder
         * @throws IllegalArgumentException if number is not positive
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder<T> onCall(int number) {
            parent.checkMutable();
            requirePositive(number);
            frequency = current -> current == number;
            return this;
        }

        /**
         * Applies from the given entry matching the selector onwards.
         *
         * @param number the first included entry number
         * @return this rule builder
         * @throws IllegalArgumentException if number is not positive
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder<T> onCallsFrom(int number) {
            parent.checkMutable();
            requirePositive(number);
            frequency = current -> current >= number;
            return this;
        }

        /**
         * Applies on the first given number of entries matching the selector.
         *
         * @param number the number of included entries
         * @return this rule builder
         * @throws IllegalArgumentException if number is not positive
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder<T> onFirstCalls(int number) {
            parent.checkMutable();
            requirePositive(number);
            frequency = current -> current <= number;
            return this;
        }

        /**
         * Adds a condition that the method had an earlier entry, completed or not.
         *
         * @param selector the method and arguments to check in prior history
         * @return this rule builder
         * @throws IllegalArgumentException if the selector does not identify one network method
         *         with a matcher for every argument
         * @throws IllegalStateException if the network was already built or recording is nested
         */
        public RuleBuilder<T> onlyIfCalled(Selector<T> selector) {
            parent.checkMutable();
            CapturedSelector other = parent.capture(selector);
            condition = condition.and(invocation -> parent.countMatches(other) > 0);
            return this;
        }

        /**
         * Adds a condition that the method had no earlier entry.
         *
         * @param selector the method and arguments to check in prior history
         * @return this rule builder
         * @throws IllegalArgumentException if the selector does not identify one network method
         *         with a matcher for every argument
         * @throws IllegalStateException if the network was already built or recording is nested
         */
        public RuleBuilder<T> onlyIfNeverCalled(Selector<T> selector) {
            return onlyIfCalledTimes(selector, 0);
        }

        /**
         * Adds a condition on the number of earlier method entries.
         *
         * @param selector the method and arguments to check in prior history
         * @param number the exact prior count, including failed and pending calls
         * @return this rule builder
         * @throws IllegalArgumentException if number is negative or the selector is invalid
         * @throws IllegalStateException if the network was already built or recording is nested
         */
        public RuleBuilder<T> onlyIfCalledTimes(Selector<T> selector, int number) {
            parent.checkMutable();
            if (number < 0) {
                throw new IllegalArgumentException("Negative prior call count: " + number);
            }
            CapturedSelector other = parent.capture(selector);
            condition = condition.and(invocation -> parent.countMatches(other) == number);
            return this;
        }

        /**
         * Adds an argument filter without changing how selector matches are counted.
         *
         * @param predicate a predicate receiving a copy of the real argument array
         * @return this rule builder
         * @throws NullPointerException if predicate is null
         * @throws IllegalStateException if the network was already built
         */
        public RuleBuilder<T> withArgs(Predicate<Object[]> predicate) {
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
        public RuleBuilder<T> and(Predicate<Invocation> predicate) {
            parent.checkMutable();
            Objects.requireNonNull(predicate, "predicate");
            condition = condition.and(predicate);
            return this;
        }

        /**
         * Registers normal delivery for both messages.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface<T> deliver() {
            return delivery(Delivery.DELIVER, Delivery.DELIVER);
        }

        /**
         * Registers request loss without endpoint execution.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface<T> dropRequest() {
            return delivery(Delivery.DROP, Delivery.DELIVER);
        }

        /**
         * Registers response loss after successful endpoint execution.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface<T> dropResponse() {
            return delivery(Delivery.DELIVER, Delivery.DROP);
        }

        /**
         * Holds each matching request for a separate test decision.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface<T> holdRequest() {
            return delivery(Delivery.HOLD, Delivery.DELIVER);
        }

        /**
         * Holds each matching response for a separate test decision.
         *
         * @return the network builder
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface<T> holdResponse() {
            return delivery(Delivery.DELIVER, Delivery.HOLD);
        }

        /**
         * Registers the rule with explicit request and response modes. The response mode
         * applies only after the request reaches the endpoint and the endpoint returns normally.
         *
         * @param request request delivery mode
         * @param response response delivery mode
         * @return the network builder for the next rule or build
         * @throws NullPointerException if either mode is null
         * @throws IllegalStateException if the network was already built
         */
        public FakeNetworkInterface<T> delivery(Delivery request, Delivery response) {
            parent.checkMutable();
            parent.rules.add(new Rule(selector, frequency, condition,
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
            long limit = waitLimit(timeout);
            long remaining = limit;
            long start = System.nanoTime();
            while (!finished) {
                if (remaining <= 0) {
                    throw new IllegalStateException("Attempt did not finish within " + timeout);
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = limit - (System.nanoTime() - start);
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
         * @return the invocation for this message
         * @throws IllegalArgumentException if timeout is negative
         * @throws IllegalStateException if the message does not arrive before timeout
         * @throws InterruptedException if the observing test thread is interrupted
         */
        public synchronized Invocation awaitArrival(Duration timeout) throws InterruptedException {
            long limit = waitLimit(timeout);
            long remaining = limit;
            long start = System.nanoTime();
            while (invocation == null) {
                if (remaining <= 0) {
                    throw new IllegalStateException("Message did not arrive within " + timeout);
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = limit - (System.nanoTime() - start);
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

        private synchronized void releaseHold() {
            if (delivery == Delivery.HOLD) {
                delivery = Delivery.DELIVER;
                notifyAll();
            }
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
