package prototest.unreliablenetwork;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import prototest.example.api.errors.service.PartitionNotFoundException;
import prototest.example.server.NodeServer;
import prototest.example.server.NodeTestEndpoint;
import prototest.example.server.NodeTestNetwork;
import prototest.example.server.TestEndpoints;
import prototest.remote.api.RequestId;
import prototest.remote.api.errors.NodeRecoveryRequiredException;
import prototest.remote.network.FakeNetworkInterface;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static prototest.example.server.NodeTestEndpoint.proxyFor;
import static org.junit.Assert.*;
import static prototest.remote.network.FakeNetworkInterface.any;

/**
 * Checks that the proxy retries lost requests and lost responses with the same
 * request ID, without duplicating their effects, and when it stops retrying.
 *
 * <p>Uses the real proxy and the real server adapter behind the fake network,
 * with explicit delivery rules.
 *
 * <p>The Javadoc of each test shows its scenario as a sequence diagram. {@code test}
 * is the test thread; {@code caller} is a thread that waits for a call while the
 * test thread controls the network. An arrow from the test or the caller to the
 * server is a node call that goes through the proxy and the network; an arrow that
 * starts from the proxy is a retry. On the network, {@code *} marks a message that
 * the network drops or holds, or a held message that the test drops; on the server,
 * it marks a change that the test makes directly. The notes on the right describe
 * each step.
 */
public class RetryTest {
    @Rule
    public final TestEndpoints endpoints = new TestEndpoints();

    private static final String PARTITION_NAME = "example";
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> CREATE = n -> n.createPartition(any(), any());
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> EXISTS = n -> n.partitionExists(any(), any());
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> WRITE = n -> n.writeToPartition(any(), any(), any());
    private static final FakeNetworkInterface.Selector<NodeTestNetwork> COMMIT = n -> n.commitPartition(any(), any());

    private NodeServer server;
    private FakeNetworkInterface<NodeTestNetwork> network;

