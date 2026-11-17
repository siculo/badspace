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
import static prototest.proxy.FakeNetworkInterface.Delivery.*;
import static prototest.proxy.FakeNetworkInterface.Operation.*;

public class NetworkResilienceTest {
    private static final String PARTITION_NAME = "example";
    private static final Duration WAIT = Duration.ofSeconds(5);

    private NodeServer server;
    private FakeNetworkInterface network;
    private NodeProxy proxy;

    @Before
    public void before() {
        server = new NodeServer();
        network = new FakeNetworkInterface(new ServerNodeNetworkInterface(server, 60000));
        proxy = new NodeProxy(network, 60000);
    }

    @Test
    public void retriesOnRequestMissing() throws Exception {
        var lost = network.expect(call -> call.operation() == CREATE_PARTITION, DROP, DELIVER);
        var retry = network.expect(call -> call.operation() == CREATE_PARTITION, DELIVER, DELIVER);

        proxy.createPartition(PARTITION_NAME);

        assertTrue(server.partitionExists(PARTITION_NAME));
        assertEquals(lost.request().awaitArrival(WAIT).requestId(),
                retry.request().awaitArrival(WAIT).requestId());
        network.verify();
    }

    @Test
    public void retriesOnResponseMissing() throws Exception {
        server.createPartition(PARTITION_NAME);
        var lost = network.expect(call -> call.operation() == PARTITION_EXISTS, DELIVER, DROP);
        var retry = network.expect(call -> call.operation() == PARTITION_EXISTS, DELIVER, DELIVER);

        assertTrue(proxy.partitionExists(PARTITION_NAME));

        assertEquals(lost.response().awaitArrival(WAIT).requestId(),
                retry.response().awaitArrival(WAIT).requestId());
        network.verify();
    }

    @Test
    public void retriesWithoutDuplicatingEffects() {
        server.createPartition(PARTITION_NAME);
        network.expect(call -> call.operation() == WRITE_TO_PARTITION, DELIVER, DROP);
        network.expect(call -> call.operation() == WRITE_TO_PARTITION, DELIVER, DELIVER);
        network.expect(call -> call.operation() == COMMIT_PARTITION, DELIVER, DROP);
        network.expect(call -> call.operation() == COMMIT_PARTITION, DELIVER, DELIVER);

        proxy.writeToPartition(PARTITION_NAME, "content");
        proxy.commitPartition(PARTITION_NAME);

        assertEquals("content", proxy.readPartitionSnapshot(PARTITION_NAME));
        network.verify();
    }

    @Test
    public void requiresNodeRecoveryAfterRetryTimeout() {
        proxy = new NodeProxy(network, 0);
        network.expect(call -> call.operation() == CREATE_PARTITION, DROP, DELIVER);

        var error = assertThrows(NodeRecoveryRequiredException.class,
                () -> proxy.createPartition(PARTITION_NAME));

        assertEquals("Unrecoverable timeout", error.getMessage());
        assertTrue(proxy.recoveryRequired());
        assertFalse(server.partitionExists(PARTITION_NAME));
        network.verify();
    }

    @Test
    public void returnsAServiceException() {
        network.expect(call -> call.operation() == WRITE_TO_PARTITION, DELIVER, DELIVER);

        assertThrows(PartitionNotFoundException.class,
                () -> proxy.writeToPartition(PARTITION_NAME, "content"));

        assertFalse(proxy.recoveryRequired());
        network.verify();
    }
}
