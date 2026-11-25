package prototest.unreliablenetwork;

import org.junit.Rule;
import org.junit.Test;

import prototest.example.server.NodeServer;
import prototest.example.server.NodeTestEndpoint;
import prototest.example.server.NodeTestNetwork;
import prototest.example.server.TestEndpoints;
import prototest.remote.api.RequestId;
import prototest.remote.api.errors.ConnectionUnavailableException;
import prototest.remote.api.errors.NodeRecoveryRequiredException;
import prototest.remote.network.FakeNetworkInterface;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static prototest.example.server.NodeTestEndpoint.proxyFor;
import static org.junit.Assert.*;
import static prototest.remote.network.FakeNetworkInterface.any;
import static prototest.remote.network.FakeNetworkInterface.eq;

/**
 * Checks what happens when a held response from an old session is delivered
 * or dropped after the proxy has recovered.
 *
 * <p>In the diagrams, {@code test} is the test thread. Node calls go through the
 * proxy and the network. On the network, {@code *} marks a held or dropped message.
 */
public class LateResponseTest {
    @Rule
    public final TestEndpoints endpoints = new TestEndpoints();

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> CREATE = n -> n.createPartition(any(), any());

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
     * Checks that a held response from the old session leaves the proxy usable
     * and the old call finished, with a single attempt.
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
     * <p>Setting up the recovered proxy while the old response is held.
     * {@code worker} is the thread that makes the old call.
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
     * }</pre>
     *
     * <p>Delivering or dropping the held response after recovery.
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |           |<----------|           |  the network delivers the old response
     * |           |           *           |  or drops it
     * |           *           |           |  the old call stays finished
     * |---------------------------------->|  partitionExists "new"
     * |<----------------------------------|  returns true
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
