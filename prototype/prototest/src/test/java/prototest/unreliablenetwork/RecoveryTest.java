package prototest.unreliablenetwork;

import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.ConnectionUnavailableException;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Checks when lost or held messages require the recovery of the proxy, and
 * that a response arriving after the recovery does not change its state.
 *
 * <p>Uses the real proxy and the real server adapter behind the fake network.
 * The timeout cases use a zero retry timeout and an explicit outcome, so they
 * do not depend on machine speed.
 */
public class RecoveryTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector CREATE = n -> n.createPartition(null, null);

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
        var network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000));
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

    /**
     * A caller that is already interrupted makes the proxy require recovery
     * without sending the request. Its interrupt flag is preserved. The fake
     * network only forwards the calls here: it has no rules.
     */
    @Test
    public void interruptionPreservesTheFlagAndRequiresRecovery() {
        var server = new NodeServer();
        var proxy = new NodeProxy(FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000)).build(), 1000);
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
        var network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000));
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
    private static void verifyHeldResponseAfterRecovery(boolean dropResponse) throws Exception {
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
        var network = FakeNetworkInterface.wrap(endpoint)
                .on(CREATE).onFirstCall().withArgs(args -> args[1].equals("old")).holdResponse()
                .on(CREATE).withArgs(args -> args[1].equals("fail")).dropRequest();
        var proxy = new NodeProxy(network.build(), 0);
        FakeNetworkInterface.Attempt old = null;
        var workers = Executors.newSingleThreadExecutor();
        try {
            var pending = workers.submit(() -> {
                var error = assertThrows(ConnectionUnavailableException.class,
                        () -> proxy.createPartition("old"));
                assertFalse(Thread.currentThread().isInterrupted());
                return error;
            });
            old = network.awaitAttempt(CREATE, 1);
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
            release(old, true);
            if (transport.get() != null) {
                transport.get().join(5000);
                assertFalse(transport.get().isAlive());
            }
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
