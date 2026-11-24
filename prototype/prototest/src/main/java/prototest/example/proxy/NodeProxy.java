package prototest.example.proxy;

import prototest.example.api.Node;
import prototest.example.api.NodeNetworkInterface;
import prototest.remote.api.errors.NodeRecoveryRequiredException;
import prototest.remote.client.RemoteCallExecutor;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Maps node operations to network calls through a remote call executor.
 * Instances support concurrent calls after opening a session with the server.
 *
 * @see RemoteCallExecutor
 */
public final class NodeProxy implements Node {

    private final NodeNetworkInterface node;
    private final RemoteCallExecutor calls;

    /**
     * Binds the node operations to a client call executor.
     *
     * @param node the remote node operations
     * @param calls the executor for the session used by this proxy
     */
    public NodeProxy(NodeNetworkInterface node, RemoteCallExecutor calls) {
        this.node = Objects.requireNonNull(node, "node");
        this.calls = Objects.requireNonNull(calls, "calls");
    }

    /**
     * Opens the initial session or reconnects after a confirmed disconnection.
     * @throws IllegalStateException if the proxy is not disconnected or is changing its connection
     */
    public void connect() {
        calls.connect();
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
     * Renews only the failed session. Use this after an idle session expires
     * when no calls have uncertain effects. For uncertain writes use
     * {@link #recover(Predicate)}. Does nothing while healthy.
     * @throws IllegalStateException if there is no session to recover or a connection change is in progress
     */
    public void recover() {
        recover(node -> true);
    }

    /**
     * Renews a failed session, then runs the client's repair on the calling thread.
     * Other threads cannot use this proxy until repair succeeds. Other proxies
     * need separate coordination. If repair fails, repeat with a repair that
     * can start from partially restored state. Errors from renewal and repair
     * reach the caller and leave recovery required.
     * The repair must rethrow any call error that requires recovery.
     *
     * @param repair synchronous repair that returns true when the node state is consistent
     * @return true if already healthy or repair succeeded, false if repair reports
     *         that more work is needed
     * @throws IllegalStateException if there is no session to recover or a connection change is in progress
     * @throws NodeRecoveryRequiredException if a call fails and leaves recovery required,
     *         even if the repair catches the error and returns a value
     */
    public boolean recover(Predicate<Node> repair) {
        Objects.requireNonNull(repair, "repair");
        return calls.recover(() -> repair.test(this));
    }

    /**
     * Stops local calls and revokes the session on the server. Once disconnection
     * succeeds, call {@link #connect()} to resume using the proxy. If confirmation
     * is lost, retry this operation first. Does nothing if already disconnected.
     * @throws IllegalStateException if a connection change is already in progress
     */
    public void disconnect() {
        calls.disconnect();
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
