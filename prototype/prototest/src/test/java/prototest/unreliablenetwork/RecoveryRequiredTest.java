package prototest.unreliablenetwork;

import org.junit.Rule;
import org.junit.Test;

import prototest.example.server.NodeServer;
import prototest.example.server.NodeTestEndpoint;
import prototest.example.server.NodeTestNetwork;
import prototest.example.server.TestEndpoints;
import prototest.remote.api.errors.ConnectionUnavailableException;
import prototest.remote.api.errors.NodeRecoveryRequiredException;
import prototest.remote.network.FakeNetworkInterface;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static prototest.example.server.NodeTestEndpoint.proxyFor;
import static org.junit.Assert.*;
import static prototest.remote.network.FakeNetworkInterface.any;

/**
 * Checks what makes the proxy require recovery and how it blocks node operations.
 *
 * <p>In the diagrams, {@code test} is the test thread. A node call goes through
 * the proxy and the network. On the network, {@code *} marks a dropped or held
 * message. On another line, it marks an event.
 */
public class RecoveryRequiredTest {
    @Rule
    public final TestEndpoints endpoints = new TestEndpoints();

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> CREATE = n -> n.createPartition(any(), any());
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> COMMIT = n -> n.commitPartition(any(), any());

    /**
     * The server creates the partition, but the response is lost and the retry
     * timeout is zero. The outcome is uncertain for the proxy, so it requires
     * recovery. The server keeps the partition it already created.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------------------------------->|  createPartition, the server creates it
     * |           |           *<----------|  the network drops the response
     * |<----------|           |           |  NodeRecoveryRequiredException,
     * |           |           |           |  "Unrecoverable timeout"
     * }</pre>
     */
    @Test
    public void aLostResponseAfterCreateRequiresRecovery() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();

        var failure = assertThrows(NodeRecoveryRequiredException.class,
                () -> proxy.createPartition("created"));

        assertTrue(server.partitionExists("created"));
        assertTrue(proxy.recoveryRequired());
        assertEquals("Unrecoverable timeout", failure.getMessage());
    }

    /**
     * A caller that is already interrupted makes the proxy require recovery
     * without sending the request. Its interrupt flag is preserved. The fake
     * network only forwards the calls here: it has no rules.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * *           |           |           |  the test thread interrupts itself
     * |---------->|           |           |  createPartition
     * |<----------|           |           |  NodeRecoveryRequiredException, no request
     * *           |           |           |  the interrupt flag is still set
     * }</pre>
     */
    @Test
    public void interruptionPreservesTheFlagAndRequiresRecovery() {
        var server = new NodeServer();
        var proxy = proxyFor(FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000))).build(), 1000);
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
     * The server commits the writes while the network holds the response.
     * Interrupting the caller while it waits preserves its interrupt flag
     * and makes recovery necessary. The commit remains visible on the server.
     *
     * <p>The diagram starts with writes waiting to be committed. {@code caller}
     * is the thread that calls commit.
     * <pre>{@code
     * caller    test      proxy     network   server
     * |         |         |         |         |
     * |-------------------------------------->|  commitPartition, publishes "saved"
     * |         |         |         *<--------|  the network holds the response
     * |<--------|         |         |         |  the test interrupts the caller
     * |<------------------|         |         |  NodeRecoveryRequiredException
     * *         |         |         |         |  the interrupt flag is still set
     * |         |         |         |         *  the test reads "saved" on the server
     * }</pre>
     */
    @Test
    public void interruptionWhileWaitingForCommitRequiresRecovery() throws Exception {
        var server = new NodeServer();
        var endpoint = endpoints.track(new NodeTestEndpoint(server, 60000));
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint)
                .on(COMMIT).onFirstCall().holdResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();
        proxy.createPartition("p");
        proxy.writeToPartition("p", "saved");

        var pending = new FutureTask<Void>(() -> {
            var failure = assertThrows(NodeRecoveryRequiredException.class,
                    () -> proxy.commitPartition("p"));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        }, null);
        var caller = Thread.ofVirtual().start(pending);
        try {
            var commit = network.awaitAttempt(COMMIT, 1, WAIT);
            commit.response().awaitArrival(WAIT);
            assertFalse(pending.isDone());
            caller.interrupt();
            pending.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(proxy.recoveryRequired());
            assertEquals("saved", server.readPartitionSnapshot("p"));

            // Release the held response so the transport can finish before teardown.
            commit.response().deliver();
            commit.awaitCompletion(WAIT);
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            caller.interrupt();
            network.releaseHeldMessages();
            assertTrue(caller.join(WAIT));
        }
    }

    /**
     * In a failed session, every node operation is rejected locally and the
     * rejection keeps the error that made recovery necessary as its cause.
     *
     * <p>The diagram starts after a lost create response makes recovery necessary.
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  each node operation
     * |<----------|           |           |  ConnectionUnavailableException,
     * |           |           |           |  caused by the first error
     * }</pre>
     */
    @Test
    public void recoveryRequiredBlocksAllNodeOperations() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();

        var failure = assertThrows(NodeRecoveryRequiredException.class,
                () -> proxy.createPartition("created"));

        List<Runnable> calls = List.of(
                () -> proxy.createPartition("blocked"),
                () -> proxy.destroyPartition("created"),
                () -> proxy.partitionExists("created"),
                () -> proxy.writeToPartition("created", "blocked"),
                () -> proxy.commitPartition("created"),
                () -> proxy.readPartitionSnapshot("created"));
        for (Runnable call : calls) {
            var rejected = assertThrows(ConnectionUnavailableException.class, call::run);
            assertEquals("Connection unavailable: proxy recovery required", rejected.getMessage());
            assertSame(failure, rejected.getCause());
        }
        assertTrue(proxy.recoveryRequired());
        assertFalse(server.partitionExists("blocked"));
        assertTrue(server.partitionExists("created"));
        server.commitPartition("created");
        assertEquals("", server.readPartitionSnapshot("created"));
    }
}
