package prototest.proxy;

import prototest.api.Node;
import prototest.api.NodeNetworkInterface;

/**
 * Maps node operations to network calls through a dedicated remote call executor.
 * Instances support concurrent calls after opening a session with the server.
 *
 * @see RemoteCallExecutor
 */
public class NodeProxy implements Node {

    private final NodeNetworkInterface node;
    private final RemoteCallExecutor calls;

    /**
     * Creates a disconnected proxy.
     *
     * @param node the network endpoint
     * @param timeout the retry timeout in milliseconds
     * @throws IllegalArgumentException if timeout is negative
     */
    public NodeProxy(NodeNetworkInterface node, long timeout) {
        this.node = node;
        this.calls = new RemoteCallExecutor(timeout);
    }

    /**
     * Opens the initial session or reconnects after a confirmed disconnection.
     * @throws IllegalStateException if the proxy is not disconnected or is changing its connection
     */
    public void connect() {
        calls.connect(node::connect);
    }

    /**
     * Reports whether a final protocol error requires recovery.
     *
     * @return whether the local call session has failed
     */
    public boolean recoveryRequired() {
        return calls.recoveryRequired();
    }

    /**
     * Replaces the failed session with a new one from the server. Does nothing while healthy.
     * @throws IllegalStateException if there is no session to recover or a connection change is in progress
     */
    public void recover() {
        calls.recover(node::recover);
    }

    /**
     * Stops local calls and revokes the session on the server. Retry this operation
     * if confirmation is lost. Does nothing if already disconnected.
     * @throws IllegalStateException if a connection change is already in progress
     */
    public void disconnect() {
        calls.disconnect(node::disconnect);
    }

    @Override
    public void createPartition(String name) {
        calls.execute((requestId) -> node.createPartition(requestId, name));
    }

    @Override
    public void destroyPartition(String name) {
        calls.execute((requestId) -> node.destroyPartition(requestId, name));
    }

    @Override
    public boolean partitionExists(String name) {
        return calls.executeAndReturn((requestId) -> node.partitionExists(requestId, name));
    }

    @Override
    public void writeToPartition(String name, String data) {
        calls.execute((requestId) -> node.writeToPartition(requestId, name, data));
    }

    @Override
    public void commitPartition(String name) {
        calls.execute((requestId) -> node.commitPartition(requestId, name));
    }

    @Override
    public String readPartitionSnapshot(String name) {
        return calls.executeAndReturn((requestId) -> node.readPartitionSnapshot(requestId, name));
    }
}
