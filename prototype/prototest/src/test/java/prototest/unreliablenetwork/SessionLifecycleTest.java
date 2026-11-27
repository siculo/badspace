package prototest.unreliablenetwork;

import org.junit.Test;
import prototest.example.proxy.NodeProxy;
import prototest.example.server.NodeServer;
import prototest.example.server.NodeTestEndpoint;
import prototest.example.server.NodeTestNetwork;
import badspace.remote.api.RequestId;
import badspace.remote.api.errors.ConnectionUnavailableException;
import badspace.remote.api.errors.NodeRecoveryRequiredException;
import badspace.remote.api.errors.RetryableCommunicationException;
import badspace.remote.api.errors.SessionUnavailableException;
import badspace.remote.network.FakeNetworkInterface;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static prototest.example.server.NodeTestEndpoint.proxyFor;
import static org.junit.Assert.*;
import static badspace.remote.network.FakeNetworkInterface.any;
import static badspace.remote.network.FakeNetworkInterface.eq;

/**
 * Checks the session changes of the proxy when messages are lost or held:
 * connection, recovery and disconnection, and old requests that reach the server
 * after their session was revoked or has expired.
 *
 * <p>Uses the real proxy and the real server adapter, usually with the fake network
 * between them and a zero retry timeout. Session expiry uses a controlled clock, so
 * the tests do not depend on machine speed.
 *
 * <p>The Javadoc of each test shows its scenario as a sequence diagram. {@code test}
 * is the test thread; the other threads have their names in the test Javadoc. An
 * arrow from a thread to the server is a node call that goes through the proxy and
 * the network. On the network, {@code *} marks a message that the network drops or
 * holds, or a held message that the test drops; on the server, it marks a change of
 * the server clock. The notes on the right describe each step.
 */
