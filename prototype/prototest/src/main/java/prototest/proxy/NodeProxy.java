package prototest.proxy;

import prototest.api.Node;
import prototest.api.NodeNetworkInterface;
import prototest.api.errors.network.ProtocolException;
import prototest.api.errors.network.RecoverableProtocolException;
import prototest.api.errors.network.UnrecoverableProtocolException;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

public class NodeProxy implements Node {

    private final NodeNetworkInterface node;
    private final AtomicLong idGenerator;
    private final long timeout;


    public NodeProxy(NodeNetworkInterface node, long timeout) {
        this.node = node;
        this.idGenerator = new AtomicLong();
        this.timeout = timeout;
    }

    @Override
    public void createPartition(String name) {
        execute((requestId) -> node.createPartition(requestId, name));
    }

    @Override
    public void destroyPartition(String name) {
        execute((requestId) -> node.destroyPartition(requestId, name));
    }

    @Override
    public boolean partitionExists(String name) {
        return executeAndReturn((requestId) -> node.partitionExists(requestId, name));
    }

    @Override
    public void writeToPartition(String name, String data) {
        execute((requestId) -> node.writeToPartition(requestId, name, data));
    }

    @Override
    public void commitPartition(String name) {
        execute((requestId) -> node.commitPartition(requestId, name));
    }

    @Override
    public String readPartitionSnapshot(String name) {
        return executeAndReturn((requestId) -> node.readPartitionSnapshot(requestId, name));
    }

    private <T> void execute(Consumer<Long> operation) {
        long requestId = idGenerator.getAndIncrement();
        long startTime = System.currentTimeMillis();
        while (true) {
            try {
                operation.accept(requestId);
                break;
            } catch (RecoverableProtocolException err) {
                if (System.currentTimeMillis() - startTime >= timeout) {
                    throw new UnrecoverableProtocolException("Unrecoverable timeout");
                }
            }
        }
    }
    private <T> T executeAndReturn(Function<Long, T> operation) {
        long requestId = idGenerator.getAndIncrement();
        long startTime = System.currentTimeMillis();
        while (true) {
            try {
                return operation.apply(requestId);
            } catch (RecoverableProtocolException err) {
                if (System.currentTimeMillis() - startTime >= timeout) {
                    throw new UnrecoverableProtocolException("Unrecoverable timeout");
                }
            }
        }
    }
}