    @Before
    public void before() {
        server = new NodeServer();
        network = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoints.track(new NodeTestEndpoint(server, 60000)));
    }

    /**
     * The first request is lost before the server. The proxy sends it again with
     * the same request ID and the partition is created.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------------------->*           |  createPartition, the network drops
     * |           |           |           |  the request
     * |           |---------------------->|  retry with the same request ID,
     * |           |           |           |  the server creates the partition
     * |<----------------------------------|
     * }</pre>
     */
    @Test
    public void retriesOnRequestMissing() throws Exception {
        network.on(CREATE).onFirstCall().dropRequest();
        var proxy = proxyFor(network.build(), 60000);
        proxy.connect();

        proxy.createPartition(PARTITION_NAME);

        assertTrue(server.partitionExists(PARTITION_NAME));
        var firstAttempt = network.awaitAttempt(CREATE, 1);
        var retryAttempt = network.awaitAttempt(CREATE, 2);
        var firstRequestId = (RequestId) firstAttempt.invocation().args()[0];
        var retryRequestId = (RequestId) retryAttempt.invocation().args()[0];
        assertEquals(firstRequestId, retryRequestId);
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * The responses of the first write and of the first commit are lost after the
     * server has applied them. The retries return the saved results: the content is
     * written only once.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |           |           |           *  the test creates the partition
     * |           |           |           |
     * |---------------------------------->|  writeToPartition, the server writes
     * |           |           *<----------|  the network drops the response
     * |           |---------------------->|  retry with the same request ID
     * |<----------------------------------|  the saved result, no second write
     * |           |           |           |
     * |---------------------------------->|  commitPartition, the server commits
     * |           |           *<----------|  the network drops the response
     * |           |---------------------->|  retry with the same request ID
     * |<----------------------------------|  the saved result, no second commit
     * |           |           |           |
     * |---------------------------------->|  readPartitionSnapshot
     * |<----------------------------------|  returns "content"
     * }</pre>
     */
    @Test
    public void retriesWithoutDuplicatingEffects() {
        server.createPartition(PARTITION_NAME);
        network.on(WRITE).onFirstCall().dropResponse()
                .on(COMMIT).onFirstCall().dropResponse();
        var proxy = proxyFor(network.build(), 60000);
        proxy.connect();

        proxy.writeToPartition(PARTITION_NAME, "content");
        proxy.commitPartition(PARTITION_NAME);

        assertEquals("content", proxy.readPartitionSnapshot(PARTITION_NAME));
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * The first request is lost and the response of the second attempt is lost too.
     * The third attempt succeeds and leaves no error state in the proxy.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------------------->*           |  createPartition, the network drops
     * |           |           |           |  the request
     * |           |---------------------->|  retry, the server creates the partition
     * |           |           *<----------|  the network drops the response
     * |           |---------------------->|  second retry with the same request ID
     * |<----------------------------------|  the saved result
     * |           |           |           |
     * |---------->|           |           |  recoveryRequired
     * |<----------|           |           |  returns false
     * |---------------------------------->|  partitionExists
     * |<----------------------------------|  returns true
     * }</pre>
     */
    @Test
    public void aSuccessfulRetryLeavesTheProxyUsable() {
        network.on(CREATE).onFirstCall().dropRequest()
                .on(CREATE).onCall(2).dropResponse();
        var proxy = proxyFor(network.build(), 60000);
        proxy.connect();

        proxy.createPartition(PARTITION_NAME);

        assertFalse(proxy.recoveryRequired());
        assertTrue(proxy.partitionExists(PARTITION_NAME));
    }

    /**
     * A write to a missing partition returns the service exception to the caller
     * after one attempt. The proxy stays usable.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------------------------------->|  writeToPartition
     * |<----------------------------------|  PartitionNotFoundException, no retry
     * |           |           |           |
     * |---------->|           |           |  recoveryRequired
     * |<----------|           |           |  returns false
     * }</pre>
     */
    @Test
    public void returnsAServiceExceptionWithoutRetrying() {
        network.on(WRITE).onFirstCall().deliver();
        var proxy = proxyFor(network.build(), 60000);
        proxy.connect();

        assertThrows(PartitionNotFoundException.class,
                () -> proxy.writeToPartition(PARTITION_NAME, "content"));

        assertFalse(proxy.recoveryRequired());
        // The call has ended, so check the recorded attempts without waiting.
        assertThrows(IllegalStateException.class, () -> network.awaitAttempt(WRITE, 2, Duration.ZERO));
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * With a zero retry timeout, the first lost request is already a final error:
     * the proxy does not retry, reports {@code "Unrecoverable timeout"} and requires
     * recovery. The server never received the request.
     *
     * <pre>{@code
     * test        proxy       network     server
     * |           |           |           |
     * |---------------------->*           |  createPartition, the network drops
     * |           |           |           |  the request
     * |<----------|           |           |  NodeRecoveryRequiredException,
     * |           |           |           |  "Unrecoverable timeout", no retry
     * |           |           |           |
     * |---------->|           |           |  recoveryRequired
     * |<----------|           |           |  returns true
     * }</pre>
     */
    @Test
    public void requiresNodeRecoveryAfterRetryTimeout() {
        network.on(CREATE).onFirstCall().dropRequest();
        var proxy = proxyFor(network.build(), 0);
        proxy.connect();

        var error = assertThrows(NodeRecoveryRequiredException.class,
                () -> proxy.createPartition(PARTITION_NAME));

        assertEquals("Unrecoverable timeout", error.getMessage());
        assertTrue(proxy.recoveryRequired());
        assertFalse(server.partitionExists(PARTITION_NAME));
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * Two proxies send their first write to the same endpoint, each on its own
     * partition. Both responses are lost. The endpoint applies each write once
     * even though the two requests have the same sequence number.
     * {@code first} and {@code second} are the two proxies, each with its own fake
     * network; {@code S1} and {@code S2} are their session tokens.
     *
     * <pre>{@code
     * test      first     second    network   server
     * |         |         |         |         |
     * |         |         |         |         *  the test creates "first" and "second"
     * |         |         |         |         |
     * |-------->|         |         |         |  writeToPartition "first"
     * |         |---------------------------->|  request "(S1, 0)", the server writes "A"
     * |         |         |         *<--------|  the network drops the response
     * |         |---------------------------->|  retry with the same request ID
     * |         |<----------------------------|  the saved result
     * |<--------|         |         |         |
     * |         |         |         |         |
     * |------------------>|         |         |  writeToPartition "second"
     * |         |         |------------------>|  request "(S2, 0)", the server writes "B"
     * |         |         |         *<--------|  the network drops the response
     * |         |         |------------------>|  retry with the same request ID
     * |         |         |<------------------|  the saved result
     * |<------------------|         |         |
     * |         |         |         |         |
     * |         |         |         |         *  the test commits and reads "A" and "B"
     * }</pre>
     */
    @Test
    public void retriesFromDifferentProxiesWithTheSameSequenceApplyEachWriteOnce() throws Exception {
        var endpoint = endpoints.track(new NodeTestEndpoint(server, 60000));
        var firstNetwork = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint)
                .on(WRITE).onFirstCall().dropResponse();
        var secondNetwork = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint)
                .on(WRITE).onFirstCall().dropResponse();
        var first = proxyFor(firstNetwork.build(), 60000);
        first.connect();
        var second = proxyFor(secondNetwork.build(), 60000);
        second.connect();
        server.createPartition("first");
        server.createPartition("second");

        first.writeToPartition("first", "A");
        second.writeToPartition("second", "B");

        var firstAttempt = firstNetwork.awaitAttempt(WRITE, 1);
        var secondAttempt = secondNetwork.awaitAttempt(WRITE, 1);
        var firstId = (RequestId) firstAttempt.invocation().args()[0];
        var secondId = (RequestId) secondAttempt.invocation().args()[0];
        // Each proxy starts its own increasing sequence at zero. If ID generation
        // changes, revisit this setup so the two requests still share a sequence.
        assertEquals(firstId.sequence(), secondId.sequence());
        firstNetwork.assertAllRulesUsedAndCallsFinished();
        secondNetwork.assertAllRulesUsedAndCallsFinished();

        server.commitPartition("first");
        server.commitPartition("second");
        assertEquals("A", server.readPartitionSnapshot("first"));
        assertEquals("B", server.readPartitionSnapshot("second"));
    }

    /**
     * The network holds the first response so the partition can be removed before
     * the response is dropped. The retry uses the same request ID and receives
     * the saved result. A new call sees the removal.
     *
     * <pre>{@code
     * caller    test      proxy     network   server
     * |         |         |         |         |
     * |         |         |         |         *  the test creates the partition
     * |         |         |         |         |
     * |-------------------------------------->|  partitionExists
     * |         |         |         *<--------|  the network holds the response
     * |         |         |         |         *  the test removes the partition
     * |         |         |         *         |  the test drops the held response
     * |         |         |------------------>|  retry with the same request ID
     * |<--------------------------------------|  the saved result, true
     * |         |         |         |         |
     * |         |---------------------------->|  partitionExists, new request ID
     * |         |<----------------------------|  returns false
     * }</pre>
     */
    @Test
    public void aRetryReturnsTheOriginalResultAfterThePartitionIsRemoved() throws Exception {
        server.createPartition(PARTITION_NAME);
        network.on(EXISTS).onFirstCall().holdResponse();
        var proxy = proxyFor(network.build(), 60000);
        proxy.connect();
        var callers = Executors.newSingleThreadExecutor();
        try {
            var result = callers.submit(() -> proxy.partitionExists(PARTITION_NAME));
            var firstAttempt = network.awaitAttempt(EXISTS, 1, WAIT);
            firstAttempt.response().awaitArrival(WAIT);

            server.destroyPartition(PARTITION_NAME);
            firstAttempt.response().drop();

            assertTrue(result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            var retryAttempt = network.awaitAttempt(EXISTS, 2, WAIT);
            assertEquals(firstAttempt.invocation().args()[0], retryAttempt.invocation().args()[0]);
            assertFalse(proxy.partitionExists(PARTITION_NAME));
            network.assertAllRulesUsedAndCallsFinished();
        } finally {
            network.releaseHeldMessages();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    /**
     * The network holds the write response while the server's clock advances
     * past the retention time. A fresh request clears the saved result. When the
     * held response is dropped, the server rejects the retry as expired, the
     * proxy requires recovery, and the write remains applied once.
     *
     * <pre>{@code
     * caller    test      proxy     network   server
     * |         |         |         |         |
     * |         |         |         |         *  the test creates the partition
     * |         |         |         |         |
     * |-------------------------------------->|  writeToPartition, the server writes
     * |         |         |         *<--------|  the network holds the response
     * |         |         |         |         *  the clock passes the retention time
     * |         |---------------------------->|  partitionExists, the server removes
     * |         |         |         |         |  the expired results
     * |         |<----------------------------|  returns true
     * |         |         |         *         |  the test drops the held response
     * |         |         |------------------>|  retry with the same request ID
     * |         |         |<------------------|  the server rejects it as expired
     * |<------------------|         |         |  NodeRecoveryRequiredException,
     * |         |         |         |         |  "Request ... expired"
     * |         |         |         |         |
     * |         |-------->|         |         |  recoveryRequired
     * |         |<--------|         |         |  returns true
     * |         |         |         |         *  the write is applied once
     * }</pre>
     */
    @Test
    public void aRetryOfAnExpiredRequestRequiresRecovery() throws Exception {
        long retentionMillis = 100;
        var now = new AtomicLong();
        InstantSource time = () -> Instant.ofEpochMilli(now.get());
        var endpoint = endpoints.track(
                new NodeTestEndpoint(server, retentionMillis, time.withZone(ZoneOffset.UTC)));
        var expiringNetwork = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint).on(WRITE).onFirstCall().holdResponse();
        var proxy = proxyFor(expiringNetwork.build(), 60000);
        proxy.connect();
        server.createPartition(PARTITION_NAME);

        var callers = Executors.newSingleThreadExecutor();
        try {
            var result = callers.submit(() -> proxy.writeToPartition(PARTITION_NAME, "content"));
            var firstAttempt = expiringNetwork.awaitAttempt(WRITE, 1, WAIT);
            firstAttempt.response().awaitArrival(WAIT);

            now.set(retentionMillis + 1);
            // The register removes expired results when it processes a new request.
            assertTrue(proxy.partitionExists(PARTITION_NAME));
            firstAttempt.response().drop();

            var failure = assertThrows(ExecutionException.class,
                    () -> result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).getCause();
            assertEquals(NodeRecoveryRequiredException.class, failure.getClass());
            assertEquals("Request %s expired".formatted(firstAttempt.invocation().args()[0]), failure.getMessage());
            assertTrue(proxy.recoveryRequired());
            server.commitPartition(PARTITION_NAME);
            assertEquals("content", server.readPartitionSnapshot(PARTITION_NAME));
            expiringNetwork.assertAllRulesUsedAndCallsFinished();
        } finally {
            expiringNetwork.releaseHeldMessages();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        }
    }
}
