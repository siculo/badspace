package prototest.fakenetwork;

import org.junit.Rule;
import org.junit.Test;
import prototest.example.api.errors.service.PartitionNotFoundException;
import prototest.example.server.NodeServer;
import prototest.example.server.NodeTestEndpoint;
import prototest.example.server.NodeTestNetwork;
import prototest.example.server.TestEndpoints;
import badspace.remote.api.RequestId;
import badspace.remote.api.errors.RetryableCommunicationException;
import badspace.remote.network.FakeNetworkInterface;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static badspace.remote.network.FakeNetworkInterface.Delivery.*;
import static badspace.remote.network.FakeNetworkInterface.any;
import static badspace.remote.network.FakeNetworkInterface.eq;

/**
 * Checks method selection, persistent rules, entry history and delivery controls.
 */
public class FakeNetworkInterfaceTest {
    @Rule
    public final TestEndpoints endpoints = new TestEndpoints();

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> CREATE = n -> n.createPartition(any(), any());
    private final NodeServer server = new NodeServer();
    private final NodeTestEndpoint endpoint = endpoints.track(new NodeTestEndpoint(server, 60000));
    private final UUID sessionToken = endpoint.connect();
    private final RequestId firstRequestId = id(0);
    private final FakeNetworkInterface<NodeTestNetwork> network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint);

    @Test
    public void unmatchedMethodsDeliverNormallyAndLeaveOtherRulesUnused() {
        network.on(n -> n.destroyPartition(any(), any())).dropRequest();
        var node = network.build();

        node.createPartition(firstRequestId, "present");

        assertTrue(server.partitionExists("present"));
        assertThrows(AssertionError.class, network::assertAllRulesUsedAndCallsFinished);
        assertThrows(RetryableCommunicationException.class,
                () -> node.destroyPartition(id(1), "present"));
        assertTrue(server.partitionExists("present"));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void lambdasSelectEveryMethodWithoutInvokingTheEndpoint() throws Exception {
        List<FakeNetworkInterface.Selector<NodeTestNetwork>> selectors = List.of(
                CREATE,
                n -> n.destroyPartition(any(), any()),
                n -> n.partitionExists(any(), any()),
                n -> n.writeToPartition(any(), any(), any()),
                n -> n.commitPartition(any(), any()),
                n -> n.readPartitionSnapshot(any(), any())
        );
        for (var selector : selectors) {
            network.on(selector).dropRequest();
        }
        var node = network.build();
        List<FakeNetworkInterface.Selector<NodeTestNetwork>> actualCalls = List.of(
                n -> n.createPartition(firstRequestId, "example"),
                n -> n.destroyPartition(firstRequestId, "example"),
                n -> n.partitionExists(firstRequestId, "example"),
                n -> n.writeToPartition(firstRequestId, "example", "data"),
                n -> n.commitPartition(firstRequestId, "example"),
                n -> n.readPartitionSnapshot(firstRequestId, "example")
        );
        for (int index = 0; index < selectors.size(); index++) {
            var actualCall = actualCalls.get(index);
            assertThrows(RetryableCommunicationException.class, () -> actualCall.accept(node));
            var attempt = network.awaitAttempt(selectors.get(index), 1, Duration.ZERO);
            assertEquals(1, attempt.invocation().callNumber());
            attempt.awaitCompletion(Duration.ZERO);
        }
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void aDroppedRequestDoesNotReachTheEndpointOrResponsePoint() throws Exception {
        network.on(CREATE).delivery(DROP, HOLD);
        var node = network.build();

        var error = assertThrows(RetryableCommunicationException.class,
                () -> node.createPartition(firstRequestId, "missing"));

        var attempt = network.awaitAttempt(CREATE, 1, Duration.ZERO);
        assertEquals("Request missing", error.getMessage());
        assertFalse(server.partitionExists("missing"));
        var requestInvocation = attempt.request().awaitArrival(Duration.ZERO);
        var requestId = (RequestId) requestInvocation.args()[0];
        assertEquals(firstRequestId, requestId);
        assertThrows(IllegalStateException.class, () -> attempt.response().awaitArrival(Duration.ZERO));
        attempt.awaitCompletion(Duration.ZERO);
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void aDroppedResponseKeepsTheEndpointEffects() {
        var node = network.on(CREATE).dropResponse().build();

        var error = assertThrows(RetryableCommunicationException.class,
                () -> node.createPartition(firstRequestId, "present"));

        assertEquals("Response missing", error.getMessage());
        assertTrue(server.partitionExists("present"));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void aHeldResponseThrowsItsApplicationExceptionOnlyAfterDelivery() throws Exception {
        FakeNetworkInterface.Selector<NodeTestNetwork> read = n -> n.readPartitionSnapshot(any(), any());
        var node = network.on(read).holdResponse().build();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> assertThrows(PartitionNotFoundException.class,
                    () -> node.readPartitionSnapshot(firstRequestId, "missing")));
            var attempt = network.awaitAttempt(read, 1);
            attempt.response().awaitArrival(WAIT);
            assertFalse(result.isDone());

            attempt.response().deliver();

            assertTrue(result.get(5, TimeUnit.SECONDS).getMessage().contains("\"missing\""));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void cleanupReleasesAnArrivedRequestAndItsFutureResponse() throws Exception {
        var node = network.on(CREATE).delivery(HOLD, HOLD).build();
        var worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        try {
            var result = worker.submit(() -> node.createPartition(firstRequestId, "present"));
            var attempt = network.awaitAttempt(CREATE, 1);
            attempt.request().awaitArrival(WAIT);

            network.releaseHeldMessages();

            result.get(5, TimeUnit.SECONDS);
            assertTrue(server.partitionExists("present"));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void cleanupBeforeRegistrationReleasesFutureHoldsAndKeepsLosses() throws Exception {
        var node = network.on(CREATE).delivery(HOLD, DROP).build();
        network.releaseHeldMessages();
        network.releaseHeldMessages();

        var worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        try {
            var result = worker.submit(() -> assertThrows(RetryableCommunicationException.class,
                    () -> node.createPartition(firstRequestId, "present")));
            assertEquals("Response missing", result.get(5, TimeUnit.SECONDS).getMessage());
            assertTrue(server.partitionExists("present"));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aHeldRequestCanBeDroppedWithoutCallingTheEndpoint() throws Exception {
        var node = network.on(CREATE).holdRequest().build();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(
                    () -> assertThrows(RetryableCommunicationException.class, () -> node.createPartition(firstRequestId, "missing"))
            );
            var attempt = network.awaitAttempt(CREATE, 1);
            attempt.request().awaitArrival(WAIT);
            assertFalse(server.partitionExists("missing"));
            assertFalse(result.isDone());
            assertThrows(AssertionError.class, network::assertAllRulesUsedAndCallsFinished);
            assertThrows(IllegalStateException.class, () -> attempt.awaitCompletion(Duration.ZERO));

            attempt.request().drop();

            assertEquals("Request missing", result.get(5, TimeUnit.SECONDS).getMessage());
            assertFalse(server.partitionExists("missing"));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aHeldResponseReturnsItsValueOnlyAfterDelivery() throws Exception {
        server.createPartition("present");
        FakeNetworkInterface.Selector<NodeTestNetwork> query = n -> n.partitionExists(any(), any());
        var node = network.on(query).holdResponse().build();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> node.partitionExists(firstRequestId, "present"));
            var attempt = network.awaitAttempt(query, 1);
            attempt.response().awaitArrival(WAIT);
            assertFalse(result.isDone());

            attempt.response().deliver();

            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, attempt.response()::deliver);
            assertThrows(IllegalStateException.class, attempt.response()::drop);
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void heldMessagesIgnoreCancellationAndPreserveTheWorkerInterruptFlag() throws Exception {
        var node = network.on(CREATE).holdResponse().build();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> {
                Thread.currentThread().interrupt();
                node.createPartition(firstRequestId, "present");
                return Thread.currentThread().isInterrupted();
            });
            var attempt = network.awaitAttempt(CREATE, 1);
            attempt.response().awaitArrival(WAIT);
            assertFalse(result.isDone());
            assertTrue(server.partitionExists("present"));

            attempt.response().deliver();

            assertTrue(result.get(5, TimeUnit.SECONDS));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void anObserverCanTimeOutWithoutChangingTheRulesOrHistory() throws Exception {
        var node = network.on(CREATE).onFirstCall().dropRequest().build();
        assertThrows(IllegalStateException.class,
                () -> network.awaitAttempt(CREATE, 1, Duration.ofMillis(1)));
        assertThrows(AssertionError.class, network::assertAllRulesUsedAndCallsFinished);
        assertThrows(IllegalArgumentException.class,
                () -> network.awaitAttempt(CREATE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> network.awaitAttempt(CREATE, 1, Duration.ofNanos(-1)));

        assertThrows(RetryableCommunicationException.class,
                () -> node.createPartition(firstRequestId, "missing"));
        var attempt = network.awaitAttempt(CREATE, 1, Duration.ZERO);
        assertSame(attempt, network.awaitAttempt(CREATE, 1, Duration.ZERO));
        assertThrows(IllegalStateException.class, () -> attempt.response().awaitArrival(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> attempt.request().awaitArrival(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> attempt.awaitCompletion(Duration.ofNanos(-1)));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void attemptObservationUsesTheTimeoutConfiguredForItsNetwork() throws Exception {
        var immediateNetwork = FakeNetworkInterface.wrap(NodeTestNetwork.class,
                endpoint, Duration.ZERO);
        var node = immediateNetwork.build();

        assertThrows(IllegalStateException.class, () -> immediateNetwork.awaitAttempt(CREATE, 1));
        node.createPartition(firstRequestId, "present");

        var attempt = immediateNetwork.awaitAttempt(CREATE, 1);
        var requestId = (RequestId) attempt.invocation().args()[0];
        assertEquals(firstRequestId, requestId);
        assertSame(attempt, immediateNetwork.awaitAttempt(CREATE, 1, WAIT));
        immediateNetwork.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void invalidDefaultObservationTimeoutsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint, Duration.ofNanos(-1)));
        assertThrows(NullPointerException.class, () -> FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint, null));
    }

    @Test
    public void onFirstCallCountsEntriesIndependentlyOfArgumentFilters() {
        var node = network.on(CREATE).onFirstCall().withArgs(args -> args[1].equals("target"))
                .dropRequest().build();

        node.createPartition(id(0), "other");
        node.createPartition(id(1), "target");

        assertTrue(server.partitionExists("other"));
        assertTrue(server.partitionExists("target"));
        assertThrows(AssertionError.class, network::assertAllRulesUsedAndCallsFinished);
    }

    @Test
    public void onCallSelectsOnlyTheGivenEntry() {
        var node = network.on(CREATE).onCall(2).dropRequest().build();

        node.createPartition(id(0), "first");
        assertThrows(RetryableCommunicationException.class, () -> node.createPartition(id(1), "second"));
        node.createPartition(id(2), "third");

        assertTrue(server.partitionExists("first"));
        assertFalse(server.partitionExists("second"));
        assertTrue(server.partitionExists("third"));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void onEveryCallIsPersistentAndCanReplaceAnEarlierFrequency() {
        var node = network.on(CREATE).onCall(2).onEveryCall().dropRequest().build();

        for (int number = 0; number < 3; number++) {
            var requestId = id(number);
            assertThrows(RetryableCommunicationException.class,
                    () -> node.createPartition(requestId, "missing"));
        }
        assertFalse(server.partitionExists("missing"));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void onFirstCallsAndOnCallsFromIncludeTheirBoundaries() {
        var node = network.on(CREATE).onFirstCalls(2).dropRequest()
                .on(CREATE).onCallsFrom(4).dropResponse().build();

        for (int number = 1; number <= 5; number++) {
            var requestId = id(number);
            String name = "partition-" + number;
            if (number == 3) {
                node.createPartition(requestId, name);
            } else {
                var error = assertThrows(RetryableCommunicationException.class,
                        () -> node.createPartition(requestId, name));
                assertEquals(number <= 2 ? "Request missing" : "Response missing", error.getMessage());
            }
            assertEquals(number >= 3, server.partitionExists(name));
        }
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void theFirstMatchingRuleWinsAndOtherRulesRemainAvailable() {
        var node = network.on(CREATE).onFirstCall().dropRequest()
                .on(CREATE).onEveryCall().dropResponse().build();

        var first = assertThrows(RetryableCommunicationException.class,
                () -> node.createPartition(id(0), "first"));
        var second = assertThrows(RetryableCommunicationException.class,
                () -> node.createPartition(id(1), "second"));
        var third = assertThrows(RetryableCommunicationException.class,
                () -> node.createPartition(id(2), "third"));

        assertEquals("Request missing", first.getMessage());
        assertEquals("Response missing", second.getMessage());
        assertEquals("Response missing", third.getMessage());
        assertFalse(server.partitionExists("first"));
        assertTrue(server.partitionExists("second"));
        assertTrue(server.partitionExists("third"));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void historyExcludesTheCurrentEntryAndIncludesLostRequests() {
        var node = network.on(CREATE).onlyIfNeverCalled(CREATE).dropRequest()
                .on(CREATE).onlyIfCalledTimes(CREATE, 1).dropResponse()
                .on(CREATE).onlyIfCalled(CREATE).deliver().build();

        assertThrows(RetryableCommunicationException.class, () -> node.createPartition(id(0), "first"));
        assertThrows(RetryableCommunicationException.class, () -> node.createPartition(id(1), "second"));
        node.createPartition(id(2), "third");

        assertFalse(server.partitionExists("first"));
        assertTrue(server.partitionExists("second"));
        assertTrue(server.partitionExists("third"));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void conditionsOnOtherMethodsCombineWithArgumentFilters() {
        var node = network.on(n -> n.partitionExists(any(), any())).onlyIfNeverCalled(CREATE)
                .withArgs(args -> args[1].equals("target")).dropRequest()
                .on(n -> n.partitionExists(any(), any())).onlyIfCalledTimes(CREATE, 1)
                .and(call -> call.callNumber() == 3).dropResponse().build();

        assertFalse(node.partitionExists(id(0), "other"));
        assertThrows(RetryableCommunicationException.class, () -> node.partitionExists(id(1), "target"));
        node.createPartition(id(2), "target");
        assertThrows(RetryableCommunicationException.class, () -> node.partitionExists(id(3), "target"));
        assertTrue(node.partitionExists(id(4), "target"));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void aFailingPredicatePropagatesItsErrorAndKeepsEntryNumbersObservable() throws Exception {
        var failure = new IllegalStateException("Broken predicate");
        var node = network.on(CREATE).onFirstCall().withArgs(args -> {
            throw failure;
        }).dropRequest().build();

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> node.createPartition(id(0), "first")));
        node.createPartition(id(1), "second");

        var first = network.awaitAttempt(CREATE, 1, Duration.ZERO);
        first.awaitCompletion(Duration.ZERO);
        var firstPartitionName = (String) first.invocation().args()[1];
        assertEquals("first", firstPartitionName);
        var secondAttempt = network.awaitAttempt(CREATE, 2, Duration.ZERO);
        assertEquals(2, secondAttempt.invocation().callNumber());
        assertFalse(server.partitionExists("first"));
        assertTrue(server.partitionExists("second"));
    }

    @Test
    public void filtersAndObserversCannotChangeTheArgumentArray() throws Exception {
        var node = network.on(CREATE).withArgs(args -> {
            args[1] = "changed";
            return true;
        }).deliver().build();

        node.createPartition(firstRequestId, "original");
        var attempt = network.awaitAttempt(CREATE, 1, Duration.ZERO);
        attempt.invocation().args()[1] = "changed again";

        assertTrue(server.partitionExists("original"));
        assertFalse(server.partitionExists("changed"));
        var requestInvocation = attempt.request().awaitArrival(Duration.ZERO);
        var partitionName = (String) requestInvocation.args()[1];
        assertEquals("original", partitionName);
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void selectorsMatchArgumentsInOrderAndCompareValues() throws Exception {
        var node = network.on(n -> n.writeToPartition(eq(firstRequestId), eq("target"), eq("data")))
                .dropRequest().build();
        server.createPartition("target");
        server.createPartition("data");

        node.writeToPartition(id(1), "target", "data");
        node.writeToPartition(firstRequestId, "target", "other");
        node.writeToPartition(firstRequestId, "data", "target");
        var equalRequestId = new RequestId(firstRequestId.sessionToken(), firstRequestId.sequence());
        assertThrows(RetryableCommunicationException.class,
                () -> node.writeToPartition(equalRequestId, new String("target"), new String("data")));

        var attempt = network.awaitAttempt(
                n -> n.writeToPartition(eq(firstRequestId), eq("target"), eq("data")), 1, Duration.ZERO);
        assertEquals(4, attempt.invocation().callNumber());
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void anyMatchesNullAndEqNullSelectsOnlyNull() throws Exception {
        var node = network.on(n -> n.createPartition(any(), any())).dropRequest().build();
        assertThrows(RetryableCommunicationException.class, () -> node.createPartition(null, null));
        assertThrows(RetryableCommunicationException.class, () -> node.createPartition(firstRequestId, "present"));

        var nullAttempt = network.awaitAttempt(n -> n.createPartition(eq(null), eq(null)), 1, Duration.ZERO);
        assertEquals(1, nullAttempt.invocation().callNumber());
        assertThrows(IllegalStateException.class,
                () -> network.awaitAttempt(n -> n.createPartition(eq(null), eq(null)), 2, Duration.ZERO));
        var secondAttempt = network.awaitAttempt(CREATE, 2, Duration.ZERO);
        assertEquals(2, secondAttempt.invocation().callNumber());
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void attemptNumbersAndRuleFrequencyCountOnlyCompleteSelectorMatches() throws Exception {
        FakeNetworkInterface.Selector<NodeTestNetwork> target = n -> n.createPartition(any(), eq("target"));
        var node = network.on(target).onCall(2).dropRequest().build();
        node.createPartition(id(0), "other");
        node.createPartition(id(1), "target");
        node.createPartition(id(2), "another");
        assertThrows(RetryableCommunicationException.class, () -> node.createPartition(id(3), "target"));

        var firstAttempt = network.awaitAttempt(target, 1, Duration.ZERO);
        var secondAttempt = network.awaitAttempt(target, 2, Duration.ZERO);
        assertEquals(2, firstAttempt.invocation().callNumber());
        assertEquals(4, secondAttempt.invocation().callNumber());
        assertSame(secondAttempt, network.awaitAttempt(CREATE, 4, Duration.ZERO));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void historyConditionsCountOnlyPriorCompleteSelectorMatches() {
        FakeNetworkInterface.Selector<NodeTestNetwork> target = n -> n.createPartition(any(), eq("target"));
        var node = network.on(target).onlyIfNeverCalled(target).dropRequest()
                .on(n -> n.partitionExists(any(), eq("target"))).onlyIfCalledTimes(target, 1).dropResponse()
                .on(n -> n.partitionExists(any(), eq("later"))).onlyIfCalled(target).dropRequest().build();

        node.createPartition(id(0), "other");
        assertFalse(node.partitionExists(id(1), "target"));
        assertFalse(node.partitionExists(id(2), "later"));
        assertThrows(RetryableCommunicationException.class, () -> node.createPartition(id(3), "target"));
        assertThrows(RetryableCommunicationException.class, () -> node.partitionExists(id(4), "target"));
        assertThrows(RetryableCommunicationException.class, () -> node.partitionExists(id(5), "later"));
        node.createPartition(id(6), "target");
        assertTrue(node.partitionExists(id(7), "target"));
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void everySelectorArgumentMustUseAMatcher() {
        assertThrows(IllegalArgumentException.class,
                () -> network.on(n -> n.createPartition(any(), "target")));
        assertThrows(IllegalArgumentException.class,
                () -> network.on(n -> n.createPartition(null, null)));
        assertThrows(IllegalArgumentException.class, () -> network.on(n -> {
            any();
            n.createPartition(any(), any());
        }));
        assertThrows(IllegalArgumentException.class, () -> network.on(n -> {
            n.createPartition(any(), any());
            any();
        }));
        network.on(CREATE).dropRequest();
    }

    @Test
    public void recordingContextIsRemovedAfterSuccessAndFailure() {
        assertThrows(IllegalStateException.class, () -> FakeNetworkInterface.any());
        assertThrows(IllegalStateException.class, () -> eq("outside"));
        var failure = new IllegalStateException("Broken selector");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> network.on(n -> {
            any();
            throw failure;
        })));
        assertThrows(IllegalStateException.class, () -> FakeNetworkInterface.any());
        assertThrows(IllegalStateException.class, () -> network.on(n -> network.on(CREATE)));
        network.on(CREATE).dropRequest();
        assertThrows(IllegalStateException.class, () -> eq("outside"));
    }

    @Test
    public void invalidSelectorsAndCountsAreRejectedDuringConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> network.on(n -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> network.on(n -> {
            n.createPartition(any(), any());
            n.destroyPartition(any(), any());
        }));
        assertThrows(IllegalArgumentException.class, () -> network.on(Object::toString));
        assertThrows(IllegalArgumentException.class, () -> network.on(CREATE).onCall(0));
        assertThrows(IllegalArgumentException.class, () -> network.on(CREATE).onCallsFrom(-1));
        assertThrows(IllegalArgumentException.class, () -> network.on(CREATE).onFirstCalls(0));
        assertThrows(IllegalArgumentException.class, () -> network.on(CREATE).onlyIfCalledTimes(CREATE, -1));
        assertThrows(IllegalStateException.class, () -> network.awaitAttempt(CREATE, 1));
        assertThrows(IllegalStateException.class, network::assertAllRulesUsedAndCallsFinished);
    }

    @Test
    public void buildFreezesConfigurationIncludingExistingRuleBuilders() {
        var rule = network.on(CREATE);
        rule.dropRequest();
        network.build();

        assertThrows(IllegalStateException.class, network::build);
        assertThrows(IllegalStateException.class, () -> network.on(CREATE));
        assertThrows(IllegalStateException.class, rule::onEveryCall);
        assertThrows(IllegalStateException.class, rule::deliver);
    }

    @Test
    public void objectMethodsUseIdentityAndDoNotEnterNetworkHistory() throws Exception {
        NodeTestNetwork node = network.build();

        assertTrue(node.equals(node));
        assertFalse(node.equals(server));
        assertEquals(System.identityHashCode(node), node.hashCode());
        assertEquals("FakeNetworkInterface", node.toString());
        assertThrows(IllegalStateException.class, () -> network.awaitAttempt(CREATE, 1, Duration.ZERO));
        node.createPartition(firstRequestId, "first");
        var firstAttempt = network.awaitAttempt(CREATE, 1, Duration.ZERO);
        assertEquals(1, firstAttempt.invocation().callNumber());
        network.assertAllRulesUsedAndCallsFinished();
    }

    private RequestId id(long sequence) {
        return new RequestId(sessionToken, sequence);
    }
}
