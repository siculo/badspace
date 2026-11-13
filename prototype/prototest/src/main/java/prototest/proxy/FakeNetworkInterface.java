package prototest.proxy;

import prototest.api.NodeNetworkInterface;
import prototest.api.errors.network.RecoverableProtocolException;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class FakeNetworkInterface implements NodeNetworkInterface {

    private final NodeNetworkInterface node;

    private Random random = new Random();

    private long minLatency = 70;
    private long maxLatency = 100;

    public enum OpResult {
        OK, REQUEST_MISSING, RESPONSE_MISSING
    }

    private final Queue<OpResult> opResultQueue = new LinkedList<>();

    public FakeNetworkInterface(NodeNetworkInterface node) {
        Objects.requireNonNull(node);
        this.node = node;
    }

    @Override
    public void createPartition(long requestId, String name) {
        execute(() -> node.createPartition(requestId, name));
    }

    @Override
    public void destroyPartition(long requestId, String name) {
        execute(() -> node.destroyPartition(requestId, name));
    }

    @Override
    public boolean partitionExists(long requestId, String name) {
        return executeAndReturn(() -> node.partitionExists(requestId, name));
    }

    @Override
    public void writeToPartition(long requestId, String name, String data) {
        execute(() -> node.writeToPartition(requestId, name, data));
    }

    @Override
    public void commitPartition(long requestId, String name) {
        execute(() -> node.commitPartition(requestId, name));
    }

    @Override
    public String readPartitionSnapshot(long requestId, String name) {
        return executeAndReturn(() -> node.readPartitionSnapshot(requestId, name));
    }

    private <T> T executeAndReturn(Supplier<T> operation) {
        OpResult opResult = fetchResult();
        simulateRequestDispatch(opResult);
        T result = operation.get();
        simulateResponseDispatch(opResult);
        return result;
    }

    private void execute(Runnable operation) {
        OpResult opResult = fetchResult();
        simulateRequestDispatch(opResult);
        operation.run();
        simulateResponseDispatch(opResult);
    }

    public void pushResults(OpResult... results) {
        opResultQueue.addAll(Arrays.stream(results).toList());
    }

    public void pushResults(int repetitions, OpResult... results) {
        for(int i = 0; i < repetitions; i++) {
            opResultQueue.addAll(Arrays.stream(results).toList());
        }
    }

    public void clearResults() {
        opResultQueue.clear();
    }

    private OpResult fetchResult() {
        return Objects.requireNonNullElse(opResultQueue.poll(), OpResult.OK);
    }

    private void simulateRequestDispatch(OpResult opResult) {
        randomSleep();
        if (opResult == OpResult.REQUEST_MISSING) {
            throw new RecoverableProtocolException("Request missing");
        }
    }

    private void simulateResponseDispatch(OpResult opResult) {
        randomSleep();
        if (opResult == OpResult.RESPONSE_MISSING) {
            throw new RecoverableProtocolException("Request missing");
        }
    }

    private void randomSleep() {
        try {
            if (minLatency > 0 && maxLatency >= minLatency) {
                Thread.sleep(random.nextLong(minLatency, maxLatency));
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void setRandom(Random random) {
        this.random = random;
    }

    public void setNetworkLatency(long minLatency, long maxLatency) {
        if (minLatency < 0 || maxLatency < minLatency) {
            throw new IllegalArgumentException("minLatency = %d, maxLatency = %d".formatted(minLatency, maxLatency));
        }
        this.minLatency = minLatency;
        this.maxLatency = maxLatency;
    }
}
