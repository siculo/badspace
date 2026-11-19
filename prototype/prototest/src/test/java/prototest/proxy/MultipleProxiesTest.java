package prototest.proxy;

import org.junit.Test;
import prototest.api.NodeNetworkInterface;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Checks that several proxies use the same endpoint without mixing their
 * requests or results.
 *
 * <p>Uses the real proxy and the real server adapter, without the fake
 * network: retries of several proxies are checked in
 * {@link prototest.unreliablenetwork.RetryTest}. Does not use
 * {@link TestService}, whose writers and readers still skip the network.
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
