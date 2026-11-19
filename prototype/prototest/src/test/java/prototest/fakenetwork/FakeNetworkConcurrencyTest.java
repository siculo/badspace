package prototest.fakenetwork;

import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.RetryableCommunicationException;
import prototest.proxy.FakeNetworkInterface;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static prototest.proxy.FakeNetworkInterface.any;
import static prototest.proxy.FakeNetworkInterface.eq;

/** Checks atomic rule selection, prior history and separate controls for concurrent calls. */
public class FakeNetworkConcurrencyTest {
    private static final UUID PROXY_ID = new UUID(0, 1);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector CREATE = n -> n.createPartition(any(), any());
    private static final FakeNetworkInterface.Selector WRITE = n -> n.writeToPartition(any(), any(), any());

    @Test
    public void concurrentAttemptsGetUniqueMethodNumbersAndUsePersistentRules() throws Exception {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000))
                .on(CREATE).and(call -> call.callNumber() % 3 == 1).dropRequest()
                .on(CREATE).and(call -> call.callNumber() % 3 == 2).dropResponse()
                .on(CREATE).deliver();
        var node = network.build();
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(4);
        var workers = Executors.newFixedThreadPool(4);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 60; index++) {
                int sequence = index;
                results.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        node.createPartition(new RequestId(PROXY_ID, sequence), "partition-" + sequence);
                        return "OK";
                    } catch (RetryableCommunicationException error) {
                        return error.getMessage();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int requestsMissing = 0;
            int responsesMissing = 0;
            int successes = 0;
            for (int index = 0; index < results.size(); index++) {
                String result = results.get(index).get(5, TimeUnit.SECONDS);
                switch (result) {
                    case "Request missing" -> {
                        requestsMissing++;
                        assertFalse(server.partitionExists("partition-" + index));
                    }
                    case "Response missing" -> {
                        responsesMissing++;
                        assertTrue(server.partitionExists("partition-" + index));
                    }
                    case "OK" -> {
                        successes++;
                        assertTrue(server.partitionExists("partition-" + index));
                    }
                    default -> fail("Unexpected outcome: " + result);
                }
            }
            assertEquals(20, requestsMissing);
            assertEquals(20, responsesMissing);
            assertEquals(20, successes);
            network.assertAllRulesUsedAndCallsFinished();
            for (int number = 1; number <= 60; number++) {
                var attempt = network.awaitAttempt(CREATE, number, Duration.ZERO);
                assertEquals(number, attempt.invocation().callNumber());
            }
            assertThrows(RetryableCommunicationException.class,
                    () -> node.createPartition(new RequestId(PROXY_ID, 60), "after-calls"));
            assertFalse(server.partitionExists("after-calls"));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void heldRequestsAllowTheTestToChooseTheOrderOfConcurrentWrites() throws Exception {
        var server = new NodeServer();
        server.createPartition("shared");
        var network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000))
                .on(WRITE).onEveryCall().holdRequest();
        var node = network.build();
        var firstId = new RequestId(PROXY_ID, 0);
        var secondId = new RequestId(PROXY_ID, 1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstResult = workers.submit(() -> node.writeToPartition(firstId, "shared", "A"));
            var secondResult = workers.submit(() -> node.writeToPartition(secondId, "shared", "B"));
            var first = network.awaitAttempt(n -> n.writeToPartition(eq(firstId), eq("shared"), any()), 1);
            var second = network.awaitAttempt(n -> n.writeToPartition(eq(secondId), eq("shared"), any()), 1);
            assertNotSame(first, second);
            var firstInvocation = first.request().awaitArrival(WAIT);
            var secondInvocation = second.request().awaitArrival(WAIT);
            var firstRequestId = (RequestId) firstInvocation.args()[0];
            var secondRequestId = (RequestId) secondInvocation.args()[0];
            assertEquals(firstId, firstRequestId);
            assertEquals(secondId, secondRequestId);
            assertFalse(firstResult.isDone());
            assertFalse(secondResult.isDone());

            second.request().deliver();
            secondResult.get(5, TimeUnit.SECONDS);
            first.request().deliver();
            firstResult.get(5, TimeUnit.SECONDS);

            server.commitPartition("shared");
            assertEquals("BA", server.readPartitionSnapshot("shared"));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void historyCountsCallsThatAreStillWaitingBeforeTheEndpoint() throws Exception {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000))
                .on(CREATE).onFirstCall().holdRequest()
                .on(n -> n.partitionExists(any(), any())).onlyIfCalled(CREATE).dropRequest();
        var node = network.build();
        var workers = Executors.newSingleThreadExecutor();
        try {
            var result = workers.submit(() -> node.createPartition(new RequestId(PROXY_ID, 0), "first"));
            var first = network.awaitAttempt(CREATE, 1);
            first.request().awaitArrival(WAIT);
            assertFalse(server.partitionExists("first"));

            assertThrows(RetryableCommunicationException.class,
                    () -> node.partitionExists(new RequestId(PROXY_ID, 1), "first"));
            assertThrows(AssertionError.class, network::assertAllRulesUsedAndCallsFinished);
            first.request().deliver();
            result.get(5, TimeUnit.SECONDS);
            assertTrue(server.partitionExists("first"));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void laterCallsDoNotChangeTheRuleAlreadyChosenForAHeldRequest() throws Exception {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000))
                .on(CREATE).onlyIfNeverCalled(CREATE).holdRequest()
                .on(CREATE).onlyIfCalled(CREATE).dropRequest();
        var node = network.build();
        var workers = Executors.newSingleThreadExecutor();
        try {
            var result = workers.submit(() -> node.createPartition(new RequestId(PROXY_ID, 0), "first"));
            var first = network.awaitAttempt(CREATE, 1);
            first.request().awaitArrival(WAIT);
            assertThrows(RetryableCommunicationException.class,
                    () -> node.createPartition(new RequestId(PROXY_ID, 1), "second"));
            first.request().deliver();
            result.get(5, TimeUnit.SECONDS);
            assertTrue(server.partitionExists("first"));
            assertFalse(server.partitionExists("second"));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void concurrentSelectorRecordingKeepsMatchersSeparate() throws Exception {
        var matchersReady = new CountDownLatch(2);
        var workers = Executors.newFixedThreadPool(2);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                String partitionName = "partition-" + index;
                results.add(workers.submit(() -> {
                    var network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(new NodeServer(), 60000));
                    network.on(n -> {
                        RequestId requestPlaceholder = any();
                        matchersReady.countDown();
                        try {
                            assertTrue(matchersReady.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Selector recording interrupted", error);
                        }
                        n.createPartition(requestPlaceholder, eq(partitionName));
                    }).dropRequest();
                    var node = network.build();
                    assertThrows(RetryableCommunicationException.class,
                            () -> node.createPartition(new RequestId(PROXY_ID, 0), partitionName));
                    node.createPartition(new RequestId(PROXY_ID, 1), "other");
                    network.assertAllRulesUsedAndCallsFinished();
                }));
            }
            for (var result : results) {
                result.get(5, TimeUnit.SECONDS);
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
