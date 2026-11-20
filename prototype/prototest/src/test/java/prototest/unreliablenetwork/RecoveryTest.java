package prototest.unreliablenetwork;

import org.junit.Rule;
import org.junit.Test;

import prototest.api.Node;
import prototest.api.RequestId;
import prototest.api.errors.network.ConnectionUnavailableException;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;
import prototest.server.TestEndpoints;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.*;
import static prototest.proxy.FakeNetworkInterface.any;
import static prototest.proxy.FakeNetworkInterface.eq;

/**
 * Checks when lost or held messages require the recovery of the proxy, and
 * that a response arriving after the recovery does not change its state.
 *
 * <p>Uses the real proxy and the real server adapter behind the fake network.
 * The timeout cases use a zero retry timeout and an explicit outcome, so they
 * do not depend on machine speed.
 */
public class RecoveryTest {
    @Rule
    public final TestEndpoints endpoints = new TestEndpoints();

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector CREATE = n -> n.createPartition(any(), any());

    /**
     * A lost create response triggers recovery. The client rebuilds the partition,
     * but the first repair fails when the network drops the commit response.
     * The proxy rejects calls from other threads during repair and keeps normal
     * calls blocked after the failure. A second repair rebuilds the partition
     * from scratch and makes the expected snapshot available.
     */
    @Test
    public void repairRestartsAfterALostCommitResponse() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(endpoints.track(new ServerNodeNetworkInterface(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        network.on(n -> n.commitPartition(any(), any())).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("p"));

        Predicate<Node> repair = node -> {
            assertTrue(proxy.recoveryRequired());
            CompletableFuture.runAsync(() ->
                    assertThrows(ConnectionUnavailableException.class, () -> proxy.partitionExists("p"))).join();
            node.destroyPartition("p");
            node.createPartition("p");
            node.writeToPartition("p", "saved");
            node.commitPartition("p");
            return "saved".equals(node.readPartitionSnapshot("p"));
        };
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.recover(repair));
        assertThrows(ConnectionUnavailableException.class, () -> proxy.readPartitionSnapshot("p"));

        assertTrue(proxy.recover(repair));
        assertFalse(proxy.recoveryRequired());
        assertEquals("saved", proxy.readPartitionSnapshot("p"));
    }

    /**
     * A repair that finds an unexpected snapshot returns false. The proxy keeps
     * normal calls blocked until a later repair writes and confirms the expected
     * snapshot. Once healthy, the proxy skips the repair callback.
     */
    @Test
    public void anIncompleteRepairKeepsTheProxyBlockedUntilTheClientConfirmsTheSnapshot() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(endpoints.track(new ServerNodeNetworkInterface(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("p"));

        assertFalse(proxy.recover(node -> "saved".equals(node.readPartitionSnapshot("p"))));
        assertTrue(proxy.recoveryRequired());
        assertThrows(ConnectionUnavailableException.class, () -> proxy.writeToPartition("p", "blocked"));

        assertTrue(proxy.recover(node -> {
            node.writeToPartition("p", "saved");
            node.commitPartition("p");
            return "saved".equals(node.readPartitionSnapshot("p"));
        }));
        assertEquals("saved", proxy.readPartitionSnapshot("p"));
        assertTrue(proxy.recover(node -> { throw new AssertionError("Healthy proxy ran repair"); }));
    }

    /**
     * The server creates the partition, but the response is lost and the retry
     * timeout is zero. The outcome is uncertain for the proxy, so it requires
     * recovery: every following operation is rejected before reaching the network,
     * with the first error as its cause. The server keeps the effects already
     * applied.
     */
    @Test
    public void aLostResponseRequiresRecoveryAndBlocksAllNodeOperations() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(endpoints.track(new ServerNodeNetworkInterface(server, 60000)));
        network.on(n -> n.createPartition(any(), any())).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 0);
        proxy.connect();

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

