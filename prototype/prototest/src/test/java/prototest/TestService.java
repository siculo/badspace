package prototest;

import prototest.api.*;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.proxy.ServiceClient;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.util.Objects;

public class TestService implements Service {

    private final NodeServer referenceNodeServer;
    private final Service referenceService;

    private final NodeServer nodeServer;
    private final FakeNetworkInterface fakeNetworkInterface;
    private final Node nodeProxy;
    private final Service service;

    public TestService(long registeryTimeout, long minNetworkLatency, long maxNetworkLatency) {
        referenceNodeServer = new NodeServer();
        referenceService = new ServiceClient(referenceNodeServer);

        nodeServer = new NodeServer();
        fakeNetworkInterface = new FakeNetworkInterface(new ServerNodeNetworkInterface(nodeServer, registeryTimeout));
        fakeNetworkInterface.setNetworkLatency(minNetworkLatency, maxNetworkLatency);

        nodeProxy = new NodeProxy(fakeNetworkInterface, registeryTimeout);
        service = new ServiceClient(nodeProxy);
    }

    public FakeNetworkInterface fakeNetworkInterface() {
        return fakeNetworkInterface;
    }

    @Override
    public void create(String name) {
        referenceService.create(name);
        service.create(name);

        assert referenceNodeServer.partitionExists(name) == nodeServer.partitionExists(name);
    }

    @Override
    public void destroy(String name) {
        referenceService.destroy(name);
        service.destroy(name);

        assert referenceNodeServer.partitionExists(name) == nodeServer.partitionExists(name);
    }

    @Override
    public boolean exist(String name) {
        boolean existsInReference = referenceService.exist(name);
        boolean exists = service.exist(name);

        assert existsInReference == exists;

        return exists;
    }

    @Override
    public Writer writer(String name) {
        return new TestWriter(name);
    }

    @Override
    public Reader reader(String name) {
        return new TestReader(name);
    }

    class TestWriter implements Writer {
        private final String name;

        private final Writer referenceWriter;
        private final Writer writer;

        TestWriter(String name) {
            this.name = name;
            referenceWriter = new ClientWriter(referenceNodeServer, name);
            writer = new ClientWriter(nodeServer, name);
        }

        @Override
        public void write(String content) {
            referenceWriter.write(content);
            writer.write(content);
            assert Objects.equals(referenceNodeServer.readPartitionSnapshot(name), nodeServer.readPartitionSnapshot(name));
        }

        @Override
        public void commit() {
            referenceWriter.commit();
            writer.commit();
            assert Objects.equals(referenceNodeServer.readPartitionSnapshot(name), nodeServer.readPartitionSnapshot(name));
        }
    }

    class TestReader implements Reader {
        private final Reader referenceReader;
        private final Reader reader;

        TestReader(String name) {
            referenceReader = new ClientReader(referenceNodeServer, name);
            reader = new ClientReader(nodeServer, name);
        }

        @Override
        public String content() {
            String referenceContent = referenceReader.content();
            String content = reader.content();
            assert Objects.equals(referenceContent, content);
            return content;
        }
    }
}
