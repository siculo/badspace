package prototest.example.server;

import prototest.example.api.NodeNetworkInterface;
import badspace.remote.api.RequestId;
import badspace.remote.server.ServerSessionEndpoint;

import java.util.Objects;

/** Exposes the node operations through the server's session endpoint. */
public final class ServerNodeNetworkInterface implements NodeNetworkInterface {
    private final NodeServer nodeServer;
    private final ServerSessionEndpoint sessions;

    /**
     * Binds the node operations to the sessions accepted by this endpoint.
     *
     * @param nodeServer the server that executes the operations
     * @param sessions the endpoint that validates sessions and handles retries
     */
    public ServerNodeNetworkInterface(NodeServer nodeServer, ServerSessionEndpoint sessions) {
        this.nodeServer = Objects.requireNonNull(nodeServer, "nodeServer");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public void createPartition(RequestId requestId, String name) {
        sessions.executeOnce(
                requestId,
                () -> nodeServer.createPartition(name)
        );
    }

    @Override
    public void destroyPartition(RequestId requestId, String name) {
        sessions.executeOnce(
                requestId,
                () -> nodeServer.destroyPartition(name)
        );
    }

    @Override
    public boolean partitionExists(RequestId requestId, String name) {
        return sessions.executeOnceAndReturn(
                requestId,
                () -> nodeServer.partitionExists(name)
        );
    }

    @Override
    public void writeToPartition(RequestId requestId, String name, String data) {
        sessions.executeOnce(
                requestId,
                () -> nodeServer.writeToPartition(name, data)
        );
    }

    @Override
    public void commitPartition(RequestId requestId, String name) {
        sessions.executeOnce(
                requestId,
                () -> nodeServer.commitPartition(name)
        );
    }

    @Override
    public String readPartitionSnapshot(RequestId requestId, String name) {
        return sessions.executeOnceAndReturn(
                requestId,
                () -> nodeServer.readPartitionSnapshot(name)
        );
    }
}
