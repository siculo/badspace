package prototest;

import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.ConnectionUnavailableException;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.api.errors.network.RetryableCommunicationException;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static prototest.proxy.FakeNetworkInterface.Delivery.*;
import static prototest.proxy.FakeNetworkInterface.Operation.*;

/** Checks concurrent delivery with explicit plans and bounded observation waits. */
public class FakeNetworkConcurrencyTest {
    private static final UUID PROXY_ID = new UUID(0, 1);
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    public void concurrentAttemptsConsumeEachMatchingPlanOnce() throws Exception {
        var server = new NodeServer();
        var network = new FakeNetworkInterface(new ServerNodeNetworkInterface(server, 60000));
        for (int index = 0; index < 20; index++) {
            network.expect(call -> true, DROP, DELIVER);
            network.expect(call -> true, DELIVER, DROP);
            network.expect(call -> true, DELIVER, DELIVER);
        }
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
                        network.createPartition(new RequestId(PROXY_ID, sequence), "partition-" + sequence);
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
            network.verify();
            network.createPartition(new RequestId(PROXY_ID, 60), "after-plans");
            assertTrue(server.partitionExists("after-plans"));
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
        var network = new FakeNetworkInterface(new ServerNodeNetworkInterface(server, 60000));
        var firstId = new RequestId(PROXY_ID, 0);
        var secondId = new RequestId(PROXY_ID, 1);
        var first = network.expect(call -> call.requestId().equals(firstId), HOLD, DELIVER);
        var second = network.expect(call -> call.requestId().equals(secondId), HOLD, DELIVER);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstResult = workers.submit(() -> network.writeToPartition(firstId, "shared", "A"));
            var secondResult = workers.submit(() -> network.writeToPartition(secondId, "shared", "B"));
            assertEquals(firstId, first.request().awaitArrival(WAIT).requestId());
            assertEquals(secondId, second.request().awaitArrival(WAIT).requestId());
            assertFalse(firstResult.isDone());
            assertFalse(secondResult.isDone());

            second.request().deliver();
            secondResult.get(5, TimeUnit.SECONDS);
            first.request().deliver();
            firstResult.get(5, TimeUnit.SECONDS);

            server.commitPartition("shared");
            assertEquals("BA", server.readPartitionSnapshot("shared"));
            network.verify();
        } finally {
            release(first.request());
            release(second.request());
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void registeringPlansDoesNotChangeAnAttemptAlreadyWaitingForItsResponse() throws Exception {
        var server = new NodeServer();
        var network = new FakeNetworkInterface(new ServerNodeNetworkInterface(server, 60000));
        var first = network.expect(call -> call.partitionName().equals("first"), DELIVER, HOLD);
        var workers = Executors.newSingleThreadExecutor();
        try {
            var result = workers.submit(() -> network.createPartition(new RequestId(PROXY_ID, 0), "first"));
            first.response().awaitArrival(WAIT);
            assertTrue(server.partitionExists("first"));
            network.expect(call -> call.operation() == CREATE_PARTITION, DROP, DELIVER);

            assertThrows(RetryableCommunicationException.class,
                    () -> network.createPartition(new RequestId(PROXY_ID, 1), "second"));
            assertFalse(server.partitionExists("second"));
            first.response().deliver();
            result.get(5, TimeUnit.SECONDS);
            network.verify();
        } finally {
            release(first.response());
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aHeldResponseCanBeDeliveredAfterProxyRecovery() throws Exception {
        verifyLateResponse(false);
    }

    @Test
    public void aHeldResponseCanBeLostAfterProxyRecovery() throws Exception {
        verifyLateResponse(true);
    }

    private static void verifyLateResponse(boolean dropResponse) throws Exception {
        var server = new NodeServer();
        var transport = new AtomicReference<Thread>();
        var oldAttempts = new AtomicInteger();
        var endpoint = new ServerNodeNetworkInterface(server, 60000) {
            @Override
            public void createPartition(RequestId requestId, String name) {
                if (name.equals("old")) {
                    transport.set(Thread.currentThread());
                    oldAttempts.incrementAndGet();
                }
                super.createPartition(requestId, name);
            }
        };
        var network = new FakeNetworkInterface(endpoint);
        var old = network.expect(call -> call.partitionName().equals("old"), DELIVER, HOLD);
        network.expect(call -> call.partitionName().equals("fail"), DROP, DELIVER);
        var proxy = new NodeProxy(network, 0);
        var workers = Executors.newSingleThreadExecutor();
        try {
            var pending = workers.submit(() -> {
                var error = assertThrows(ConnectionUnavailableException.class,
                        () -> proxy.createPartition("old"));
                assertFalse(Thread.currentThread().isInterrupted());
                return error;
            });
            old.response().awaitArrival(WAIT);
            assertTrue(server.partitionExists("old"));
            var failure = assertThrows(NodeRecoveryRequiredException.class,
                    () -> proxy.createPartition("fail"));
            assertSame(failure, pending.get(5, TimeUnit.SECONDS).getCause());
            assertThrows(IllegalStateException.class, network::verify);

            proxy.recover();
            proxy.createPartition("new");
            if (dropResponse) {
                old.response().drop();
            } else {
                old.response().deliver();
            }
            old.awaitCompletion(WAIT);
            // Wait for the executor as well as the simulated network to process the late result.
            transport.get().join(5000);
            assertFalse(transport.get().isAlive());
            assertEquals(1, oldAttempts.get());
            assertFalse(proxy.recoveryRequired());
            assertTrue(proxy.partitionExists("new"));
            network.verify();
        } finally {
            release(old.response());
            if (transport.get() != null) {
                transport.get().join(5000);
                assertFalse(transport.get().isAlive());
            }
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void release(FakeNetworkInterface.ExchangePoint point) {
        try {
            point.deliver();
        } catch (IllegalStateException ignored) {
            // The point may have been released already or not reached after a test failure.
        }
    }
}
