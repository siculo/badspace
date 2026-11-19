package prototest.server;

import org.junit.Before;
import org.junit.Test;
import prototest.api.Reader;
import prototest.api.Service;
import prototest.api.Writer;
import prototest.proxy.ServiceClient;

import static org.junit.Assert.*;

public class NodeServerTest {

    public static final String PARTITION_NAME = "prova";

    private Service service;

    @Before
    public void before() {
        service = new ServiceClient(new NodeServer());
    }

    @Test
    public void createAddANewPartition() {
        service.create(PARTITION_NAME);
        assertTrue(service.exist(PARTITION_NAME));
    }

    @Test
    public void destroyRemovesAnExistingPartition() {
        service.create(PARTITION_NAME);
        service.destroy(PARTITION_NAME);
        assertFalse(service.exist(PARTITION_NAME));
    }

    @Test
    public void partitionsWritersAndReaders() {
        service.create(PARTITION_NAME);
        Writer writer = service.writer(PARTITION_NAME);
        writer.write("uno ");
        writer.write("due");
        Reader r0 = service.reader(PARTITION_NAME);
        assertEquals("", r0.content());
        writer.commit();
        Reader r1 = service.reader(PARTITION_NAME);
        assertEquals("uno due", r1.content());
    }
}
