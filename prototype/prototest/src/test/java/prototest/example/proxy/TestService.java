package prototest.example.proxy;

import prototest.example.api.*;
import prototest.example.server.NodeServer;
import prototest.example.server.NodeTestEndpoint;
import prototest.example.server.NodeTestNetwork;
import prototest.remote.network.FakeNetworkInterface;

import java.util.Objects;
import static prototest.example.server.NodeTestEndpoint.proxyFor;

public class TestService implements Service, AutoCloseable {

    private final NodeServer referenceNodeServer;
    private final Service referenceService;

    private final NodeServer nodeServer;
    private final FakeNetworkInterface<NodeTestNetwork> fakeNetworkInterface;
    private final NodeTestEndpoint endpoint;
    private final Service service;

    public TestService(long registryTimeout) {
        referenceNodeServer = new NodeServer();
        referenceService = new ServiceClient(referenceNodeServer);

        nodeServer = new NodeServer();
        endpoint = new NodeTestEndpoint(nodeServer, registryTimeout);
        fakeNetworkInterface = FakeNetworkInterface.wrap(NodeTestNetwork.class, endpoint);

        var nodeProxy = proxyFor(fakeNetworkInterface.build(), registryTimeout);
        nodeProxy.connect();
        service = new ServiceClient(nodeProxy);
    }

    @Override
    public void close() {
        endpoint.close();
    }

    public FakeNetworkInterface<NodeTestNetwork> fakeNetworkInterface() {
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
