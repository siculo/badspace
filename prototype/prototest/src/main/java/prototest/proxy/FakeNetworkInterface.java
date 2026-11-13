package prototest.proxy;

import prototest.api.NodeNetworkInterface;
import prototest.api.RequestId;
import prototest.api.errors.network.RetryableCommunicationException;
import prototest.api.errors.network.NodeRecoveryRequiredException;

import java.util.*;
import java.util.function.Supplier;

/**
 * Simulates a network shared by concurrent callers. Queue and configuration changes
 * are synchronized, but latency and server operations do not hold the network monitor.
 * Each attempt consumes one queued outcome; concurrent callers have no fixed order.
 */
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
    public void createPartition(RequestId requestId, String name) {
        execute(() -> node.createPartition(requestId, name));
    }

    @Override
    public void destroyPartition(RequestId requestId, String name) {
        execute(() -> node.destroyPartition(requestId, name));
    }

    @Override
    public boolean partitionExists(RequestId requestId, String name) {
        return executeAndReturn(() -> node.partitionExists(requestId, name));
    }

    @Override
    public void writeToPartition(RequestId requestId, String name, String data) {
        execute(() -> node.writeToPartition(requestId, name, data));
    }

    @Override
    public void commitPartition(RequestId requestId, String name) {
        execute(() -> node.commitPartition(requestId, name));
    }

    @Override
    public String readPartitionSnapshot(RequestId requestId, String name) {
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

    public synchronized void pushResults(OpResult... results) {
        opResultQueue.addAll(List.of(results));
    }

    public synchronized void pushResults(int repetitions, OpResult... results) {
        for(int i = 0; i < repetitions; i++) {
            opResultQueue.addAll(List.of(results));
        }
    }

    public synchronized void clearResults() {
        opResultQueue.clear();
    }

    private synchronized OpResult fetchResult() {
        return Objects.requireNonNullElse(opResultQueue.poll(), OpResult.OK);
    }

    private void simulateRequestDispatch(OpResult opResult) {
        randomSleep();
        if (opResult == OpResult.REQUEST_MISSING) {
            throw new RetryableCommunicationException("Request missing");
        }
    }

    private void simulateResponseDispatch(OpResult opResult) {
        randomSleep();
        if (opResult == OpResult.RESPONSE_MISSING) {
            throw new RetryableCommunicationException("Response missing");
        }
    }

    private void randomSleep() {
        try {
            Thread.sleep(nextLatency());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeRecoveryRequiredException("Network attempt interrupted", e);
        }
    }

    private synchronized long nextLatency() {
        return minLatency == maxLatency ? minLatency : random.nextLong(minLatency, maxLatency);
    }

    public synchronized void setRandom(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public synchronized void setNetworkLatency(long minLatency, long maxLatency) {
        if (minLatency < 0 || maxLatency < minLatency) {
            throw new IllegalArgumentException("minLatency = %d, maxLatency = %d".formatted(minLatency, maxLatency));
        }
        this.minLatency = minLatency;
        this.maxLatency = maxLatency;
    }
}
