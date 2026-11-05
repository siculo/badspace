package badspace.service.partition;

import static badspace.service.partition.Owners.writes;
import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.common.partition.Entity3.entity;
import static badspace.service.partition.Names.nameOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.partition.Ownership;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionHealth;
import badspace.common.partition.PartitionId;
import badspace.common.partition.WriteToken;
import badspace.service.index.SpatialIndexes;
import badspace.service.partition.slots.CoordinateBufferPool;
import badspace.service.partition.snapshot.MonotonicClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the rollback and the tick of the last commit of a 3D partition with other threads:
 * a claim or a move of the tick that arrives during a rollback waits for it, the health and
 * the ownership stay readable meanwhile, and the tick of the last commit waits for a commit
 * that is running. The index of the tests stops the restore or the commit at a gate, and the
 * lock of the partition tells when another thread waits for it, so the tests need no sleep.
 */
class PartitionRecoveryConcurrency3Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long[] IDS = {1, 2};

    private final GatedIndex3[] index = new GatedIndex3[1];
    private final ObservedLock lock = new ObservedLock();
    private LocalPartitionNode3 node;
    private WriteToken token;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode3(MonotonicClock.SYSTEM,
                (indexConfig, retainedTicks, clock, tick) -> new PartitionStorage3(indexConfig.limits(),
                        slots -> index[0] = new GatedIndex3(SpatialIndexes.create(indexConfig, slots)),
                        retainedTicks, clock, tick, lock, new CoordinateBufferPool()));
        node.createPartition(P1, nameOf(P1));
        token = node.claim(P1, 0).orElseThrow();
    }

    @Test
    void claimThatArrivesDuringARollbackWaitsForIt() throws Exception {
        writes(node, token).setAll(List.of(entity(1, 1, 1, 1)));
        writes(node, token).commit();
        writes(node, token).setAll(List.of(entity(2, 2, 2, 2)));
        Gate gate = new Gate();
        index[0].restoreGate = gate;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<WriteToken> claimed = new AtomicReference<>();
        Thread writer = worker(() -> writes(node, token).rollback(), failure);
        Thread claimer = worker(() -> claimed.set(node.claim(P1, token.generation()).orElseThrow()), failure);
        writer.start();
        try {
            gate.awaitEntered();
            // The health and the ownership take no lock: they stay readable during the restore.
            assertSame(PartitionHealth.HEALTHY, node.health(P1));
            assertEquals(new Ownership(1, true), node.ownership(P1));
            claimer.start();
            // The claim waits for the lock of the partition, which the rollback holds.
            lock.awaitWaiting();
        } finally {
            gate.open();
            join(writer);
            join(claimer);
        }
        if (failure.get() != null) {
            throw new AssertionError("rollback or claim failed", failure.get());
        }
        assertThrows(OwnershipChangedException.class, () -> writes(node, token).rollback());
        index[0].restoreGate = null;
        writes(node, claimed.get()).commit();
        node.setTick(1);
        assertEquals(List.of(entity(1, 1, 1, 1)), node.getAll(node.acquireLatest(P1, defaultTimeout()), IDS));
    }

    @Test
    void moveOfTheTickThatArrivesDuringARollbackPublishesTheLastCommit() throws Exception {
        writes(node, token).setAll(List.of(entity(1, 1, 1, 1)));
        writes(node, token).commit();
        writes(node, token).setAll(List.of(entity(2, 2, 2, 2)));
        Gate gate = new Gate();
        index[0].restoreGate = gate;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = worker(() -> writes(node, token).rollback(), failure);
        Thread ticker = worker(() -> node.setTick(1), failure);
        writer.start();
        try {
            gate.awaitEntered();
            ticker.start();
            // The move of the tick waits for the lock of the partition, which the rollback holds.
            lock.awaitWaiting();
        } finally {
            gate.open();
            join(writer);
            join(ticker);
        }
        if (failure.get() != null) {
            throw new AssertionError("rollback or move of the tick failed", failure.get());
        }
        assertEquals(List.of(entity(1, 1, 1, 1)), node.getAll(node.acquireLatest(P1, defaultTimeout()), IDS));
        assertEquals(0, node.lastCommitTick(token));
    }

    @Test
    void tickOfTheLastCommitWaitsForTheCommitThatIsRunning() throws Exception {
        node.setTick(1);
        writes(node, token).setAll(List.of(entity(1, 1, 1, 1)));
        Gate gate = new Gate();
        index[0].commitGate = gate;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong lastCommitTick = new AtomicLong(-1);
        Thread writer = worker(() -> writes(node, token).commit(), failure);
        Thread reader = worker(() -> lastCommitTick.set(node.lastCommitTick(token)), failure);
        writer.start();
        try {
            gate.awaitEntered();
            reader.start();
            // The read waits for the lock of the partition, which the commit holds.
            lock.awaitWaiting();
        } finally {
            gate.open();
            join(writer);
            join(reader);
        }
        if (failure.get() != null) {
            throw new AssertionError("commit or read failed", failure.get());
        }
        assertEquals(1, lastCommitTick.get());
    }

    /**
     * A writer is stopped inside its commit when a coordinator takes the partition: the commit
     * ends before the claim, and the new owner sees that the current tick has a commit.
     */
    @Test
    void newOwnerSeesTheCommitThatWasRunningWhenItTookThePartition() throws Exception {
        node.setTick(1);
        writes(node, token).setAll(List.of(entity(1, 1, 1, 1)));
        Gate gate = new Gate();
        index[0].commitGate = gate;
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
        index[0].commitGate = null;
        assertEquals(node.tick(), node.lastCommitTick(claimed.get()));
        node.setTick(2);
        assertEquals(List.of(entity(1, 1, 1, 1)), node.getAll(node.acquireLatest(P1, defaultTimeout()), IDS));
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
