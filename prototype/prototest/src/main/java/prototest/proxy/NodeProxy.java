package prototest.proxy;

import prototest.api.Node;
import prototest.api.NodeNetworkInterface;

/**
 * Maps node operations to network calls through a dedicated remote call executor.
 * Instances support concurrent calls. Recovery only resets the local call state.
 *
 * @see RemoteCallExecutor
 */
public class NodeProxy implements Node {

    private final NodeNetworkInterface node;
    private final RemoteCallExecutor calls;

    /**
     * Creates a proxy with its own request IDs and recovery state.
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
     * Reports whether a final protocol error requires recovery.
     *
     * @return whether the local call session has failed
     */
    public boolean recoveryRequired() {
        return calls.recoveryRequired();
    }

    /**
     * Enables new calls after a failure without restoring the remote node.
     * Does nothing while healthy. Old calls remain cancelled.
     */
    public void recover() {
        calls.recover();
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
