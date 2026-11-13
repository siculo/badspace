package prototest;

import org.junit.Before;
import org.junit.Test;
import prototest.api.Node;
import prototest.api.Reader;
import prototest.api.Writer;
import prototest.api.errors.network.NodeRecoveryRequiredException;
import prototest.api.errors.service.PartitionNotFoundException;
import prototest.proxy.FakeNetworkInterface;

import static org.junit.Assert.*;
import static prototest.proxy.FakeNetworkInterface.OpResult;

public class NetworkResilienceTest {

    public static final String PARTITION_NAME = "prova";

    private TestService service;

    private FakeNetworkInterface fakeNetworkInterface;
    private Node nodeProxy;

    @Before
    public void before() {
        service = new TestService(200, 8, 12);
        fakeNetworkInterface = service.fakeNetworkInterface();
    }

    @Test
    public void retriesOnRequestMissing() {
        fakeNetworkInterface.pushResults(
                OpResult.OK, // create partition
                OpResult.REQUEST_MISSING, // partition exists
                OpResult.OK // partition exists
        );
        service.create(PARTITION_NAME);
        assertTrue(service.exist(PARTITION_NAME));
    }

    @Test
    public void retriesOnResponseMissing() {
        fakeNetworkInterface.pushResults(
                OpResult.OK, // create partition
                OpResult.RESPONSE_MISSING, // partition exists
                OpResult.OK // partition exists
        );
        service.create(PARTITION_NAME);
        assertTrue(service.exist(PARTITION_NAME));
    }

    @Test
    public void retriesWithoutDuplicatingEffects() {
        fakeNetworkInterface.pushResults(
                OpResult.OK, // create partition
                OpResult.RESPONSE_MISSING, // write
                OpResult.OK, // commit
                OpResult.OK // content
        );
        service.create(PARTITION_NAME);
        Writer writer = service.writer(PARTITION_NAME);
        writer.write("any content");
        writer.commit();
        Reader reader = service.reader(PARTITION_NAME);
        assertEquals("any content", reader.content());
    }

    @Test
    public void requiresNodeRecoveryAfterRetryTimeout() {
        fakeNetworkInterface.pushResults(13, OpResult.REQUEST_MISSING, OpResult.RESPONSE_MISSING);
        NodeRecoveryRequiredException exception = assertThrows(NodeRecoveryRequiredException.class, () -> service.create(PARTITION_NAME));
        assertEquals("Unrecoverable timeout", exception.getMessage());
    }

    @Test
    public void returnsAServiceException() {
        fakeNetworkInterface.pushResults(OpResult.OK);
        Writer writer = service.writer(PARTITION_NAME);
        assertThrows(PartitionNotFoundException.class, () -> writer.write("any content"));
    }
}
