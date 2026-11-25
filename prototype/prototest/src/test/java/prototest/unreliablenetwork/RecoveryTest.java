package prototest.unreliablenetwork;

import org.junit.Rule;
import org.junit.Test;

import prototest.example.api.Node;
import prototest.example.server.NodeServer;
import prototest.example.server.NodeTestEndpoint;
import prototest.example.server.NodeTestNetwork;
import prototest.example.server.TestEndpoints;
import prototest.remote.api.RequestId;
import prototest.remote.api.errors.ConnectionUnavailableException;
import prototest.remote.api.errors.NodeRecoveryRequiredException;
import prototest.remote.network.FakeNetworkInterface;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static prototest.example.server.NodeTestEndpoint.proxyFor;
import static org.junit.Assert.*;
import static prototest.remote.network.FakeNetworkInterface.any;
import static prototest.remote.network.FakeNetworkInterface.eq;

/**
 * Checks when lost or held messages require the recovery of the proxy, and
 * that a response arriving after the recovery does not change its state.
 *
 * <p>Uses the real proxy and the real server adapter behind the fake network.
 * The timeout cases use a zero retry timeout and an explicit outcome, so they
 * do not depend on machine speed.
 *
 * <p>The Javadoc of each test shows its scenario as a sequence diagram. {@code test}
 * is the test thread, which also runs the repair callback. An arrow from the test to
 * the server is a node call that goes through the proxy and the network. On the
 * network, {@code *} marks a message that the network drops or holds; on another
 * line it marks an event. The notes on the right describe each step.
 */
public class RecoveryTest {
    @Rule
    public final TestEndpoints endpoints = new TestEndpoints();

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> CREATE = n -> n.createPartition(any(), any());
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> COMMIT = n -> n.commitPartition(any(), any());

