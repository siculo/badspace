package prototest.unreliablenetwork;

import org.junit.Rule;
import org.junit.Test;

import prototest.example.api.Node;
import prototest.example.server.NodeServer;
import prototest.example.server.NodeTestEndpoint;
import prototest.example.server.NodeTestNetwork;
import prototest.example.server.TestEndpoints;
import badspace.remote.api.errors.ConnectionUnavailableException;
import badspace.remote.api.errors.NodeRecoveryRequiredException;
import badspace.remote.network.FakeNetworkInterface;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static prototest.example.server.NodeTestEndpoint.proxyFor;
import static org.junit.Assert.*;
import static badspace.remote.network.FakeNetworkInterface.any;

/**
 * Checks the outcomes of the repair callback and who can use the proxy while
 * the callback runs.
 *
 * <p>In the diagrams, {@code test} is the test thread that runs the callback.
 * A node call goes through the proxy and the network. On the network,
 * {@code *} marks a dropped message.
 */
public class RepairTest {
    @Rule
    public final TestEndpoints endpoints = new TestEndpoints();

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> CREATE = n -> n.createPartition(any(), any());
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> COMMIT = n -> n.commitPartition(any(), any());

    /**
     * A repair writes and confirms the expected snapshot. Once the callback
     * returns true, the proxy accepts normal calls again.
     *
     * <p>The diagram starts after an incomplete repair.
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)"
     * |           |---------------------->|  the server renews the session
     * |           |<----------------------|
     * |<----------|           |           |  the proxy runs the repair
     * |---------------------------------->|  write, commit
     * |<----------------------------------|
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  returns "saved", the repair returns true
     * |<----------|           |           |  recover returns true
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  returns "saved"
     * }</pre>
     */
    @Test
    public void aSuccessfulRepairMakesTheProxyUsable() {
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("p"));
        assertFalse(proxy.recover(node -> "saved".equals(node.readPartitionSnapshot("p"))));

        assertTrue(proxy.recover(node -> {
            node.writeToPartition("p", "saved");
            node.commitPartition("p");
            return "saved".equals(node.readPartitionSnapshot("p"));
        }));

