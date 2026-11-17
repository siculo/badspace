package prototest;

import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.api.errors.network.ConnectionUnavailableException;
import prototest.api.errors.service.PartitionNotFoundException;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Checks when the proxy stays usable, when it requires recovery and what
 * happens to the calls still pending at the first final error.
 *
 * <p>Relevant orderings are forced with latches, waits are bounded and worker
 * errors reach the test. The timeout cases use a zero retry timeout and an
 * explicit outcome, so they do not depend on machine speed.
 */
public class NodeProxyRecoveryTest {
    @Test
    public void serviceErrorsLeaveTheProxyUsable() {
        var server = new NodeServer();
        var proxy = new NodeProxy(new ServerNodeNetworkInterface(server, 60000), 1000);

        assertThrows(PartitionNotFoundException.class, () -> proxy.writeToPartition("missing", "data"));
        assertFalse(proxy.recoveryRequired());
        proxy.createPartition("present");
        proxy.writeToPartition("present", "data");
        proxy.commitPartition("present");
        assertEquals("data", proxy.readPartitionSnapshot("present"));
    }

    @Test
    public void aLostResponseRequiresRecoveryAndBlocksAllNodeOperations() {
        var server = new NodeServer();
        var network = network(server);
        network.on(n -> n.createPartition(null, null)).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 0);

        var failure = assertThrows(NodeRecoveryRequiredException.class,
                () -> proxy.createPartition("created"));

