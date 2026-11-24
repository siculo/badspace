package prototest.example.server;

import prototest.example.proxy.NodeProxy;
import prototest.remote.api.RequestId;
import prototest.remote.client.RemoteCallExecutor;
import prototest.remote.server.ServerSessionEndpoint;

import java.time.Clock;
import java.util.UUID;

/** Exposes both contracts so tests can observe them through one simulated network. */
public class NodeTestEndpoint implements NodeTestNetwork, AutoCloseable {
    private final ServerSessionEndpoint sessions;
    private final ServerNodeNetworkInterface node;

    public NodeTestEndpoint(long requestTimeout) {
        this(new NodeServer(), requestTimeout);
    }

    public NodeTestEndpoint(NodeServer nodeServer, long requestTimeout) {
        this(nodeServer, new ServerSessionEndpoint(requestTimeout));
    }

    public NodeTestEndpoint(NodeServer nodeServer, long requestTimeout, Clock clock) {
        this(nodeServer, new ServerSessionEndpoint(requestTimeout, clock));
    }

    public NodeTestEndpoint(NodeServer nodeServer, long requestTimeout, long sessionTimeout, Clock clock) {
        this(nodeServer, new ServerSessionEndpoint(requestTimeout, sessionTimeout, clock));
    }

    private NodeTestEndpoint(NodeServer nodeServer, ServerSessionEndpoint sessions) {
        this.sessions = sessions;
        this.node = new ServerNodeNetworkInterface(nodeServer, sessions);
    }

    public static NodeProxy proxyFor(NodeTestNetwork network, long timeout) {
        return new NodeProxy(network, new RemoteCallExecutor(network, timeout));
    }

    @Override
    public UUID connect() {
        return sessions.connect();
    }

    @Override
    public UUID recover(UUID previousToken) {
        return sessions.recover(previousToken);
    }

    @Override
    public void disconnect(UUID token) {
        sessions.disconnect(token);
    }

    @Override
    public void close() {
        sessions.close();
    }

    @Override
    public void createPartition(RequestId requestId, String name) {
        node.createPartition(requestId, name);
    }

    @Override
    public void destroyPartition(RequestId requestId, String name) {
        node.destroyPartition(requestId, name);
    }

    @Override
    public boolean partitionExists(RequestId requestId, String name) {
        return node.partitionExists(requestId, name);
    }

    @Override
    public void writeToPartition(RequestId requestId, String name, String data) {
        node.writeToPartition(requestId, name, data);
    }

    @Override
    public void commitPartition(RequestId requestId, String name) {
        node.commitPartition(requestId, name);
    }

    @Override
    public String readPartitionSnapshot(RequestId requestId, String name) {
        return node.readPartitionSnapshot(requestId, name);
    }
}
