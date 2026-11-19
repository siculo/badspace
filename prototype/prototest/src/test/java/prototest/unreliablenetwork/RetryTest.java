package prototest.unreliablenetwork;

import org.junit.Before;
import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.api.errors.service.PartitionNotFoundException;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import static org.junit.Assert.*;

/**
 * Checks that the proxy retries lost requests and lost responses with the same
 * request ID, without duplicating their effects, and when it stops retrying.
 *
 * <p>Uses the real proxy and the real server adapter behind the fake network,
 * with explicit delivery rules.
 */
public class RetryTest {
    private static final String PARTITION_NAME = "example";

    private NodeServer server;
    private FakeNetworkInterface network;

    @Before
    public void before() {
        server = new NodeServer();
        network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000));
    }

    /**
     * The first request is lost before the server. The proxy sends it again with
     * the same request ID and the partition is created.
     */
    @Test
    public void retriesOnRequestMissing() throws Exception {
        network.on(n -> n.createPartition(null, null)).onFirstCall().dropRequest();
        var proxy = new NodeProxy(network.build(), 60000);

        proxy.createPartition(PARTITION_NAME);

        assertTrue(server.partitionExists(PARTITION_NAME));
        var firstAttempt = network.awaitAttempt(n -> n.createPartition(null, null), 1);
        var retryAttempt = network.awaitAttempt(n -> n.createPartition(null, null), 2);
        var firstRequestId = (RequestId) firstAttempt.invocation().args()[0];
        var retryRequestId = (RequestId) retryAttempt.invocation().args()[0];
        assertEquals(firstRequestId, retryRequestId);
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * The server answers, but the first response is lost. The proxy asks again with
     * the same request ID and receives the result.
     */
    @Test
    public void retriesOnResponseMissing() throws Exception {
        server.createPartition(PARTITION_NAME);
        network.on(n -> n.partitionExists(null, null)).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 60000);

        assertTrue(proxy.partitionExists(PARTITION_NAME));

        var firstAttempt = network.awaitAttempt(n -> n.partitionExists(null, null), 1);
        var retryAttempt = network.awaitAttempt(n -> n.partitionExists(null, null), 2);
        var firstRequestId = (RequestId) firstAttempt.invocation().args()[0];
        var retryRequestId = (RequestId) retryAttempt.invocation().args()[0];
        assertEquals(firstRequestId, retryRequestId);
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * The responses of the first write and of the first commit are lost after the
     * server has applied them. The retries return the saved results: the content is
     * written only once.
     */
    @Test
    public void retriesWithoutDuplicatingEffects() {
        server.createPartition(PARTITION_NAME);
        network.on(n -> n.writeToPartition(null, null, null)).onFirstCall().dropResponse()
                .on(n -> n.commitPartition(null, null)).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 60000);

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
        network.on(n -> n.createPartition(null, null)).onFirstCall().dropRequest();
        var proxy = new NodeProxy(network.build(), 0);

        var error = assertThrows(NodeRecoveryRequiredException.class,
                () -> proxy.createPartition(PARTITION_NAME));

        assertEquals("Unrecoverable timeout", error.getMessage());
        assertTrue(proxy.recoveryRequired());
        assertFalse(server.partitionExists(PARTITION_NAME));
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * A service error, such as a missing partition, crosses the network as a
     * normal response. It reaches the caller without retries and the proxy stays
     * usable.
     */
    @Test
    public void returnsAServiceException() {
        network.on(n -> n.writeToPartition(null, null, null)).onEveryCall().deliver();
        var proxy = new NodeProxy(network.build(), 60000);

        assertThrows(PartitionNotFoundException.class,
                () -> proxy.writeToPartition(PARTITION_NAME, "content"));

        assertFalse(proxy.recoveryRequired());
        network.assertAllRulesUsedAndCallsFinished();
    }

    /**
     * The first request is lost and the response of the second attempt is lost too.
     * The third attempt succeeds and leaves no error state in the proxy.
     */
    @Test
    public void aSuccessfulRetryLeavesTheProxyUsable() {
        network.on(n -> n.createPartition(null, null)).onFirstCall().dropRequest()
                .on(n -> n.createPartition(null, null)).onCall(2).dropResponse();
        var proxy = new NodeProxy(network.build(), 60000);

        proxy.createPartition(PARTITION_NAME);

        assertFalse(proxy.recoveryRequired());
        assertTrue(proxy.partitionExists(PARTITION_NAME));
    }

    /**
     * Two proxies share the same server, each one behind its own fake network.
     * Writes and the commit lose requests or responses: every retry keeps the ID of
     * its call, the server applies each write and the commit only once, and the IDs
     * of the two proxies differ. In the same proxy the commit, which comes later,
     * has a higher sequence than the write.
     */
    @Test
    public void retriesOfSeveralProxiesKeepTheirIdsAndDoNotDuplicateWritesOrCommits() throws Exception {
        var endpoint = new ServerNodeNetworkInterface(server, 60000);
        var firstNetwork = FakeNetworkInterface.wrap(endpoint)
                .on(n -> n.writeToPartition(null, null, null)).onFirstCall().dropRequest()
                .on(n -> n.writeToPartition(null, null, null)).onCall(2).dropResponse()
                .on(n -> n.commitPartition(null, null)).onFirstCall().dropResponse();
        var secondNetwork = FakeNetworkInterface.wrap(endpoint)
                .on(n -> n.writeToPartition(null, null, null)).onFirstCall().dropResponse();
        var first = new NodeProxy(firstNetwork.build(), 60000);
        var second = new NodeProxy(secondNetwork.build(), 60000);
        server.createPartition("shared");

        first.writeToPartition("shared", "A");
        second.writeToPartition("shared", "B");
        first.commitPartition("shared");

        assertEquals("AB", server.readPartitionSnapshot("shared"));
        assertEquals("AB", second.readPartitionSnapshot("shared"));
        firstNetwork.assertAllRulesUsedAndCallsFinished();
        secondNetwork.assertAllRulesUsedAndCallsFinished();
        var writeAttempt = firstNetwork.awaitAttempt(n -> n.writeToPartition(null, null, null), 1);
        var writeId = (RequestId) writeAttempt.invocation().args()[0];
        for (int number = 2; number <= 3; number++) {
            var retryAttempt = firstNetwork.awaitAttempt(n -> n.writeToPartition(null, null, null), number);
            var retryRequestId = (RequestId) retryAttempt.invocation().args()[0];
            assertEquals(writeId, retryRequestId);
        }
        var secondWriteAttempt = secondNetwork.awaitAttempt(n -> n.writeToPartition(null, null, null), 1);
        var secondWriteId = (RequestId) secondWriteAttempt.invocation().args()[0];
        var secondWriteRetryAttempt = secondNetwork.awaitAttempt(n -> n.writeToPartition(null, null, null), 2);
        var secondWriteRetryId = (RequestId) secondWriteRetryAttempt.invocation().args()[0];
        assertEquals(secondWriteId, secondWriteRetryId);

        var commitAttempt = firstNetwork.awaitAttempt(n -> n.commitPartition(null, null), 1);
        var commitId = (RequestId) commitAttempt.invocation().args()[0];
        var commitRetryAttempt = firstNetwork.awaitAttempt(n -> n.commitPartition(null, null), 2);
        var commitRetryId = (RequestId) commitRetryAttempt.invocation().args()[0];
        assertEquals(commitId, commitRetryId);
        assertEquals(writeId.proxyId(), commitId.proxyId());
        assertTrue(commitId.sequence() > writeId.sequence());
        assertNotEquals(writeId.proxyId(), secondWriteId.proxyId());
    }
}