        assertTrue(server.partitionExists("created"));
        assertTrue(proxy.recoveryRequired());
        assertEquals("Unrecoverable timeout", failure.getMessage());
        List<Runnable> calls = List.of(
                () -> proxy.createPartition("blocked"),
                () -> proxy.destroyPartition("created"),
                () -> proxy.partitionExists("created"),
                () -> proxy.writeToPartition("created", "blocked"),
                () -> proxy.commitPartition("created"),
                () -> proxy.readPartitionSnapshot("created"));
        for (Runnable call : calls) {
            var rejected = assertThrows(NodeRecoveryRequiredException.class, call::run);
            assertEquals("Connection unavailable: proxy recovery required", rejected.getMessage());
            assertSame(failure, rejected.getCause());
        }
        assertFalse(server.partitionExists("blocked"));
        assertTrue(server.partitionExists("created"));
        server.commitPartition("created");
        assertEquals("", server.readPartitionSnapshot("created"));
    }

    @Test
    public void aSuccessfulRetryLeavesTheProxyUsable() {
        var server = new NodeServer();
        var network = network(server);
        network.on(n -> n.createPartition(null, null)).onFirstCall().dropRequest()
                .on(n -> n.createPartition(null, null)).onCall(2).dropResponse();
        var proxy = new NodeProxy(network.build(), 60000);

        proxy.createPartition("present");

        assertFalse(proxy.recoveryRequired());
        assertTrue(proxy.partitionExists("present"));
    }

    @Test
    public void aFinalErrorFromTheEndpointRequiresRecovery() {
        var failure = new NodeRecoveryRequiredException("Request expired");
        var endpoint = new ServerNodeNetworkInterface(60000) {
            @Override
            public boolean partitionExists(RequestId requestId, String name) {
                throw failure;
            }
        };
        var proxy = new NodeProxy(endpoint, 1000);

        assertSame(failure, assertThrows(NodeRecoveryRequiredException.class,
                () -> proxy.partitionExists("any")));
        assertTrue(proxy.recoveryRequired());
        assertEquals("Connection unavailable: proxy recovery required", assertThrows(NodeRecoveryRequiredException.class,
                () -> proxy.createPartition("blocked")).getMessage());
    }

    @Test
    public void interruptionPreservesTheFlagAndRequiresRecovery() {
        var server = new NodeServer();
        var proxy = new NodeProxy(network(server).build(), 1000);
        try {
            Thread.currentThread().interrupt();
            assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("blocked"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(proxy.recoveryRequired());
            assertFalse(server.partitionExists("blocked"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void aFinalErrorFailsOtherCallsWithoutInterruptingTheirCallers() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var server = new NodeServer();
        var endpoint = new ServerNodeNetworkInterface(server, 60000) {
            @Override
            public void createPartition(RequestId requestId, String name) {
                entered.countDown();
                await(finish);
                super.createPartition(requestId, name);
            }

            @Override
            public boolean partitionExists(RequestId requestId, String name) {
                throw new NodeRecoveryRequiredException("Connection lost");
            }
        };
        var proxy = new NodeProxy(endpoint, 1000);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> {
                var failure = assertThrows(ConnectionUnavailableException.class,
                        () -> proxy.createPartition("in-flight"));
                assertFalse(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var original = assertThrows(NodeRecoveryRequiredException.class,
                    () -> proxy.partitionExists("any"));

            assertSame(original, result.get(5, TimeUnit.SECONDS).getCause());
            assertFalse(server.partitionExists("in-flight"));
            assertTrue(proxy.recoveryRequired());
            assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("blocked"));
            assertFalse(server.partitionExists("blocked"));
        } finally {
            finish.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void allPendingCallsFailBeforeTheTransportReturns() throws Exception {
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var transportThreads = new ConcurrentLinkedQueue<Thread>();
        var original = new NodeRecoveryRequiredException("Connection lost");
        var endpoint = new ServerNodeNetworkInterface(60000) {
            private void blockTransport() {
                transportThreads.add(Thread.currentThread());
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        released = release.await(5, TimeUnit.SECONDS);
                        if (!released) {
                            throw new AssertionError("Transport was not released");
                        }
                    } catch (InterruptedException ignored) {
                        // Deliberately ignore cancellation to keep the transport pending.
                    }
                }
            }

            @Override
            public void createPartition(RequestId requestId, String name) {
                blockTransport();
            }

            @Override
            public String readPartitionSnapshot(RequestId requestId, String name) {
                blockTransport();
                return "late result";
            }

            @Override
            public boolean partitionExists(RequestId requestId, String name) {
                throw original;
            }
        };
        var proxy = new NodeProxy(endpoint, 1000);
        var callers = Executors.newFixedThreadPool(3);
        List<Future<ConnectionUnavailableException>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                int index = i;
                results.add(callers.submit(() -> {
                    var failure = assertThrows(ConnectionUnavailableException.class, () -> {
                        if (index == 0) {
                            proxy.readPartitionSnapshot("pending");
                        } else {
                            proxy.createPartition("pending");
                        }
                    });
                    assertFalse(Thread.currentThread().isInterrupted());
                    return failure;
                }));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertSame(original, assertThrows(NodeRecoveryRequiredException.class,
                    () -> proxy.partitionExists("fail")));
            for (var result : results) {
                assertSame(original, result.get(5, TimeUnit.SECONDS).getCause());
            }
            assertEquals(1, release.getCount());
            assertTrue(proxy.recoveryRequired());
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            for (Thread transport : transportThreads) {
                transport.join(5000);
                assertFalse(transport.isAlive());
            }
        }
    }

    @Test
    public void recoveryAllowsNewCallsWithoutReusingRequestIds() {
        var server = new NodeServer();
        var network = network(server);
        network.on(n -> n.createPartition(null, null)).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 0);
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("present"));
        assertTrue(proxy.recoveryRequired());

        proxy.recover();

        assertFalse(proxy.recoveryRequired());
        proxy.destroyPartition("present");
        assertFalse(server.partitionExists("present"));
        proxy.createPartition("new");
        assertTrue(proxy.partitionExists("new"));
        proxy.recover();
        assertTrue(proxy.partitionExists("new"));
    }

    @Test
    public void aLateSuccessCannotResumeAfterRecovery() throws Exception {
        verifyLateResponseAfterRecovery(false);
    }

    @Test
    public void aLateFailureCannotPutTheRecoveredProxyBackInError() throws Exception {
        verifyLateResponseAfterRecovery(true);
    }

    private static void verifyLateResponseAfterRecovery(boolean lateFailure) throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var transportDone = new CountDownLatch(1);
        var server = new NodeServer();
        var endpoint = new ServerNodeNetworkInterface(server, 60000) {
            @Override
            public void createPartition(RequestId requestId, String name) {
                if (!name.equals("old")) {
                    super.createPartition(requestId, name);
                    return;
                }
                entered.countDown();
                try {
                    // Keep the transport pending after cancellation to test immediate delivery.
                    try {
                        finish.await(5, TimeUnit.SECONDS);
                        throw new AssertionError("Transport worker was not interrupted");
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                    }
                    await(finish);
                    if (lateFailure) {
                        throw new NodeRecoveryRequiredException("Late failure");
                    }
                    super.createPartition(requestId, name);
                } finally {
                    transportDone.countDown();
                }
            }

            @Override
            public boolean partitionExists(RequestId requestId, String name) {
                if (name.equals("fail")) {
                    throw new NodeRecoveryRequiredException("Connection lost");
                }
                return super.partitionExists(requestId, name);
            }
        };
        var proxy = new NodeProxy(endpoint, 1000);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> {
                var failure = assertThrows(ConnectionUnavailableException.class,
                        () -> proxy.createPartition("old"));
                assertFalse(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(NodeRecoveryRequiredException.class, () -> proxy.partitionExists("fail"));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertNotNull(result.get(5, TimeUnit.SECONDS));
            assertEquals(1, transportDone.getCount());

            proxy.recover();
            proxy.createPartition("new");
            assertFalse(proxy.recoveryRequired());
            finish.countDown();
            assertTrue(transportDone.await(5, TimeUnit.SECONDS));
            assertFalse(proxy.recoveryRequired());
            assertTrue(proxy.partitionExists("new"));
            assertEquals(!lateFailure, server.partitionExists("old"));
        } finally {
            finish.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static FakeNetworkInterface network(NodeServer server) {
        var network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000));
        return network;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Operation was not released");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new NodeRecoveryRequiredException("Call interrupted", error);
        }
    }
}
