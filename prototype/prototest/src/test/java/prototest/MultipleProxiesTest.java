package prototest;

import org.junit.Test;
import prototest.api.NodeNetworkInterface;
import prototest.api.RequestId;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static prototest.proxy.FakeNetworkInterface.OpResult.*;

public class MultipleProxiesTest {
    private final NodeServer server = new NodeServer();
    private final NodeNetworkInterface endpoint = new ServerNodeNetworkInterface(server, 60000);

    @Test
    public void proxiesCreateDifferentPartitionsOnTheSameServer() {
        var first = new NodeProxy(endpoint, 1000);
        var second = new NodeProxy(endpoint, 1000);

        first.createPartition("first");
        second.createPartition("second");

        assertTrue(server.partitionExists("first"));
        assertTrue(server.partitionExists("second"));
    }

    @Test
    public void proxiesReceiveTheirOwnResults() {
        server.createPartition("present");
        var first = new NodeProxy(endpoint, 1000);
        var second = new NodeProxy(endpoint, 1000);

        assertTrue(first.partitionExists("present"));
        assertFalse(second.partitionExists("absent"));
    }

    @Test
    public void aNewProxyDoesNotReuseThePreviousProxysRequests() {
        new NodeProxy(endpoint, 1000).createPartition("previous");
        new NodeProxy(endpoint, 1000).destroyPartition("previous");

        assertFalse(server.partitionExists("previous"));
    }

    @Test
    public void retriesKeepTheSameIdAndDoNotDuplicateWritesOrCommits() {
        var firstNetwork = new RecordingNetwork(endpoint);
        var secondNetwork = new RecordingNetwork(endpoint);
        var first = new NodeProxy(firstNetwork, 1000);
        var second = new NodeProxy(secondNetwork, 1000);
        server.createPartition("shared");

        firstNetwork.pushResults(REQUEST_MISSING, RESPONSE_MISSING, OK);
        first.writeToPartition("shared", "A");
        secondNetwork.pushResults(RESPONSE_MISSING, OK);
        second.writeToPartition("shared", "B");
        firstNetwork.pushResults(RESPONSE_MISSING, OK);
        first.commitPartition("shared");

        assertEquals("AB", server.readPartitionSnapshot("shared"));
        assertEquals("AB", second.readPartitionSnapshot("shared"));
        assertEquals(3, firstNetwork.writeIds.size());
        assertEquals(firstNetwork.writeIds.get(0), firstNetwork.writeIds.get(1));
        assertEquals(firstNetwork.writeIds.get(0), firstNetwork.writeIds.get(2));
        assertEquals(2, secondNetwork.writeIds.size());
        assertEquals(secondNetwork.writeIds.get(0), secondNetwork.writeIds.get(1));
        assertEquals(2, firstNetwork.commitIds.size());
        assertEquals(firstNetwork.commitIds.get(0), firstNetwork.commitIds.get(1));
        RequestId writeId = firstNetwork.writeIds.get(0);
        RequestId commitId = firstNetwork.commitIds.get(0);
        assertEquals(writeId.proxyId(), commitId.proxyId());
        assertTrue(commitId.sequence() > writeId.sequence());
        assertNotEquals(writeId.proxyId(), secondNetwork.writeIds.get(0).proxyId());
    }

    @Test
    public void concurrentProxiesKeepAllTheirWrites() throws Exception {
        server.createPartition("shared");
        var first = new NodeProxy(endpoint, 1000);
        var second = new NodeProxy(endpoint, 1000);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstResult = workers.submit(() -> writeAfterStart(first, "A", ready, start));
            var secondResult = workers.submit(() -> writeAfterStart(second, "B", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            firstResult.get(5, TimeUnit.SECONDS);
            secondResult.get(5, TimeUnit.SECONDS);
            first.commitPartition("shared");

            String content = server.readPartitionSnapshot("shared");
            assertTrue(content.equals("AB") || content.equals("BA"));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void writeAfterStart(NodeProxy proxy, String content,
                                        CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Workers did not start");
            }
            proxy.writeToPartition("shared", content);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static final class RecordingNetwork extends FakeNetworkInterface {
        private final List<RequestId> writeIds = new ArrayList<>();
        private final List<RequestId> commitIds = new ArrayList<>();

        private RecordingNetwork(NodeNetworkInterface node) {
            super(node);
            setNetworkLatency(0, 0);
        }

        @Override
        public void writeToPartition(RequestId requestId, String name, String data) {
            writeIds.add(requestId);
            super.writeToPartition(requestId, name, data);
        }

        @Override
        public void commitPartition(RequestId requestId, String name) {
            commitIds.add(requestId);
            super.commitPartition(requestId, name);
        }
    }
}
