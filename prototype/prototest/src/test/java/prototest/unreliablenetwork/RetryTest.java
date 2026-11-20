package prototest.unreliablenetwork;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.api.errors.service.PartitionNotFoundException;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;
import prototest.server.TestEndpoints;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static prototest.proxy.FakeNetworkInterface.any;

/**
 * Checks that the proxy retries lost requests and lost responses with the same
 * request ID, without duplicating their effects, and when it stops retrying.
 *
 * <p>Uses the real proxy and the real server adapter behind the fake network,
 * with explicit delivery rules.
 */
public class RetryTest {
    @Rule
    public final TestEndpoints endpoints = new TestEndpoints();

    private static final String PARTITION_NAME = "example";
    private static final Duration WAIT = Duration.ofSeconds(5);

    private NodeServer server;
    private FakeNetworkInterface network;

    @Before
    public void before() {
        server = new NodeServer();
        network = FakeNetworkInterface.wrap(endpoints.track(new ServerNodeNetworkInterface(server, 60000)));
    }

    /**
     * The first request is lost before the server. The proxy sends it again with
     * the same request ID and the partition is created.
     */
    @Test
    public void retriesOnRequestMissing() throws Exception {
        network.on(n -> n.createPartition(any(), any())).onFirstCall().dropRequest();
        var proxy = new NodeProxy(network.build(), 60000);
        proxy.connect();

        proxy.createPartition(PARTITION_NAME);

        assertTrue(server.partitionExists(PARTITION_NAME));
        var firstAttempt = network.awaitAttempt(n -> n.createPartition(any(), any()), 1);
        var retryAttempt = network.awaitAttempt(n -> n.createPartition(any(), any()), 2);
        var firstRequestId = (RequestId) firstAttempt.invocation().args()[0];
        var retryRequestId = (RequestId) retryAttempt.invocation().args()[0];
        assertEquals(firstRequestId, retryRequestId);
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * The network holds the first response so the partition can be removed before
     * the response is dropped. The retry uses the same request ID and receives
     * the saved result. A new call sees the removal.
     */
    @Test
    public void aRetryReturnsTheOriginalResultAfterThePartitionIsRemoved() throws Exception {
        server.createPartition(PARTITION_NAME);
        FakeNetworkInterface.Selector partitionExists = n -> n.partitionExists(any(), any());
        network.on(partitionExists).onFirstCall().holdResponse();
        var proxy = new NodeProxy(network.build(), 60000);
        proxy.connect();
        var callers = Executors.newSingleThreadExecutor();
        try {
            var result = callers.submit(() -> proxy.partitionExists(PARTITION_NAME));
            var firstAttempt = network.awaitAttempt(partitionExists, 1, WAIT);
            firstAttempt.response().awaitArrival(WAIT);

            server.destroyPartition(PARTITION_NAME);
            firstAttempt.response().drop();

            assertTrue(result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            var retryAttempt = network.awaitAttempt(partitionExists, 2, WAIT);
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
     */
    @Test
    public void aRetryOfAnExpiredRequestRequiresRecovery() throws Exception {
        long retentionMillis = 100;
        var now = new AtomicLong();
        InstantSource time = () -> Instant.ofEpochMilli(now.get());
        var endpoint = endpoints.track(
                new ServerNodeNetworkInterface(server, retentionMillis, time.withZone(ZoneOffset.UTC)));
        FakeNetworkInterface.Selector write = n -> n.writeToPartition(any(), any(), any());
        var expiringNetwork = FakeNetworkInterface.wrap(endpoint).on(write).onFirstCall().holdResponse();
        var proxy = new NodeProxy(expiringNetwork.build(), 60000);
        proxy.connect();
        server.createPartition(PARTITION_NAME);

        var callers = Executors.newSingleThreadExecutor();
        try {
            var result = callers.submit(() -> proxy.writeToPartition(PARTITION_NAME, "content"));
            var firstAttempt = expiringNetwork.awaitAttempt(write, 1, WAIT);
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

    /**
     * The responses of the first write and of the first commit are lost after the
     * server has applied them. The retries return the saved results: the content is
     * written only once.
     */
    @Test
    public void retriesWithoutDuplicatingEffects() {
        server.createPartition(PARTITION_NAME);
        network.on(n -> n.writeToPartition(any(), any(), any())).onFirstCall().dropResponse()
                .on(n -> n.commitPartition(any(), any())).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 60000);
        proxy.connect();

        proxy.writeToPartition(PARTITION_NAME, "content");
        proxy.commitPartition(PARTITION_NAME);

        assertEquals("content", proxy.readPartitionSnapshot(PARTITION_NAME));
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * With a zero retry timeout, the first lost request is already a final error:
     * the proxy does not retry, reports {@code "Unrecoverable timeout"} and requires
     * recovery. The server never received the request.
     */
    @Test
    public void requiresNodeRecoveryAfterRetryTimeout() {
        network.on(n -> n.createPartition(any(), any())).onFirstCall().dropRequest();
        var proxy = new NodeProxy(network.build(), 0);
        proxy.connect();

        var error = assertThrows(NodeRecoveryRequiredException.class,
                () -> proxy.createPartition(PARTITION_NAME));

        assertEquals("Unrecoverable timeout", error.getMessage());
        assertTrue(proxy.recoveryRequired());
        assertFalse(server.partitionExists(PARTITION_NAME));
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * A write to a missing partition returns the service exception to the caller
     * after one attempt. The proxy stays usable.
     */
    @Test
    public void returnsAServiceExceptionWithoutRetrying() {
        FakeNetworkInterface.Selector write = n -> n.writeToPartition(any(), any(), any());
        network.on(write).onFirstCall().deliver();
        var proxy = new NodeProxy(network.build(), 60000);
        proxy.connect();

        assertThrows(PartitionNotFoundException.class,
                () -> proxy.writeToPartition(PARTITION_NAME, "content"));

        assertFalse(proxy.recoveryRequired());
        // The call has ended, so check the recorded attempts without waiting.
        assertThrows(IllegalStateException.class, () -> network.awaitAttempt(write, 2, Duration.ZERO));
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * The first request is lost and the response of the second attempt is lost too.
     * The third attempt succeeds and leaves no error state in the proxy.
     */
    @Test
    public void aSuccessfulRetryLeavesTheProxyUsable() {
        network.on(n -> n.createPartition(any(), any())).onFirstCall().dropRequest()
                .on(n -> n.createPartition(any(), any())).onCall(2).dropResponse();
        var proxy = new NodeProxy(network.build(), 60000);
        proxy.connect();

        proxy.createPartition(PARTITION_NAME);

        assertFalse(proxy.recoveryRequired());
        assertTrue(proxy.partitionExists(PARTITION_NAME));
    }

    /**
     * Two proxies send their first write to the same endpoint, each on its own
     * partition. Both responses are lost. The endpoint applies each write once
     * even though the two requests have the same sequence number.
     */
    @Test
    public void retriesFromDifferentProxiesWithTheSameSequenceApplyEachWriteOnce() throws Exception {
        var endpoint = endpoints.track(new ServerNodeNetworkInterface(server, 60000));
        var firstNetwork = FakeNetworkInterface.wrap(endpoint)
                .on(n -> n.writeToPartition(any(), any(), any())).onFirstCall().dropResponse();
        var secondNetwork = FakeNetworkInterface.wrap(endpoint)
                .on(n -> n.writeToPartition(any(), any(), any())).onFirstCall().dropResponse();
        var first = new NodeProxy(firstNetwork.build(), 60000);
        first.connect();
        var second = new NodeProxy(secondNetwork.build(), 60000);
        second.connect();
        server.createPartition("first");
        server.createPartition("second");

        first.writeToPartition("first", "A");
        second.writeToPartition("second", "B");

        var firstAttempt = firstNetwork.awaitAttempt(n -> n.writeToPartition(any(), any(), any()), 1);
        var secondAttempt = secondNetwork.awaitAttempt(n -> n.writeToPartition(any(), any(), any()), 1);
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
}
