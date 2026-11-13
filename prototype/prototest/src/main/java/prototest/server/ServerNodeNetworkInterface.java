package prototest.server;

import prototest.api.NodeNetworkInterface;
import prototest.api.RequestId;

public class ServerNodeNetworkInterface implements NodeNetworkInterface {

    private final NodeServer nodeServer;
    private final RequestsRegister requestsRegistery;

    public ServerNodeNetworkInterface(NodeServer nodeServer, long registeryTimeout) {
        this.nodeServer = nodeServer;
        requestsRegistery = new RequestsRegister(registeryTimeout);
    }

    public ServerNodeNetworkInterface(long registeryTimeout) {
        this(new NodeServer(), registeryTimeout);
    }

    @Override
    public synchronized void createPartition(RequestId requestId, String name) {
        requestsRegistery.executeOnce(
                requestId,
                () -> nodeServer.createPartition(name)
        );
    }

    @Override
    public synchronized void destroyPartition(RequestId requestId, String name) {
        requestsRegistery.executeOnce(
                requestId,
                () -> nodeServer.destroyPartition(name)
        );
    }

    @Override
    public synchronized boolean partitionExists(RequestId requestId, String name) {
        return requestsRegistery.executeOnceAndReturn(
                requestId,
                () -> nodeServer.partitionExists(name)
        );
    }

    @Override
    public synchronized void writeToPartition(RequestId requestId, String name, String data) {
        requestsRegistery.executeOnce(
                requestId,
                () -> nodeServer.writeToPartition(name, data)
        );
    }

    @Override
    public void commitPartition(RequestId requestId, String name) {
        requestsRegistery.executeOnce(
                requestId,
                () -> nodeServer.commitPartition(name)
        );
    }

    @Override
    public String readPartitionSnapshot(RequestId requestId, String name) {
        return requestsRegistery.executeOnceAndReturn(
                requestId,
                () -> nodeServer.readPartitionSnapshot(name)
        );
    }
}
