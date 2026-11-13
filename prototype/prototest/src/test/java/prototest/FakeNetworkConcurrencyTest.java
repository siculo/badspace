package prototest;

import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.RetryableCommunicationException;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static prototest.proxy.FakeNetworkInterface.OpResult.*;

public class FakeNetworkConcurrencyTest {
    private static final UUID PROXY_ID = new UUID(0, 1);

    @Test
    public void concurrentAttemptsConsumeEachQueuedOutcomeOnce() throws Exception {
        var server = new NodeServer();
        var network = new FakeNetworkInterface(new ServerNodeNetworkInterface(server, 60000));
        network.setNetworkLatency(0, 0);
        network.pushResults(20, REQUEST_MISSING, RESPONSE_MISSING, OK);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(4);
        var workers = Executors.newFixedThreadPool(4);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 60; i++) {
                int sequence = i;
                results.add(workers.submit(() -> {
                    ready.countDown();
                    await(start);
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
            for (int i = 0; i < results.size(); i++) {
                String result = results.get(i).get(5, TimeUnit.SECONDS);
                switch (result) {
                    case "Request missing" -> {
                        requestsMissing++;
                        assertFalse(server.partitionExists("partition-" + i));
                    }
                    case "Response missing" -> {
                        responsesMissing++;
                        assertTrue(server.partitionExists("partition-" + i));
                    }
                    case "OK" -> {
                        successes++;
                        assertTrue(server.partitionExists("partition-" + i));
                    }
                    default -> fail("Unexpected outcome: " + result);
                }
            }
            assertEquals(20, requestsMissing);
            assertEquals(20, responsesMissing);
            assertEquals(20, successes);
            network.createPartition(new RequestId(PROXY_ID, 60), "after-queue");
            assertTrue(server.partitionExists("after-queue"));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void threadsShareOneProxyAndNetworkWithoutDuplicatingWrites() throws Exception {
        var server = new NodeServer();
        server.createPartition("shared");
        var network = new FakeNetworkInterface(new ServerNodeNetworkInterface(server, 60000));
        network.setNetworkLatency(0, 0);
        network.pushResults(RESPONSE_MISSING, RESPONSE_MISSING, OK, OK);
        var proxy = new NodeProxy(network, 60000);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                ready.countDown();
                await(start);
                proxy.writeToPartition("shared", "A");
            });
            var second = workers.submit(() -> {
                ready.countDown();
                await(start);
                proxy.writeToPartition("shared", "B");
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            proxy.commitPartition("shared");

            String content = proxy.readPartitionSnapshot("shared");
            assertTrue(content.equals("AB") || content.equals("BA"));
            assertFalse(proxy.recoveryRequired());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void serverCallsCanOverlapWhileTheNetworkIsReconfigured() throws Exception {
        var entered = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        var server = new NodeServer();
        var endpoint = new ServerNodeNetworkInterface(server, 60000) {
            @Override
            public void createPartition(RequestId requestId, String name) {
                entered.countDown();
                await(finish);
                super.createPartition(requestId, name);
            }
        };
        var network = new FakeNetworkInterface(endpoint);
        network.setNetworkLatency(1, 1);
        network.pushResults(OK, OK, RESPONSE_MISSING);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> network.createPartition(new RequestId(PROXY_ID, 0), "first"));
            var second = workers.submit(() -> network.createPartition(new RequestId(PROXY_ID, 1), "second"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            network.clearResults();
            network.pushResults(REQUEST_MISSING);
            network.setRandom(new Random(1));
            network.setNetworkLatency(0, 0);
            finish.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertTrue(server.partitionExists("first"));
            assertTrue(server.partitionExists("second"));
            assertEquals("Request missing", assertThrows(RetryableCommunicationException.class,
                    () -> network.createPartition(new RequestId(PROXY_ID, 2), "missing")).getMessage());
            assertFalse(server.partitionExists("missing"));
        } finally {
            finish.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Operation was not released");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