        assertFalse(proxy.recoveryRequired());
        assertEquals("saved", proxy.readPartitionSnapshot("p"));
    }

    /**
     * A healthy proxy returns true from recover without renewing the session
     * or running the repair callback.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)" on a healthy proxy
     * |<----------|           |           |  returns true, the repair is skipped
     * }</pre>
     */
    @Test
    public void aHealthyProxySkipsTheRepair() {
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(60000)));
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();

        assertTrue(proxy.recover(node -> { throw new AssertionError("Healthy proxy ran repair"); }));

        assertFalse(proxy.recoveryRequired());
        assertThrows(IllegalStateException.class,
                () -> network.awaitAttempt(node -> node.recover(any()), 1, Duration.ZERO));
        proxy.createPartition("p");
        assertTrue(proxy.partitionExists("p"));
    }

    /**
     * A repair that finds an unexpected snapshot returns false. The proxy still
     * requires recovery and keeps normal calls blocked.
     *
     * <p>The diagram starts with the proxy in need of recovery.
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)"
     * |           |---------------------->|  the server renews the session
     * |           |<----------------------|
     * |<----------|           |           |  the proxy runs the repair
     * |           |           |           |
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  not "saved", so the repair returns false
     * |<----------|           |           |  recover returns false
     * |           |           |           |
     * |---------->|           |           |  writeToPartition
     * |<----------|           |           |  ConnectionUnavailableException, no request
     * }</pre>
     */
    @Test
    public void anIncompleteRepairKeepsTheProxyBlocked() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("p"));

        assertFalse(proxy.recover(node -> "saved".equals(node.readPartitionSnapshot("p"))));
        assertTrue(proxy.recoveryRequired());
        assertThrows(ConnectionUnavailableException.class, () -> proxy.writeToPartition("p", "blocked"));
    }

    /**
     * A repair commits a snapshot and then throws its own exception. The proxy
     * passes the same exception to the caller and keeps normal calls blocked.
     * A second repair can confirm the snapshot committed by the first one.
     *
     * <p>The diagram starts after a lost create response makes recovery necessary.
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)"
     * |           |---------------------->|  the server renews the session
     * |           |<----------------------|
     * |<----------|           |           |  the proxy runs the repair
     * |---------------------------------->|  write, commit
     * |<----------------------------------|
     * |---------->|           |           |  the repair throws IllegalStateException
     * |<----------|           |           |  recover throws the same exception
     * |           |           |           |
     * |---------->|           |           |  readPartitionSnapshot
     * |<----------|           |           |  ConnectionUnavailableException
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)"
     * |           |---------------------->|  the server renews the session
     * |           |<----------------------|
     * |<----------|           |           |  the proxy runs the second repair
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  returns "saved", the repair returns true
     * |<----------|           |           |  recover returns true
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  returns "saved"
     * }</pre>
     */
    @Test
    public void recoveryCanResumeAfterTheRepairThrowsItsOwnException() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("p"));

        var callbackFailure = new IllegalStateException("Repair stopped after commit");
        var failure = assertThrows(IllegalStateException.class, () -> proxy.recover(node -> {
            node.writeToPartition("p", "saved");
            node.commitPartition("p");
            throw callbackFailure;
        }));

        assertSame(callbackFailure, failure);
        assertTrue(proxy.recoveryRequired());
        assertThrows(ConnectionUnavailableException.class, () -> proxy.readPartitionSnapshot("p"));

        assertTrue(proxy.recover(node -> "saved".equals(node.readPartitionSnapshot("p"))));
        assertFalse(proxy.recoveryRequired());
        assertEquals("saved", proxy.readPartitionSnapshot("p"));
    }

    /**
     * A lost create response triggers recovery. During repair the network drops
     * the commit response, and the repair catches the error and returns true.
     * Recovery still fails with a new error whose cause is the ignored call
     * failure, and the proxy still requires recovery.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------------------------------->|  createPartition, the server creates p
     * |           |           *<----------|  the network drops the response
     * |<----------|           |           |  NodeRecoveryRequiredException
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)"
     * |           |---------------------->|  the server renews the session
     * |           |<----------------------|
     * |<----------|           |           |  the proxy runs the repair
     * |           |           |           |
     * |---------------------------------->|  commit, the server commits p
     * |           |           *<----------|  the network drops the response
     * |<----------|           |           |  NodeRecoveryRequiredException,
     * |           |           |           |  the repair catches it
     * |---------->|           |           |  the repair returns true
     * |<----------|           |           |  NodeRecoveryRequiredException from recover,
     * |           |           |           |  caused by the ignored error
     * }</pre>
     */
    @Test
    public void recoveryFailsWhenRepairIgnoresACallFailure() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        network.on(COMMIT).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("p"));

        var callFailure = new AtomicReference<NodeRecoveryRequiredException>();
        var recoveryFailure = assertThrows(NodeRecoveryRequiredException.class, () -> proxy.recover(node -> {
            callFailure.set(assertThrows(NodeRecoveryRequiredException.class, () -> node.commitPartition("p")));
            return true;
        }));

        assertEquals("Node repair ignored a failed call", recoveryFailure.getMessage());
        assertSame(callFailure.get(), recoveryFailure.getCause());
        assertTrue(proxy.recoveryRequired());
    }

    /**
     * During repair, the proxy reports that recovery is required and rejects
     * calls from other threads without waiting for the repair to finish.
     * {@code thread} is the thread that the repair starts.
     *
     * <pre>{@code
     * test      thread    proxy     network   server
     * |         |         |         |         |
     * |-------------------------------------->|  createPartition, the server creates p
     * |         |         |         *<--------|  the network drops the response
     * |<------------------|         |         |  NodeRecoveryRequiredException
     * |         |         |         |         |
     * |------------------>|         |         |  "recover(repair)"
     * |         |         |------------------>|  the server renews the session
     * |         |         |<------------------|
     * |<------------------|         |         |  the proxy runs the repair
     * |         |         |         |         |
     * |------------------>|         |         |  recoveryRequired
     * |<------------------|         |         |  returns true
     * |-------->|         |         |         |  the repair starts a thread and waits
     * |         |-------->|         |         |  partitionExists
     * |         |<--------|         |         |  ConnectionUnavailableException at once
     * |<--------|         |         |         |  the thread ends
     * |         |         |         |         |
     * |-------------------------------------->|  partitionExists
     * |<--------------------------------------|  returns true, the repair returns true
     * |<------------------|         |         |  recover returns true
     * |         |         |         |         |
     * |-------------------------------------->|  partitionExists
     * |<--------------------------------------|  returns true
     * }</pre>
     */
    @Test
    public void repairRejectsCallsFromOtherThreads() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("p"));

        assertTrue(proxy.recover(node -> {
            assertTrue(proxy.recoveryRequired());
            var otherThread = CompletableFuture.runAsync(() ->
                    assertThrows(ConnectionUnavailableException.class, () -> proxy.partitionExists("p")));
            awaitCompletion(otherThread);
            return node.partitionExists("p");
        }));
        assertTrue(proxy.partitionExists("p"));
    }

    /**
     * After session renewal, the proxy rejects other connection changes while
     * the repair callback is still running. The current repair can then finish
     * and make the proxy usable.
     *
     * <p>The diagram starts after a lost create response makes recovery necessary.
     * {@code thread} tries to change the connection while the callback waits.
     * <pre>{@code
     * test      thread    proxy     network   server
     * |         |         |         |         |
     * |------------------>|         |         |  "recover(repair)"
     * |         |         |------------------>|  the server renews the session
     * |         |         |<------------------|
     * |<------------------|         |         |  the proxy runs the repair
     * |-------->|         |         |         |  the repair starts a thread and waits
     * |         |-------->|         |         |  each of recover, disconnect, connect
     * |         |<--------|         |         |  IllegalStateException at once
     * |<--------|         |         |         |  the thread ends
     * |         |         |         |         |
     * |-------------------------------------->|  partitionExists
     * |<--------------------------------------|  returns true, the repair returns true
     * |<------------------|         |         |  recover returns true
     * |-------------------------------------->|  partitionExists
     * |<--------------------------------------|  returns true
     * }</pre>
     */
    @Test
    public void repairRejectsOtherConnectionChanges() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("p"));

        assertTrue(proxy.recover(node -> {
            var otherThread = CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, proxy::recover);
                assertThrows(IllegalStateException.class, proxy::disconnect);
                assertThrows(IllegalStateException.class, proxy::connect);
            });
            awaitCompletion(otherThread);
            return node.partitionExists("p");
        }));
        assertFalse(proxy.recoveryRequired());
        assertTrue(proxy.partitionExists("p"));
    }

    private static void awaitCompletion(CompletableFuture<?> task) {
        try {
            task.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            throw new AssertionError("Call from another thread was not rejected", error.getCause());
        } catch (TimeoutException error) {
            throw new AssertionError("Call from another thread waited for the repair", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    /**
     * A lost create response triggers recovery. The client rebuilds the partition,
     * but the first repair fails when the network drops the commit response.
     * The proxy keeps normal calls blocked after the failure. A second repair
     * rebuilds the partition from scratch and makes the expected snapshot available.
     *
     * <p>The lost create response and the failed first repair:
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------------------------------->|  createPartition, the server creates p
     * |           |           *<----------|  the network drops the response
     * |<----------|           |           |  NodeRecoveryRequiredException
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)"
     * |           |---------------------->|  the server renews the session
     * |           |<----------------------|
     * |<----------|           |           |  the proxy runs the repair
     * |           |           |           |
     * |---------------------------------->|  destroy, create, write
     * |<----------------------------------|
     * |---------------------------------->|  commit, the server commits p
     * |           |           *<----------|  the network drops the response
     * |<----------|           |           |  NodeRecoveryRequiredException from recover
     * |           |           |           |
     * |---------->|           |           |  readPartitionSnapshot
     * |<----------|           |           |  ConnectionUnavailableException, no request
     * }</pre>
     *
     * <p>The second repair, which rebuilds p although the first one already committed it:
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)"
     * |           |---------------------->|  the server renews the session
     * |           |<----------------------|
     * |<----------|           |           |  the proxy runs the repair
     * |           |           |           |
     * |---------------------------------->|  destroy, create, write, commit
     * |<----------------------------------|
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  returns "saved", so the repair returns true
     * |<----------|           |           |  recover returns true
     * |           |           |           |
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  returns "saved"
     * }</pre>
     */
    @Test
    public void repairRestartsAfterALostCommitResponse() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        network.on(COMMIT).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("p"));

        Predicate<Node> repair = node -> {
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
}
