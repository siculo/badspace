package prototest.server;

import prototest.api.NodeNetworkInterface;

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
    public synchronized void createPartition(long requestId, String name) {
        requestsRegistery.executeOnce(
                requestId,
                () -> nodeServer.createPartition(name)
        );
    }

    @Override
    public synchronized void destroyPartition(long requestId, String name) {
        requestsRegistery.executeOnce(
                requestId,
                () -> nodeServer.destroyPartition(name)
        );
    }

    @Override
    public synchronized boolean partitionExists(long requestId, String name) {
        return requestsRegistery.executeOnceAndReturn(
                requestId,
                () -> nodeServer.partitionExists(name)
        );
    }

    @Override
    public synchronized void writeToPartition(long requestId, String name, String data) {
        requestsRegistery.executeOnce(
                requestId,
                () -> nodeServer.writeToPartition(name, data)
        );
    }

    @Override
    public void commitPartition(long requestId, String name) {
        requestsRegistery.executeOnce(
                requestId,
                () -> nodeServer.commitPartition(name)
        );
    }

    @Override
    public String readPartitionSnapshot(long requestId, String name) {
        return requestsRegistery.executeOnceAndReturn(
                requestId,
                () -> nodeServer.readPartitionSnapshot(name)
        );
    }
}
