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

/** Checks atomic rule selection, prior history and separate controls for concurrent calls. */
public class FakeNetworkConcurrencyTest {
    private static final UUID PROXY_ID = new UUID(0, 1);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector CREATE = n -> n.createPartition(null, null);
    private static final FakeNetworkInterface.Selector WRITE = n -> n.writeToPartition(null, null, null);

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
                assertEquals(number, network.awaitCall(CREATE, number, Duration.ZERO).invocation().callNumber());
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
        FakeNetworkInterface.Attempt first = null;
        FakeNetworkInterface.Attempt second = null;
        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstResult = workers.submit(() -> node.writeToPartition(firstId, "shared", "A"));
            var secondResult = workers.submit(() -> node.writeToPartition(secondId, "shared", "B"));
            var entryOne = network.awaitCall(WRITE, 1, WAIT);
            var entryTwo = network.awaitCall(WRITE, 2, WAIT);
            first = entryOne.invocation().args()[0].equals(firstId) ? entryOne : entryTwo;
            second = first == entryOne ? entryTwo : entryOne;
            assertNotSame(first, second);
            assertEquals(firstId, first.request().awaitArrival(WAIT).args()[0]);
            assertEquals(secondId, second.request().awaitArrival(WAIT).args()[0]);
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
            release(first, false);
            release(second, false);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void historyCountsCallsThatAreStillWaitingBeforeTheEndpoint() throws Exception {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000))
                .on(CREATE).onFirstCall().holdRequest()
                .on(n -> n.partitionExists(null, null)).onlyIfCalled(CREATE).dropRequest();
        var node = network.build();
        var workers = Executors.newSingleThreadExecutor();
        FakeNetworkInterface.Attempt first = null;
        try {
            var result = workers.submit(() -> node.createPartition(new RequestId(PROXY_ID, 0), "first"));
            first = network.awaitCall(CREATE, 1, WAIT);
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
            release(first, false);
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
        FakeNetworkInterface.Attempt first = null;
        try {
            var result = workers.submit(() -> node.createPartition(new RequestId(PROXY_ID, 0), "first"));
            first = network.awaitCall(CREATE, 1, WAIT);
            first.request().awaitArrival(WAIT);
            assertThrows(RetryableCommunicationException.class,
                    () -> node.createPartition(new RequestId(PROXY_ID, 1), "second"));
            first.request().deliver();
            result.get(5, TimeUnit.SECONDS);
            assertTrue(server.partitionExists("first"));
            assertFalse(server.partitionExists("second"));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            release(first, false);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void release(FakeNetworkInterface.Attempt attempt, boolean response) {
        if (attempt == null) {
            return;
        }
        try {
            (response ? attempt.response() : attempt.request()).deliver();
        } catch (IllegalStateException ignored) {
            // The point may have been released already or not reached after a test failure.
        }
    }
}