public class SessionLifecycleTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> CONNECT = NodeTestNetwork::connect;
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> RECOVER = node -> node.recover(any());
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> DISCONNECT = node -> node.disconnect(any());
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> OLD_CREATE = node -> node.createPartition(any(), eq("old"));
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> FAIL_CREATE = node -> node.createPartition(any(), eq("fail"));
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> BLOCKED_CREATE = node -> node.createPartition(any(), eq("blocked"));

    /**
     * The session expires while the proxy is idle. The server rejects the next call
     * with {@link SessionUnavailableException} without running it, and the proxy does
     * not retry it. After {@code recover()} the same call works. The proxy uses the
     * endpoint directly, without the fake network.
     *
     * <pre>{@code
     * test        proxy       server
     * |           |           |
     * |---------->|           |  connect
     * |           |---------->|  the server opens a session
     * |           |<----------|
     * |<----------|           |
     * |           |           |
     * |           |           *  the clock reaches the session timeout
     * |---------------------->|  createPartition "p"
     * |<----------------------|  SessionUnavailableException,
     * |           |           |  no retry, "p" is not created
     * |           |           |
     * |---------->|           |  "recover()"
     * |           |---------->|  the server opens a new session
     * |           |<----------|
     * |<----------|           |
     * |           |           |
     * |---------------------->|  createPartition "p"
     * |<----------------------|
     * }</pre>
     */
    @Test
    public void anIdleProxyCanRenewAfterItsFirstAttemptIsRejected() {
        var now = new AtomicLong();
        InstantSource time = () -> Instant.ofEpochMilli(now.get());
        var server = new NodeServer();
        long sessionTimeout = 1000;
        try (var endpoint = new NodeTestEndpoint(server, 1000, sessionTimeout,
                time.withZone(ZoneOffset.UTC))) {
            var proxy = proxyFor(endpoint, 0);
            proxy.connect();
            now.set(sessionTimeout);
            assertThrows(SessionUnavailableException.class, () -> proxy.createPartition("p"));
            assertFalse(server.partitionExists("p"));
            proxy.recover();
            proxy.createPartition("p");
            assertTrue(proxy.partitionExists("p"));
        }
    }

    /**
     * After a lost response, {@code recover()} accepts new calls again. The new
     * calls use new request IDs, so the server does not answer them with the saved
     * results of old calls.
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
     * }</pre>
     */
    @Test
    public void recoveryAllowsNewCallsWithoutReusingRequestIds() throws InterruptedException {
        var server = new NodeServer();
        FakeNetworkInterface.Selector<NodeTestNetwork> create = node -> node.createPartition(any(), eq("present"));
        FakeNetworkInterface.Selector<NodeTestNetwork> destroy = node -> node.destroyPartition(any(), eq("present"));
        try (var endpoint = new NodeTestEndpoint(server, 60000)) {
            var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint)
                    .on(create).onFirstCall().dropResponse();
            var proxy = proxyFor(network.build(), 0);
            proxy.connect();
            assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("present"));
            assertTrue(proxy.recoveryRequired());
            var failedAttempt = network.awaitAttempt(create, 1, WAIT);
            var failedRequestId = (RequestId) failedAttempt.invocation().args()[0];

            proxy.recover();

            assertFalse(proxy.recoveryRequired());
            proxy.destroyPartition("present");
            var destroyAttempt = network.awaitAttempt(destroy, 1, WAIT);
            var renewedRequestId = (RequestId) destroyAttempt.invocation().args()[0];
            assertNotEquals(failedRequestId.sessionToken(), renewedRequestId.sessionToken());
            assertEquals(0L, failedRequestId.sequence());
            assertEquals(0L, renewedRequestId.sequence());
            assertFalse(server.partitionExists("present"));
        }
    }

    /**
     * The network drops the response of {@code connect()}. The connection fails
     * without a retry and the proxy stays disconnected: it rejects calls, and a new
     * {@code connect()} works. See {@link #verifyFailedTransition}.
     */
    @Test
    public void aFailedConnectionLeavesTheProxyDisconnected() throws Exception {
        verifyFailedTransition(CONNECT, NodeProxy::connect, false);
    }

    /**
     * Like {@link #aFailedConnectionLeavesTheProxyDisconnected()}, but the network
     * drops the response of {@code recover()}: after the failure the proxy still
     * requires recovery, and a new {@code recover()} works.
     */
    @Test
    public void aFailedRecoveryLeavesTheProxyInNeedOfRecovery() throws Exception {
        verifyFailedTransition(RECOVER, NodeProxy::recover, true);
    }

    /**
     * Runs the scenario of a connection change whose response is lost. Before the
     * change, while it waits for the response and after it fails, the proxy rejects
     * calls without sending them. A second change while the first one runs fails
     * with {@link IllegalStateException}. Then the same change is repeated and works.
     *
     * <p>For a recovery, the proxy first connects and loses a request, so that it
     * requires recovery:
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  connect
     * |           |---------------------->|  the server opens a session
     * |           |<----------------------|
     * |<----------|           |           |
     * |---------------------->*           |  createPartition "fail", the network
     * |           |           |           |  drops the request
     * |<----------|           |           |  NodeRecoveryRequiredException
     * }</pre>
     *
     * <p>The change itself, where {@code worker} is the thread that runs it:
     * <pre>{@code
     * worker    test      proxy     network   server
     * |         |         |         |         |
     * |------------------>|         |         |  connect or recover
     * |         |         |------------------>|  the server opens or renews a session
     * |         |         |         *<--------|  the network holds the response
     * |         |-------->|         |         |  createPartition "blocked"
     * |         |<--------|         |         |  ConnectionUnavailableException,
     * |         |         |         |         |  no request
     * |         |-------->|         |         |  the same change
     * |         |<--------|         |         |  IllegalStateException
     * |         |         |         |         |
     * |         |         |         *         |  the test drops the held response
     * |<------------------|         |         |  RetryableCommunicationException,
     * |         |         |         |         |  no retry
     * |         |-------->|         |         |  createPartition "blocked"
     * |         |<--------|         |         |  ConnectionUnavailableException,
     * |         |         |         |         |  no request
     * |         |         |         |         |
     * |         |-------->|         |         |  the same change
     * |         |         |------------------>|  the server opens or renews a session
     * |         |         |<------------------|
     * |         |<--------|         |         |
     * |         |---------------------------->|  createPartition "new"
     * |         |<----------------------------|
     * }</pre>
     *
     * @param selector the network call of the change
     * @param transition the change, {@code connect()} or {@code recover()}
     * @param recovering whether the proxy must require recovery before the change
     * @throws Exception if a thread of the test fails or is interrupted
     */
    private static void verifyFailedTransition(FakeNetworkInterface.Selector<NodeTestNetwork> selector,
                                               Consumer<NodeProxy> transition, boolean recovering)
            throws Exception {
        try (var endpoint = new NodeTestEndpoint(60000)) {
            var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint).on(selector).onFirstCall().holdResponse();
            if (recovering) {
                network.on(FAIL_CREATE).dropRequest();
            }
            var proxy = proxyFor(network.build(), 0);
            assertBlocked(proxy, network);
            if (recovering) {
                proxy.connect();
                assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("fail"));
                assertTrue(proxy.recoveryRequired());
            }
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    var result = workers.submit(() -> transition.accept(proxy));
                    var attempt = network.awaitAttempt(selector, 1, WAIT);
                    attempt.response().awaitArrival(WAIT);
                    assertBlocked(proxy, network);
                    assertThrows(IllegalStateException.class, () -> transition.accept(proxy));
                    attempt.response().drop();
                    assertTrue(failureOf(result) instanceof RetryableCommunicationException);

                    assertEquals(recovering, proxy.recoveryRequired());
                    assertBlocked(proxy, network);
                    transition.accept(proxy);
                    proxy.createPartition("new");
                    assertTrue(proxy.partitionExists("new"));
                    network.assertAllRulesUsedAndCallsFinished();
                } finally {
                    network.releaseHeldMessages();
                }
            }
        }
    }

    /**
     * The network holds the request of a call while another lost request makes
     * recovery necessary. After {@code recover()} the network delivers the old
     * request: the server rejects it because its session is revoked, and does not
     * create the partition. {@code caller} is the thread of the old call.
     *
     * <pre>{@code
     * caller    test      proxy     network   server
     * |         |         |         |         |
     * |---------------------------->*         |  createPartition "old", the network
     * |         |         |         |         |  holds the request
     * |         |------------------>*         |  createPartition "fail", the network
     * |         |         |         |         |  drops the request
     * |         |<--------|         |         |  NodeRecoveryRequiredException
     * |<------------------|         |         |  ConnectionUnavailableException
     * |         |         |         |         |
     * |         |-------->|         |         |  "recover()"
     * |         |         |------------------>|  the server revokes the old session
     * |         |         |         |         |  and opens a new one
     * |         |         |<------------------|
     * |         |<--------|         |         |
     * |         |---------------------------->|  createPartition "new"
     * |         |<----------------------------|
     * |         |         |         |         |
     * |         |         |         |-------->|  the network delivers the old request
     * |         |         |<------------------|  SessionUnavailableException,
     * |         |         |         |         |  "old" is not created
     * }</pre>
     */
    @Test
    public void recoveryRejectsARequestThatWasStillInTransit() throws Exception {
        var server = new NodeServer();
        try (var endpoint = new NodeTestEndpoint(server, 60000)) {
            var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint).on(OLD_CREATE).holdRequest()
                    .on(FAIL_CREATE).dropRequest();
            var proxy = proxyFor(network.build(), 0);
            proxy.connect();
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    var pending = workers.submit(() -> proxy.createPartition("old"));
                    var old = network.awaitAttempt(OLD_CREATE, 1, WAIT);
                    old.request().awaitArrival(WAIT);
                    assertThrows(NodeRecoveryRequiredException.class, () -> proxy.createPartition("fail"));
                    assertTrue(failureOf(pending) instanceof ConnectionUnavailableException);

                    proxy.recover();
                    proxy.createPartition("new");
                    old.request().deliver();
                    old.awaitCompletion(WAIT);
                    assertFalse(server.partitionExists("old"));
                    assertTrue(proxy.partitionExists("new"));
                    network.assertAllRulesUsedAndCallsFinished();
                } finally {
                    network.releaseHeldMessages();
                }
            }
        }
    }

    /**
     * The network holds a request while its session expires. When the request
     * arrives, the server rejects it and does not create the partition. Recovery
     * opens a session with a new token, and requests with that token work. The test
     * uses the network interface directly, without the proxy: {@code T} and
     * {@code R} are the tokens of the two sessions, and {@code caller} is the thread
     * of the old request.
     *
     * <pre>{@code
     * caller      test        network     server
     * |           |           |           |
     * |           |---------------------->|  connect, the server opens session T
     * |           |<----------------------|
     * |           |           |           |
     * |---------------------->*           |  createPartition "old", request "(T, 0)",
     * |           |           |           |  the network holds the request
     * |           |           |           *  the clock reaches the session timeout
     * |           |           |---------->|  the network delivers the request
     * |<----------------------------------|  SessionUnavailableException,
     * |           |           |           |  "old" is not created
     * |           |           |           |
     * |           |---------------------->|  "recover(T)", the server opens session R
     * |           |<----------------------|
     * |           |---------------------->|  createPartition "new", request "(R, 0)"
     * |           |<----------------------|
     * }</pre>
     */
    @Test
    public void anExpiredSessionRejectsARequestThatWasStillInTransit() throws Exception {
        var now = new AtomicLong();
        InstantSource time = () -> Instant.ofEpochMilli(now.get());
        var server = new NodeServer();
        long sessionTimeout = 1000;
        try (var endpoint = new NodeTestEndpoint(server, 1000, sessionTimeout,
                time.withZone(ZoneOffset.UTC))) {
            var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint).on(OLD_CREATE).holdRequest();
            var node = network.build();
            var token = node.connect();
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    var pending = workers.submit(() -> node.createPartition(new RequestId(token, 0), "old"));
                    var old = network.awaitAttempt(OLD_CREATE, 1, WAIT);
                    old.request().awaitArrival(WAIT);
                    now.set(sessionTimeout);
                    old.request().deliver();
                    assertTrue(failureOf(pending) instanceof SessionUnavailableException);
                    assertFalse(server.partitionExists("old"));

                    var renewed = node.recover(token);
                    assertNotEquals(token, renewed);
                    node.createPartition(new RequestId(renewed, 0), "new");
                    assertTrue(server.partitionExists("new"));
                    network.assertAllRulesUsedAndCallsFinished();
                } finally {
                    network.releaseHeldMessages();
                }
            }
        }
    }

    /**
     * The disconnection cancels a call whose request the network holds, then loses
     * its confirmation. Until a disconnection is confirmed, the proxy rejects calls
     * and {@code connect()} fails. A second disconnection sends the same token again
     * and works, and then the proxy can connect again. When the network finally
     * delivers the old request, the server rejects it. {@code caller} is the thread
     * of the old call, {@code closer} the thread of the first disconnection.
     *
     * <p>The first disconnection, whose confirmation is lost:
     * <pre>{@code
     * caller   closer   test     proxy    network  server
     * |        |        |        |        |        |
     * |---------------------------------->*        |  createPartition "old", the network
     * |        |        |        |        |        |  holds the request
     * |        |---------------->|        |        |  disconnect
     * |<-------------------------|        |        |  ConnectionUnavailableException
     * |        |        |        |---------------->|  the server revokes the session
     * |        |        |        |        *<-------|  the network holds the response
     * |        |        |------->|        |        |  createPartition "blocked"
     * |        |        |<-------|        |        |  ConnectionUnavailableException,
     * |        |        |        |        |        |  no request
     * |        |        |------->|        |        |  connect
     * |        |        |<-------|        |        |  IllegalStateException
     * |        |        |        |        |        |
     * |        |        |        |        *        |  the test drops the held response
     * |        |<----------------|        |        |  RetryableCommunicationException
     * |        |        |------->|        |        |  createPartition "blocked"
     * |        |        |<-------|        |        |  ConnectionUnavailableException,
     * |        |        |        |        |        |  no request
     * |        |        |------->|        |        |  connect
     * |        |        |<-------|        |        |  IllegalStateException,
     * |        |        |        |        |        |  disconnection not confirmed
     * }</pre>
     *
     * <p>The second disconnection and the old request:
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------->|           |           |  disconnect
     * |           |---------------------->|  the same token again
     * |           |<----------------------|
     * |<----------|           |           |
     * |---------->|           |           |  createPartition "blocked"
     * |<----------|           |           |  ConnectionUnavailableException, no session
     * |           |           |           |
     * |---------->|           |           |  connect
     * |           |---------------------->|  the server opens a new session
     * |           |<----------------------|
     * |<----------|           |           |
     * |---------------------------------->|  createPartition "new"
     * |<----------------------------------|
     * |           |           |           |
     * |           |           |---------->|  the network delivers the old request
     * |           |<----------------------|  SessionUnavailableException,
     * |           |           |           |  "old" is not created
     * }</pre>
     */
    @Test
    public void disconnectionCancelsPendingCallsAndCanBeRetriedAfterALostConfirmation() throws Exception {
        var server = new NodeServer();
        try (var endpoint = new NodeTestEndpoint(server, 60000)) {
            var network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint)
                    .on(OLD_CREATE).holdRequest()
                    .on(DISCONNECT).onFirstCall().holdResponse();
            var proxy = proxyFor(network.build(), 0);
            proxy.connect();
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    var pending = workers.submit(() -> proxy.createPartition("old"));
                    var old = network.awaitAttempt(OLD_CREATE, 1, WAIT);
                    old.request().awaitArrival(WAIT);
                    var disconnecting = workers.submit(proxy::disconnect);
                    var disconnect = network.awaitAttempt(DISCONNECT, 1, WAIT);
                    disconnect.response().awaitArrival(WAIT);

                    assertTrue(failureOf(pending) instanceof ConnectionUnavailableException);
                    assertBlocked(proxy, network);
                    assertThrows(IllegalStateException.class, proxy::connect);
                    disconnect.response().drop();
                    assertTrue(failureOf(disconnecting) instanceof RetryableCommunicationException);
                    assertBlocked(proxy, network);
                    assertThrows(IllegalStateException.class, proxy::connect);
                    proxy.disconnect();
                    assertBlocked(proxy, network);
                    proxy.connect();
                    proxy.createPartition("new");

                    old.request().deliver();
                    old.awaitCompletion(WAIT);
                    assertFalse(server.partitionExists("old"));
                    assertTrue(proxy.partitionExists("new"));
                    assertEquals(disconnect.invocation().args()[0],
                            network.awaitAttempt(DISCONNECT, 2, WAIT).invocation().args()[0]);
                    network.assertAllRulesUsedAndCallsFinished();
                } finally {
                    network.releaseHeldMessages();
                }
            }
        }
    }

    private static void assertBlocked(NodeProxy proxy, FakeNetworkInterface<NodeTestNetwork> network) {
        assertThrows(ConnectionUnavailableException.class, () -> proxy.createPartition("blocked"));
        assertThrows(IllegalStateException.class, () -> network.awaitAttempt(BLOCKED_CREATE, 1, Duration.ZERO));
    }

    private static Throwable failureOf(Future<?> result) {
        return assertThrows(ExecutionException.class,
                () -> result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).getCause();
    }
}
