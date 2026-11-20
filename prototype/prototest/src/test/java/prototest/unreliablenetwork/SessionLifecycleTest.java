package prototest.unreliablenetwork;

import org.junit.Test;
import prototest.api.NodeNetworkInterface;
import prototest.api.RequestId;
import prototest.api.errors.network.ConnectionUnavailableException;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.api.errors.network.RetryableCommunicationException;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

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

import static org.junit.Assert.*;
import static prototest.proxy.FakeNetworkInterface.any;
import static prototest.proxy.FakeNetworkInterface.eq;

public class SessionLifecycleTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector CONNECT = NodeNetworkInterface::connect;
    private static final FakeNetworkInterface.Selector RECOVER = node -> node.recover(any());
    private static final FakeNetworkInterface.Selector DISCONNECT = node -> node.disconnect(any());
    private static final FakeNetworkInterface.Selector OLD_CREATE = node -> node.createPartition(any(), eq("old"));

    @Test
    public void aFailedConnectionLeavesTheProxyDisconnected() throws Exception {
        verifyFailedTransition(CONNECT, NodeProxy::connect, false);
    }

    @Test
    public void aFailedRecoveryLeavesTheProxyInNeedOfRecovery() throws Exception {
        verifyFailedTransition(RECOVER, NodeProxy::recover, true);
    }

    private static void verifyFailedTransition(FakeNetworkInterface.Selector selector,
                                               Consumer<NodeProxy> transition, boolean recovering)
            throws Exception {
        try (var endpoint = new ServerNodeNetworkInterface(60000)) {
            var network = FakeNetworkInterface.wrap(endpoint).on(selector).onFirstCall().holdResponse();
            if (recovering) {
                network.on(node -> node.createPartition(any(), eq("fail"))).dropRequest();
            }
            var proxy = new NodeProxy(network.build(), 0);
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

    @Test
    public void disconnectionCancelsPendingCallsAndCanBeRetriedAfterALostConfirmation() throws Exception {
        var server = new NodeServer();
        try (var endpoint = new ServerNodeNetworkInterface(server, 60000)) {
            var network = FakeNetworkInterface.wrap(endpoint)
                    .on(OLD_CREATE).holdRequest()
                    .on(DISCONNECT).onFirstCall().holdResponse();
            var proxy = new NodeProxy(network.build(), 0);
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

    @Test
    public void recoveryRejectsARequestThatWasStillInTransit() throws Exception {
        var server = new NodeServer();
        try (var endpoint = new ServerNodeNetworkInterface(server, 60000)) {
            var network = FakeNetworkInterface.wrap(endpoint).on(OLD_CREATE).holdRequest()
                    .on(node -> node.createPartition(any(), eq("fail"))).dropRequest();
            var proxy = new NodeProxy(network.build(), 0);
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

    @Test
    public void anExpiredSessionRejectsARequestThatWasStillInTransit() throws Exception {
        var now = new AtomicLong();
        InstantSource time = () -> Instant.ofEpochMilli(now.get());
        var server = new NodeServer();
        long sessionTimeout = 1000;
        try (var endpoint = new ServerNodeNetworkInterface(server, 1000, sessionTimeout,
                time.withZone(ZoneOffset.UTC))) {
            verifyRequestRejectedAfterInvalidation(endpoint, () -> now.set(sessionTimeout), server);
        }
    }

    @Test
    public void aRestoredServerRejectsARequestThatWasStillInTransit() throws Exception {
        var original = new NodeServer();
        var restored = new NodeServer();
        try (var endpoint = new ServerNodeNetworkInterface(original, 60000)) {
            verifyRequestRejectedAfterInvalidation(endpoint, () -> endpoint.restore(restored), restored);
            assertFalse(original.partitionExists("old"));
            assertFalse(original.partitionExists("new"));
        }
    }

    private static void verifyRequestRejectedAfterInvalidation(ServerNodeNetworkInterface endpoint,
                                                                Runnable invalidate, NodeServer active)
            throws Exception {
        var network = FakeNetworkInterface.wrap(endpoint).on(OLD_CREATE).holdRequest();
        var node = network.build();
        var token = node.connect();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var pending = workers.submit(() -> node.createPartition(new RequestId(token, 0), "old"));
                var old = network.awaitAttempt(OLD_CREATE, 1, WAIT);
                old.request().awaitArrival(WAIT);
                invalidate.run();
                old.request().deliver();
                assertTrue(failureOf(pending) instanceof NodeRecoveryRequiredException);
                assertFalse(active.partitionExists("old"));

                var renewed = node.recover(token);
                assertNotEquals(token, renewed);
                node.createPartition(new RequestId(renewed, 0), "new");
                assertTrue(active.partitionExists("new"));
                network.assertAllRulesUsedAndCallsFinished();
            } finally {
                network.releaseHeldMessages();
            }
        }
    }

    private static void assertBlocked(NodeProxy proxy, FakeNetworkInterface network) {
        assertThrows(ConnectionUnavailableException.class, () -> proxy.createPartition("blocked"));
        assertThrows(IllegalStateException.class, () -> network.awaitAttempt(
                node -> node.createPartition(any(), eq("blocked")), 1, Duration.ZERO));
    }

    private static Throwable failureOf(Future<?> result) {
        return assertThrows(ExecutionException.class,
                () -> result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).getCause();
    }
}