    /**
     * The server creates the partition, but the response is lost and the retry
     * timeout is zero. The outcome is uncertain for the proxy, so it requires
     * recovery: every following operation is rejected before reaching the network,
     * with the first error as its cause. The server keeps the effects already
     * applied.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------------------------------->|  createPartition, the server creates it
     * |           |           *<----------|  the network drops the response
     * |<----------|           |           |  NodeRecoveryRequiredException,
     * |           |           |           |  "Unrecoverable timeout"
     * |           |           |           |
     * |---------->|           |           |  each node operation
     * |<----------|           |           |  ConnectionUnavailableException, no request,
     * |           |           |           |  caused by the first error
     * }</pre>
     */
    @Test
    public void aLostResponseRequiresRecoveryAndBlocksAllNodeOperations() {
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
     * and makes recovery necessary. The commit remains visible, and the late
     * response leaves the recovered proxy usable.
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
     * |         |         |         |         |
     * |         |-------->|         |         |  "recover()"
     * |         |         |------------------>|  the server renews the session
     * |         |         |<------------------|
     * |         |<--------|         |         |
     * |         |         |<--------|         |  the network delivers the response
     * |         |---------------------------->|  readPartitionSnapshot through proxy
     * |         |<----------------------------|  returns "saved"
     * }</pre>
     */
    @Test
    public void interruptionWhileWaitingForCommitRequiresRecovery() throws Exception {
        var server = new NodeServer();
        var transport = new AtomicReference<Thread>();
        var endpoint = endpoints.track(new NodeTestEndpoint(server, 60000) {
            @Override
            public void commitPartition(RequestId requestId, String name) {
                transport.set(Thread.currentThread());
                super.commitPartition(requestId, name);
            }
        });
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

            proxy.recover();
            commit.response().deliver();
            commit.awaitCompletion(WAIT);
            // Wait for the proxy worker to process the response before checking the proxy.
            assertTrue(transport.get().join(WAIT));
            assertFalse(proxy.recoveryRequired());
            assertEquals("saved", proxy.readPartitionSnapshot("p"));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            caller.interrupt();
            network.releaseHeldMessages();
            assertTrue(caller.join(WAIT));
        }
    }

    /**
     * After a lost response, {@code recover()} accepts new calls again. The new
     * calls use new request IDs, so the server does not answer them with the saved
     * results of old calls. A second {@code recover()} on a usable proxy changes
     * nothing.
     *
     * <p>A request ID is shown as the session token and the sequence number of the call.
     * The test checks both parts on the first call of each session.
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  connect
     * |           |---------------------->|  the server opens session A
     * |           |<----------------------|
     * |<----------|           |           |
     * |           |           |           |
     * |---------------------------------->|  createPartition, request "(A, 0)"
     * |           |           *<----------|  the network drops the response
     * |<----------|           |           |  NodeRecoveryRequiredException
     * |           |           |           |
     * |---------->|           |           |  "recover()"
     * |           |---------------------->|  the server revokes A and opens B
     * |           |<----------------------|
     * |<----------|           |           |  the proxy accepts calls again
     * |           |           |           |
     * |---------------------------------->|  destroyPartition, request "(B, 0)"
     * |<----------------------------------|
     * |---------------------------------->|  createPartition, request "(B, 1)"
     * |<----------------------------------|
     * |---------------------------------->|  partitionExists, request "(B, 2)"
     * |<----------------------------------|  returns true
     * |           |           |           |
     * |---------->|           |           |  "recover()"
     * |<----------|           |           |  the proxy is healthy, no request
     * |---------------------------------->|  partitionExists, request "(B, 3)"
     * |<----------------------------------|  returns true
     * }</pre>
     */
    @Test
    public void recoveryAllowsNewCallsWithoutReusingRequestIds() throws InterruptedException {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();
        assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("present"));
        assertTrue(proxy.recoveryRequired());
        var failedAttempt = network.awaitAttempt(CREATE, 1, WAIT);
        var failedRequestId = (RequestId) failedAttempt.invocation().args()[0];

        proxy.recover();

        assertFalse(proxy.recoveryRequired());
        proxy.destroyPartition("present");
        var destroyAttempt = network.awaitAttempt(n -> n.destroyPartition(any(), eq("present")), 1, WAIT);
        var renewedRequestId = (RequestId) destroyAttempt.invocation().args()[0];
        assertNotEquals(failedRequestId.sessionToken(), renewedRequestId.sessionToken());
        assertEquals(0L, failedRequestId.sequence());
        assertEquals(0L, renewedRequestId.sequence());
        assertFalse(server.partitionExists("present"));
        proxy.createPartition("new");
        assertTrue(proxy.partitionExists("new"));
        proxy.recover();
        assertTrue(proxy.partitionExists("new"));
    }

    /**
     * A repair that finds an unexpected snapshot returns false. The proxy keeps
     * normal calls blocked until a later repair writes and confirms the expected
     * snapshot. Once healthy, the proxy skips the repair callback.
     *
     * <p>The first repair, which finds an unexpected snapshot:
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
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  not "saved", so the repair returns false
     * |<----------|           |           |  recover returns false
     * |           |           |           |
     * |---------->|           |           |  writeToPartition
     * |<----------|           |           |  ConnectionUnavailableException, no request
     * }</pre>
     *
     * <p>The second repair, and a recovery of the healthy proxy:
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)"
     * |           |---------------------->|  the server renews the session
     * |           |<----------------------|
     * |<----------|           |           |  the proxy runs the repair
     * |           |           |           |
     * |---------------------------------->|  write, commit
     * |<----------------------------------|
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  returns "saved", so the repair returns true
     * |<----------|           |           |  recover returns true
     * |           |           |           |
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  returns "saved"
     * |           |           |           |
     * |---------->|           |           |  "recover(repair)"
     * |<----------|           |           |  returns true, the repair does not run
     * }</pre>
     */
    @Test
    public void anIncompleteRepairKeepsTheProxyBlockedUntilTheClientConfirmsTheSnapshot() {
        var server = new NodeServer();
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
        network.on(CREATE).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 0);
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
     * <p>{@code worker} is the thread that makes the old call:
     * <pre>{@code
     * worker    test      proxy     network   server
     * |         |         |         |         |
     * |-------------------------------------->|  createPartition "old" succeeds
     * |         |         |         *<--------|  the network holds the response
     * |         |         |         |         |
     * |         |------------------>*         |  createPartition "fail", the network
     * |         |         |         |         |  drops the request
     * |         |<--------|         |         |  NodeRecoveryRequiredException
     * |<------------------|         |         |  ConnectionUnavailableException,
     * |         |         |         |         |  with the same cause
     * |         |         |         |         |
     * |         |-------->|         |         |  "recover()"
     * |         |         |------------------>|  the server renews the session
     * |         |         |<------------------|
     * |         |<--------|         |         |
     * |         |         |         |         |
     * |         |---------------------------->|  createPartition "new" succeeds
     * |         |<----------------------------|
     * |         |         |         |         |
     * |         |         |<--------|         |  the network delivers or drops the held
     * |         |         |         |         |  response; the old call does not retry
     * |         |         |         |         |
     * |         |---------------------------->|  partitionExists "new"
     * |         |<----------------------------|  returns true
     * }</pre>
     *
     * @param dropResponse whether the held response is lost instead of delivered
     */
    private void verifyHeldResponseAfterRecovery(boolean dropResponse) throws Exception {
        var server = new NodeServer();
        var transport = new AtomicReference<Thread>();
        var oldAttempts = new AtomicInteger();
        var endpoint = endpoints.track(new NodeTestEndpoint(server, 60000) {
            @Override
            public void createPartition(RequestId requestId, String name) {
                if (name.equals("old")) {
                    transport.set(Thread.currentThread());
                    oldAttempts.incrementAndGet();
                }
                super.createPartition(requestId, name);
            }
        });
        var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint)
                .on(n -> n.createPartition(any(), eq("old"))).onFirstCall().holdResponse()
                .on(n -> n.createPartition(any(), eq("fail"))).dropRequest();
        var proxy = proxyFor(network.build(), 0);
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