    /**
     * A caller that is already interrupted makes the proxy require recovery
     * without sending the request. Its interrupt flag is preserved. The fake
     * network only forwards the calls here: it has no rules.
     */
    @Test
    public void interruptionPreservesTheFlagAndRequiresRecovery() {
        var server = new NodeServer();
        var proxy = new NodeProxy(FakeNetworkInterface.wrap(endpoints.track(new ServerNodeNetworkInterface(server, 60000))).build(), 1000);
        proxy.connect();
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

    /**
     * After a lost response, {@code recover()} accepts new calls again. The new
     * calls use new request IDs, so the server does not answer them with the saved
     * results of old calls. A second {@code recover()} on a usable proxy changes
     * nothing.
     */
    @Test
    public void recoveryAllowsNewCallsWithoutReusingRequestIds() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(endpoints.track(new ServerNodeNetworkInterface(server, 60000)));
        network.on(n -> n.createPartition(any(), any())).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 0);
        proxy.connect();
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

    /**
     * The fake network holds the response of a call while another call fails the
     * proxy. After the recovery the held response is delivered: it does not resume
     * the old call or change the state of the proxy. See
     * {@link #verifyHeldResponseAfterRecovery(boolean)}.
     */
    @Test
    public void aHeldResponseCanBeDeliveredAfterProxyRecovery() throws Exception {
        verifyHeldResponseAfterRecovery(false);
    }

    /**
     * Like {@link #aHeldResponseCanBeDeliveredAfterProxyRecovery()}, but the held
     * response is lost: the old call does not retry after the recovery.
     */
    @Test
    public void aHeldResponseCanBeLostAfterProxyRecovery() throws Exception {
        verifyHeldResponseAfterRecovery(true);
    }

    /**
     * Runs the late response scenario.
     *
     * <ol>
     *   <li>The server creates {@code "old"}, but the fake network holds the
     *       response.</li>
     *   <li>The request of {@code "fail"} is lost and the retry timeout is zero, so
     *       the proxy requires recovery. The pending {@code "old"} call fails with
     *       {@link ConnectionUnavailableException}.</li>
     *   <li>After {@code recover()} the proxy creates {@code "new"}.</li>
     *   <li>The held response is delivered or lost. The old call never makes a
     *       second attempt and the proxy stays usable.</li>
     * </ol>
     *
     * @param dropResponse whether the held response is lost instead of delivered
     */
    private void verifyHeldResponseAfterRecovery(boolean dropResponse) throws Exception {
        var server = new NodeServer();
        var transport = new AtomicReference<Thread>();
        var oldAttempts = new AtomicInteger();
        var endpoint = endpoints.track(new ServerNodeNetworkInterface(server, 60000) {
            @Override
            public void createPartition(RequestId requestId, String name) {
                if (name.equals("old")) {
                    transport.set(Thread.currentThread());
                    oldAttempts.incrementAndGet();
                }
                super.createPartition(requestId, name);
            }
        });
        var network = FakeNetworkInterface.wrap(endpoint)
                .on(n -> n.createPartition(any(), eq("old"))).onFirstCall().holdResponse()
                .on(n -> n.createPartition(any(), eq("fail"))).dropRequest();
        var proxy = new NodeProxy(network.build(), 0);
        proxy.connect();
        var workers = Executors.newSingleThreadExecutor();
        try {
            var pending = workers.submit(() -> {
                var error = assertThrows(ConnectionUnavailableException.class,
                        () -> proxy.createPartition("old"));
                assertFalse(Thread.currentThread().isInterrupted());
                return error;
            });
            var old = network.awaitAttempt(CREATE, 1);
            old.response().awaitArrival(WAIT);
            assertTrue(server.partitionExists("old"));
            var failure = assertThrows(NodeRecoveryRequiredException.class,
                    () -> proxy.createPartition("fail"));
            assertSame(failure, pending.get(5, TimeUnit.SECONDS).getCause());
            assertThrows(AssertionError.class, network::assertAllRulesUsedAndCallsFinished);

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
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            if (transport.get() != null) {
                transport.get().join(5000);
                assertFalse(transport.get().isAlive());
            }
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
