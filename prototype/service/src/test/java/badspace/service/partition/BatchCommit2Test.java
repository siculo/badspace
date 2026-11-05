package badspace.service.partition;

import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.service.partition.Names.nameOf;
import static badspace.service.partition.Owners.writes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.common.partition.EntityWrite2;
import badspace.common.partition.PartitionFailureException;
import badspace.common.partition.PartitionHealth;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionOperation;
import badspace.common.partition.WriteMode;
import badspace.common.partition.WriteSession2;
import badspace.common.partition.WriteToken;
import badspace.service.index.SpatialIndexes;
import badspace.service.partition.slots.CoordinateBufferPool;
import badspace.service.partition.snapshot.MonotonicClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the commit of a batch on a 2D node in the buffered mode, with the writes of the owner
 * in its {@link WriteSession2}: the node applies the batch and prepares the snapshot in one step
 * under the lock of the partition, so the moves of the tick and the claims wait for it and see
 * the batch whole or not at all. A rejected batch changes nothing, and the writer keeps its
 * writes; a failed one keeps the last commit and the snapshot that waits for the move of the
 * tick, and the writer discards its writes, like the writes sent to the node one by one.
 * The index of the tests stops the batch at a gate or makes it fail, and the lock of the
 * partition tells when another thread waits for it, so the tests need no sleep.
 */
