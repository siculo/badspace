package prototest;

import org.junit.Before;
import org.junit.Test;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.api.errors.service.PartitionNotFoundException;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.time.Duration;

import static org.junit.Assert.*;

public class NetworkResilienceTest {
    private static final String PARTITION_NAME = "example";
    private static final Duration WAIT = Duration.ofSeconds(5);

    private NodeServer server;
    private FakeNetworkInterface network;

    @Before
    public void before() {
        server = new NodeServer();
        network = FakeNetworkInterface.wrap(new ServerNodeNetworkInterface(server, 60000));
    }

    @Test
    public void retriesOnRequestMissing() throws Exception {
        network.on(n -> n.createPartition(null, null)).onFirstCall().dropRequest();
        var proxy = new NodeProxy(network.build(), 60000);

        proxy.createPartition(PARTITION_NAME);

        assertTrue(server.partitionExists(PARTITION_NAME));
        assertEquals(network.awaitCall(n -> n.createPartition(null, null), 1, WAIT).invocation().args()[0],
                network.awaitCall(n -> n.createPartition(null, null), 2, WAIT).invocation().args()[0]);
        network.assertAllRulesUsedAndCallsFinished();
    }

    @Test
    public void retriesOnResponseMissing() throws Exception {
        server.createPartition(PARTITION_NAME);
        network.on(n -> n.partitionExists(null, null)).onFirstCall().dropResponse();
        var proxy = new NodeProxy(network.build(), 60000);

        assertTrue(proxy.partitionExists(PARTITION_NAME));

        assertEquals(network.awaitCall(n -> n.partitionExists(null, null), 1, WAIT).invocation().args()[0],
                network.awaitCall(n -> n.partitionExists(null, null), 2, WAIT).invocation().args()[0]);
        network.assertAllRulesUsedAndCallsFinished();
    }

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

    @Test
    public void returnsAServiceException() {
        network.on(n -> n.writeToPartition(null, null, null)).onEveryCall().deliver();
        var proxy = new NodeProxy(network.build(), 60000);

        assertThrows(PartitionNotFoundException.class,
                () -> proxy.writeToPartition(PARTITION_NAME, "content"));

        assertFalse(proxy.recoveryRequired());
        network.assertAllRulesUsedAndCallsFinished();
    }
}
