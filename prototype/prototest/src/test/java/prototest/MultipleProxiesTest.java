package prototest;

import org.junit.Test;
import prototest.api.NodeNetworkInterface;
import prototest.api.RequestId;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Checks that several proxies use the same endpoint without mixing their
 * requests or results.
 *
 * <p>Uses the real proxy and the real server adapter. The retry tests also go
 * through the fake network, with explicit delivery rules. Does not
 * use {@link TestService}, whose writers and readers still skip the network.
 */
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
    public void retriesKeepTheSameIdAndDoNotDuplicateWritesOrCommits() throws Exception {
        var firstNetwork = FakeNetworkInterface.wrap(endpoint)
                .on(n -> n.writeToPartition(null, null, null)).onFirstCall().dropRequest()
                .on(n -> n.writeToPartition(null, null, null)).onCall(2).dropResponse()
                .on(n -> n.commitPartition(null, null)).onFirstCall().dropResponse();
        var secondNetwork = FakeNetworkInterface.wrap(endpoint)
                .on(n -> n.writeToPartition(null, null, null)).onFirstCall().dropResponse();
        var first = new NodeProxy(firstNetwork.build(), 60000);
        var second = new NodeProxy(secondNetwork.build(), 60000);
        server.createPartition("shared");

        first.writeToPartition("shared", "A");
        second.writeToPartition("shared", "B");
        first.commitPartition("shared");

        assertEquals("AB", server.readPartitionSnapshot("shared"));
        assertEquals("AB", second.readPartitionSnapshot("shared"));
        firstNetwork.assertAllRulesUsedAndCallsFinished();
        secondNetwork.assertAllRulesUsedAndCallsFinished();
        RequestId writeId = (RequestId) firstNetwork.awaitCall(
                n -> n.writeToPartition(null, null, null), 1, Duration.ZERO).invocation().args()[0];
        for (int number = 2; number <= 3; number++) {
            assertEquals(writeId, firstNetwork.awaitCall(n -> n.writeToPartition(null, null, null),
                    number, Duration.ZERO).invocation().args()[0]);
        }
        RequestId secondWriteId = (RequestId) secondNetwork.awaitCall(
                n -> n.writeToPartition(null, null, null), 1, Duration.ZERO).invocation().args()[0];
        assertEquals(secondWriteId, secondNetwork.awaitCall(n -> n.writeToPartition(null, null, null),
                2, Duration.ZERO).invocation().args()[0]);
        RequestId commitId = (RequestId) firstNetwork.awaitCall(
                n -> n.commitPartition(null, null), 1, Duration.ZERO).invocation().args()[0];
        assertEquals(commitId, firstNetwork.awaitCall(n -> n.commitPartition(null, null),
                2, Duration.ZERO).invocation().args()[0]);
        assertEquals(writeId.proxyId(), commitId.proxyId());
        assertTrue(commitId.sequence() > writeId.sequence());
        assertNotEquals(writeId.proxyId(), secondWriteId.proxyId());
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
}