class BatchCommit2Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long[] IDS = {1, 2, 3};
    private static final Point2 A = new Point2(1, 1);
    private static final Point2 B = new Point2(2, 2);
    private static final Point2 C = new Point2(3, 3);
    private static final Point2 OUTSIDE = new Point2(Double.MAX_VALUE, 0);

    private final GatedIndex2[] index = new GatedIndex2[1];
    private final ObservedLock lock = new ObservedLock();
    private LocalPartitionNode2 node;
    private WriteToken token;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode2(MonotonicClock.SYSTEM,
                (indexConfig, retainedTicks, clock, tick) -> new PartitionStorage2(indexConfig.limits(),
                        slots -> index[0] = new GatedIndex2(SpatialIndexes.create(indexConfig, slots)),
                        retainedTicks, clock, tick, lock, new CoordinateBufferPool()), WriteMode.BUFFERED);
        node.createPartition(P1, nameOf(P1));
        token = node.claim(P1, 0).orElseThrow();
        // The first commit, at tick 0: the snapshot of tick 0 waits for the move of the tick.
        writes(node, token).setAll(List.of(new Entity2(1, A)));
        writes(node, token).commit();
    }

    @Test
    void moveOfTheTickDuringTheBatchWaitsAndPublishesTheBatchWhole() throws Exception {
        writes(node, token).setAll(List.of(new Entity2(1, B), new Entity2(2, B), new Entity2(3, B)));
        Gate gate = new Gate();
        index[0].notificationGate = gate;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = worker(() -> writes(node, token).commit(), failure);
        Thread ticker = worker(() -> node.setTick(1), failure);
        writer.start();
        try {
            gate.awaitEntered();
            ticker.start();
            // The move of the tick waits for the lock of the partition, which the commit holds.
            lock.awaitWaiting();
        } finally {
            gate.open();
            join(writer);
            join(ticker);
        }
        if (failure.get() != null) {
            throw new AssertionError("commit or move of the tick failed", failure.get());
        }
        index[0].notificationGate = null;
        assertEquals(List.of(new Entity2(1, B), new Entity2(2, B), new Entity2(3, B)), latest());
    }

    @Test
    void claimDuringTheBatchWaitsAndTheNewOwnerFindsTheBatchConfirmed() throws Exception {
        node.setTick(1);
        writes(node, token).setAll(List.of(new Entity2(2, B), new Entity2(3, B)));
        Gate gate = new Gate();
        index[0].notificationGate = gate;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<WriteToken> claimed = new AtomicReference<>();
        Thread writer = worker(() -> writes(node, token).commit(), failure);
        Thread claimer = worker(() -> claimed.set(node.claim(P1, token.generation()).orElseThrow()), failure);
        writer.start();
        try {
            gate.awaitEntered();
            claimer.start();
            lock.awaitWaiting();
        } finally {
            gate.open();
            join(writer);
            join(claimer);
        }
        if (failure.get() != null) {
            throw new AssertionError("commit or claim failed", failure.get());
        }
        index[0].notificationGate = null;
        assertEquals(1, node.lastCommitTick(claimed.get()));
        node.setTick(2);
        assertEquals(List.of(new Entity2(1, A), new Entity2(2, B), new Entity2(3, B)), latest());
    }

    @Test
    void rejectedBatchKeepsTheSnapshotThatWaitsForTheMoveOfTheTick() {
        assertThrows(IllegalArgumentException.class, () -> node.commit(token,
                List.of(EntityWrite2.set(new Entity2(2, B)), EntityWrite2.set(new Entity2(3, OUTSIDE)))));
        assertSame(PartitionHealth.HEALTHY, node.health(P1));
        assertEquals(0, node.lastCommitTick(token));
        node.setTick(1);
        assertEquals(List.of(new Entity2(1, A)), latest());
    }

    @Test
    void failedBatchKeepsTheSnapshotThatWaitsAndTheWriterDiscardsItsWrites() {
        WriteSession2 writes = writes(node, token);
        writes.setAll(List.of(new Entity2(1, C), new Entity2(2, C)));
        index[0].notificationFailure = new IllegalStateException("Index failure for the test");
        assertThrows(PartitionFailureException.class, writes::commit);
        index[0].notificationFailure = null;
        assertSame(PartitionHealth.HEALTHY, node.health(P1));
        node.setTick(1);
        assertEquals(List.of(new Entity2(1, A)), latest());

        // Like a failure of the writes sent one by one: the writes are gone.
        writes.commit();
        node.setTick(2);
        assertEquals(List.of(new Entity2(1, A)), latest());
    }

    @Test
    void failedFlushDiscardsAlsoTheWritesFlushedBefore() {
        WriteSession2 writes = writes(node, token);
        writes.setAll(List.of(new Entity2(2, B)));
        writes.flush();
        writes.setAll(List.of(new Entity2(3, C)));
        index[0].notificationFailure = new IllegalStateException("Index failure for the test");
        PartitionFailureException failure = assertThrows(PartitionFailureException.class, writes::flush);
        index[0].notificationFailure = null;
        assertSame(PartitionOperation.WRITE, failure.failure().operation());
        writes.commit();
        node.setTick(1);
        assertEquals(List.of(new Entity2(1, A)), latest());
    }

    @Test
    void failedRestoreRejectsTheCommitsUntilTheRecoveryAndARejectedCommitKeepsTheWrites() {
        WriteSession2 writes = writes(node, token);
        writes.setAll(List.of(new Entity2(2, C)));
        index[0].notificationFailure = new IllegalStateException("Index failure for the test");
        index[0].restoreFailure = new IllegalStateException("Restore failure for the test");
        assertThrows(PartitionFailureException.class, writes::commit);
        index[0].notificationFailure = null;
        assertInstanceOf(PartitionHealth.RecoveryRequired.class, node.health(P1));
        writes.setAll(List.of(new Entity2(3, C)));
        assertThrows(IllegalStateException.class, writes::commit);
        // The move of the tick publishes the last commit, which is whole.
        node.setTick(1);
        assertEquals(List.of(new Entity2(1, A)), latest());

        // The recovery on the node: the rejected commit kept the writes, the failed one did not.
        index[0].restoreFailure = null;
        node.rollback(token);
        assertSame(PartitionHealth.HEALTHY, node.health(P1));
        writes.commit();
        node.setTick(2);
        assertEquals(List.of(new Entity2(1, A), new Entity2(3, C)), latest());
    }

    private List<Entity2> latest() {
        return node.getAll(node.acquireLatest(P1, defaultTimeout()), IDS);
    }

    private static Thread worker(Runnable action, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                action.run();
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TIMEOUT.toMillis());
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(TIMEOUT.toMillis());
            throw new AssertionError("Worker did not finish: " + thread.getName());
        }
    }
}
