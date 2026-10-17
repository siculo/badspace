package badspace.service.partition;

import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.common.partition.Entity3.entity;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Box3;
import badspace.common.geometry.Point3;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.Ownership;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionId;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.WriteToken;
import badspace.service.index.SpatialIndexes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Checks the ownership of a partition on the node: generation, claim, release, the discarded
 * writes and the writes with a token that is not current, also from other threads.
 */
class PartitionOwnership3Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);
    private static final List<IndexConfig> CONFIGS = List.of(
            IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4));
    private static final Box3 EVERYWHERE = new Box3(new Point3(-1000, -1000, -1000), new Point3(1000, 1000, 1000));
    /** More than the IDs that the tests use, so that content() reads all the entities. */
    private static final int MAX_ENTITY_ID = 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private LocalPartitionNode3 node;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode3();
        node.createPartition(P1);
    }

    @Test
    void newPartitionHasNoOwnerAtGenerationZero() {
        assertEquals(new Ownership(0, false), node.ownership(P1));
    }

    @Test
    void claimOfAFreePartitionGivesTheTokenOfTheNextGeneration() {
        assertEquals(Optional.of(new WriteToken(P1, 1)), node.claim(P1, 0));
        assertEquals(new Ownership(1, true), node.ownership(P1));
    }

    @Test
    void claimWithAnOldOrAFutureGenerationFailsAndChangesNothing() {
        node.claim(P1, 0).orElseThrow();
        assertEquals(Optional.empty(), node.claim(P1, 0));
        assertEquals(Optional.empty(), node.claim(P1, 5));
        assertEquals(new Ownership(1, true), node.ownership(P1));
    }

    @Test
    void claimWithTheGenerationOfTheOwnerGivesANewTokenAndTheOldOneCannotClaimAgain() {
        // The same call passes the ownership, if the owner makes it, or takes it away, if
        // someone else makes it: the node does not know who calls.
        WriteToken first = node.claim(P1, 0).orElseThrow();
        WriteToken second = node.claim(P1, first.generation()).orElseThrow();
        assertEquals(new WriteToken(P1, 2), second);
        assertEquals(new Ownership(2, true), node.ownership(P1));
        assertEquals(Optional.empty(), node.claim(P1, first.generation()));
    }

    @Test
    void releaseLeavesThePartitionWithoutOwnerAtTheNextGeneration() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.release(token);
        assertEquals(new Ownership(2, false), node.ownership(P1));
        assertEquals(Optional.of(new WriteToken(P1, 3)), node.claim(P1, 2));
    }

    @Test
    void releaseIsIdempotentAndIgnoresTokensThatAreNotCurrent() {
        WriteToken first = node.claim(P1, 0).orElseThrow();
        WriteToken second = node.claim(P1, first.generation()).orElseThrow();
        node.release(first);
        assertEquals(new Ownership(2, true), node.ownership(P1));
        node.release(second);
        node.release(second);
        assertEquals(new Ownership(3, false), node.ownership(P1));
        // A token with the generation of a free partition names no owner.
        node.release(new WriteToken(P1, 3));
        assertEquals(new Ownership(3, false), node.ownership(P1));
    }

    @Test
    void claimDiscardsTheWritesNotCommittedAndKeepsTheCommits() {
        for (IndexConfig config : CONFIGS) {
            setUp();
            node.createPartition(P2, config);
            WriteToken first = node.claim(P2, 0).orElseThrow();
            node.insertAll(first, List.of(entity(1, 1, 1, 1), entity(2, 2, 2, 2), entity(3, 3, 3, 3)));
            node.commit(first);
            node.insertAll(first, List.of(entity(4, 4, 4, 4)));
            node.updateAll(first, List.of(entity(1, 9, 9, 9)));
            node.removeAll(first, new long[] {2});
            WriteToken second = node.claim(P2, first.generation()).orElseThrow();
            assertEquals(List.of(entity(1, 1, 1, 1), entity(2, 2, 2, 2), entity(3, 3, 3, 3)), content(publish(second)),
                    config.toString());
        }
    }

    @Test
    void releaseDiscardsTheWritesNotCommitted() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.insertAll(token, List.of(entity(1, 1, 1, 1)));
        node.commit(token);
        node.updateAll(token, List.of(entity(1, 5, 5, 5)));
        node.insertAll(token, List.of(entity(2, 2, 2, 2)));
        // The owner can decide not to commit and leave the ownership.
        node.release(token);
        WriteToken next = node.claim(P1, node.ownership(P1).generation()).orElseThrow();
        assertEquals(List.of(entity(1, 1, 1, 1)), content(publish(next)));
    }

    @Test
    void discardBeforeTheFirstCommitLeavesThePartitionEmpty() {
        WriteToken first = node.claim(P1, 0).orElseThrow();
        node.insertAll(first, List.of(entity(1, 1, 1, 1), entity(2, 2, 2, 2)));
        WriteToken second = node.claim(P1, first.generation()).orElseThrow();
        assertEquals(List.of(), content(publish(second)));
    }

    @Test
    void newOwnerStartsFromTheCommitsOfTheCurrentTick() {
        WriteToken first = node.claim(P1, 0).orElseThrow();
        node.insertAll(first, List.of(entity(1, 1, 1, 1)));
        node.commit(first);
        WriteToken second = node.claim(P1, first.generation()).orElseThrow();
        node.insertAll(second, List.of(entity(2, 2, 2, 2)));
        node.commit(second);
        node.setTick(1);
        // Both commits are of tick 0: the last one has the state confirmed by the first.
        Acquisition acquisition = node.acquire(P1, 0, defaultTimeout());
        assertEquals(List.of(entity(1, 1, 1, 1), entity(2, 2, 2, 2)), content(acquisition));
    }

    @Test
    void discardAfterRandomWritesGivesTheCommittedStateAndTheIndexStaysConsistent() {
        for (IndexConfig config : CONFIGS) {
            setUp();
            node.createPartition(P2, config);
            WriteToken token = node.claim(P2, 0).orElseThrow();
            Random random = new Random(42);
            Map<Long, Entity3> model = new LinkedHashMap<>();
            long nextId = 1;
            for (int round = 0; round < 3; round++) {
                nextId = writeRandomly(token, random, model, nextId, 200);
                node.commit(token);
                // Writes that the claim discards; the next round uses new IDs after them.
                nextId = writeRandomly(token, random, new LinkedHashMap<>(model), nextId, 100);
                token = node.claim(P2, token.generation()).orElseThrow();
                Acquisition acquisition = publish(token);
                String where = config + ", round " + round;
                assertEquals(sorted(model.values()), content(acquisition), where);
                assertEquals(sorted(model.values()), sorted(node.findInRegion(acquisition, EVERYWHERE)), where);
            }
        }
    }

    @Test
    void negativeExpectedGenerationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> node.claim(P1, -1));
        assertEquals(new Ownership(0, false), node.ownership(P1));
    }

    @Test
    void claimAfterTheRemovalIsRejectedAndReleaseDoesNothing() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.removePartition(token);
        assertThrows(IllegalStateException.class, () -> node.claim(P1, token.generation()));
        node.release(token);
        assertEquals(new Ownership(1, true), node.ownership(P1));
        node.setTick(1);
        assertThrows(IllegalStateException.class, () -> node.claim(P1, token.generation()));
        assertEquals(new Ownership(1, true), node.ownership(P1));
    }

    @Test
    void destroyedPartitionIsLikeAnUnknownOne() {
        node.setTick(1);
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.removePartition(token);
        node.setTick(2);
        node.setTick(3);
        assertThrows(IllegalArgumentException.class, () -> node.ownership(P1));
        assertThrows(IllegalArgumentException.class, () -> node.claim(P1, 1));
        assertDoesNotThrow(() -> node.release(token));
        assertThrows(IllegalArgumentException.class, () -> node.ownership(P2));
        assertThrows(IllegalArgumentException.class, () -> node.claim(P2, 0));
        assertDoesNotThrow(() -> node.release(new WriteToken(P2, 1)));
    }

    @Test
    void writesCommitsAndRemovalsWithAnOldTokenAreRejectedAndChangeNothing() {
        WriteToken old = node.claim(P1, 0).orElseThrow();
        node.insertAll(old, List.of(entity(1, 1, 1, 1)));
        node.commit(old);
        WriteToken current = node.claim(P1, old.generation()).orElseThrow();
        assertOwnershipChanged(() -> node.insertAll(old, List.of(entity(2, 2, 2, 2))));
        assertOwnershipChanged(() -> node.updateAll(old, List.of(entity(1, 5, 5, 5))));
        assertOwnershipChanged(() -> node.removeAll(old, new long[] {1}));
        assertOwnershipChanged(() -> node.commit(old));
        assertOwnershipChanged(() -> node.removePartition(old));
        assertOwnershipChanged(() -> node.dropPartition(old));
        assertEquals(new Ownership(2, true), node.ownership(P1));
        assertEquals(List.of(entity(1, 1, 1, 1)), content(publish(current)));
    }

    @Test
    void writesWithoutAnOwnerAreRejected() {
        assertOwnershipChanged(() -> node.insertAll(new WriteToken(P1, 1), List.of(entity(1, 1, 1, 1))));
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.release(token);
        assertOwnershipChanged(() -> node.insertAll(token, List.of(entity(1, 1, 1, 1))));
        assertOwnershipChanged(() -> node.commit(token));
        // A token that names the free state is not a token of an owner either.
        assertOwnershipChanged(() -> node.commit(new WriteToken(P1, 2)));
    }

    @Test
    void removalOfAPartitionThatWasRemovedIsRejectedBeforeTheToken() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.removePartition(token);
        assertThrows(IllegalStateException.class, () -> node.insertAll(token, List.of(entity(1, 1, 1, 1))));
        assertThrows(IllegalStateException.class, () -> node.commit(new WriteToken(P1, 7)));
    }

    @Test
    void commitThatIsRunningWhenTheClaimArrivesIsConfirmed() throws Exception {
        for (IndexConfig config : CONFIGS) {
            GatedIndex3[] index = new GatedIndex3[1];
            ObservedLock lock = new ObservedLock();
            LocalPartitionNode3 gated = gatedNode(index, lock);
            gated.createPartition(P1, config, 1);
            WriteToken old = gated.claim(P1, 0).orElseThrow();
            gated.insertAll(old, List.of(entity(1, 1, 1, 1)));
            Gate gate = new Gate();
            index[0].commitGate = gate;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<WriteToken> claimed = new AtomicReference<>();
            Thread writer = worker(() -> gated.commit(old), failure);
            Thread claimer = worker(() -> claimed.set(gated.claim(P1, old.generation()).orElseThrow()), failure);
            writer.start();
            try {
                gate.awaitEntered();
                claimer.start();
                // The claim waits for the lock of the partition, which the commit holds.
                lock.awaitWaiting();
            } finally {
                gate.open();
                join(writer);
                join(claimer);
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": commit or claim failed", failure.get());
            }
            // The commit ended before the claim: its write stays, and the old token is rejected.
            assertOwnershipChanged(() -> gated.commit(old));
            index[0].commitGate = null;
            gated.commit(claimed.get());
            gated.setTick(1);
            assertEquals(List.of(entity(1, 1, 1, 1)),
                    gated.getAll(gated.acquireLatest(P1, defaultTimeout()), new long[] {1}), config.toString());
        }
    }

    @Test
    void ofManyClaimsWithTheSameGenerationOnlyOneWins() throws Exception {
        int threads = 8;
        WriteToken token = node.claim(P1, 0).orElseThrow();
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> claimers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            claimers.add(worker(() -> {
                await(start);
                if (node.claim(P1, token.generation()).isPresent()) {
                    winners.incrementAndGet();
                }
            }, failure));
        }
        claimers.forEach(Thread::start);
        for (Thread claimer : claimers) {
            join(claimer);
        }
        if (failure.get() != null) {
            throw new AssertionError("a claim failed", failure.get());
        }
        assertEquals(1, winners.get());
        assertEquals(new Ownership(2, true), node.ownership(P1));
    }

    @Test
    void releaseDuringTheWritesKeepsExactlyTheCommitsThatEndedBefore() throws Exception {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.insertAll(token, List.of(entity(1, 0, 0, 0)));
        node.commit(token);
        CountDownLatch someCommits = new CountDownLatch(10);
        AtomicLong lastCommitted = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // The writer moves entity 1 to a new position at each step and commits it, until the
        // release reaches it: the last commit that ended is the one the next owner sees.
        Thread writer = worker(() -> {
            try {
                for (long step = 1; ; step++) {
                    node.updateAll(token, List.of(entity(1, step, step, step)));
                    node.commit(token);
                    lastCommitted.set(step);
                    someCommits.countDown();
                }
            } catch (OwnershipChangedException expected) {
                // The writer lost the ownership: it stops.
            }
        }, failure);
        writer.start();
        assertTrue(someCommits.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the writer made no progress");
        node.release(token);
        join(writer);
        if (failure.get() != null) {
            throw new AssertionError("the writer failed", failure.get());
        }
        WriteToken next = node.claim(P1, node.ownership(P1).generation()).orElseThrow();
        long step = lastCommitted.get();
        assertEquals(List.of(entity(1, step, step, step)), content(publish(next)));
    }

    @Test
    void moveOfTheTickDuringClaimsLeavesTheOwnershipAndTheTicksConsistent() throws Exception {
        WriteToken first = node.claim(P1, 0).orElseThrow();
        node.insertAll(first, List.of(entity(1, 1, 1, 1)));
        node.commit(first);
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<WriteToken> last = new AtomicReference<>(first);
        Thread claimer = worker(() -> {
            await(start);
            for (int i = 0; i < 100; i++) {
                last.set(node.claim(P1, last.get().generation()).orElseThrow());
            }
        }, failure);
        Thread mover = worker(() -> {
            await(start);
            for (long tick = 1; tick <= 100; tick++) {
                node.setTick(tick);
            }
        }, failure);
        claimer.start();
        mover.start();
        join(claimer);
        join(mover);
        if (failure.get() != null) {
            throw new AssertionError("a claim or a move of the tick failed", failure.get());
        }
        assertEquals(new Ownership(101, true), node.ownership(P1));
        assertEquals(100, node.tick(P1));
        assertEquals(List.of(entity(1, 1, 1, 1)), content(publish(last.get())));
    }

    @Test
    void releaseThatArrivesDuringACommitWaitsAndTheCommitIsConfirmed() throws Exception {
        for (IndexConfig config : CONFIGS) {
            GatedIndex3[] index = new GatedIndex3[1];
            ObservedLock lock = new ObservedLock();
            LocalPartitionNode3 gated = gatedNode(index, lock);
            gated.createPartition(P1, config, 1);
            WriteToken old = gated.claim(P1, 0).orElseThrow();
            gated.insertAll(old, List.of(entity(1, 1, 1, 1)));
            Gate gate = new Gate();
            index[0].commitGate = gate;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread writer = worker(() -> gated.commit(old), failure);
            Thread releaser = worker(() -> gated.release(old), failure);
            writer.start();
            try {
                gate.awaitEntered();
                releaser.start();
                // The release waits for the lock of the partition, which the commit holds.
                lock.awaitWaiting();
                assertEquals(new Ownership(1, true), gated.ownership(P1), config.toString());
            } finally {
                gate.open();
                join(writer);
                join(releaser);
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": commit or release failed", failure.get());
            }
            // The commit ended before the release: its write stays, and the old token is rejected.
            assertEquals(new Ownership(2, false), gated.ownership(P1), config.toString());
            assertOwnershipChanged(() -> gated.commit(old));
            WriteToken next = gated.claim(P1, 2).orElseThrow();
            gated.commit(next);
            gated.setTick(1);
            assertEquals(List.of(entity(1, 1, 1, 1)),
                    gated.getAll(gated.acquireLatest(P1, defaultTimeout()), new long[] {1}), config.toString());
        }
    }

    @Test
    void snapshotAcquiredBeforeTheChangeOfOwnerKeepsItsContent() {
        for (boolean release : new boolean[] {false, true}) {
            for (IndexConfig config : CONFIGS) {
                setUp();
                node.createPartition(P2, config);
                WriteToken first = node.claim(P2, 0).orElseThrow();
                Random random = new Random(7);
                Map<Long, Entity3> model = new LinkedHashMap<>();
                long nextId = writeRandomly(first, random, model, 1, 200);
                Acquisition before = publish(first);
                List<Entity3> expected = sorted(model.values());
                List<Entity3> nearest = node.findNearest(before, new Point3(0, 0, 0), 10);
                // Writes not committed, which the change of owner discards.
                nextId = writeRandomly(first, random, new LinkedHashMap<>(model), nextId, 100);
                WriteToken second;
                if (release) {
                    node.release(first);
                    second = node.claim(P2, node.ownership(P2).generation()).orElseThrow();
                } else {
                    second = node.claim(P2, first.generation()).orElseThrow();
                }
                // The new owner writes and publishes some ticks: with k = 1 the old snapshot leaves
                // the window, and the snapshots that end give their buffers back for the next commits.
                for (int tick = 0; tick < 3; tick++) {
                    nextId = writeRandomly(second, random, model, nextId, 100);
                    node.release(publish(second));
                }
                String where = config + (release ? ", release" : ", claim");
                assertThrows(SnapshotNotAvailableException.class,
                        () -> node.acquire(P2, before.tick(), defaultTimeout()), where);
                assertEquals(expected, content(before), where);
                assertEquals(expected, sorted(node.findInRegion(before, EVERYWHERE)), where);
                assertEquals(nearest, node.findNearest(before, new Point3(0, 0, 0), 10), where);
            }
        }
    }

    /**
     * Returns a node whose partitions have the index behind a gate, put in index[0], and the
     * given lock, which tells the test when a thread waits for it.
     */
    private static LocalPartitionNode3 gatedNode(GatedIndex3[] index, ObservedLock lock) {
        return new LocalPartitionNode3(MonotonicClock.SYSTEM,
                (indexConfig, retainedTicks, clock, tick) -> new PartitionStorage3(indexConfig.limits(),
                        slots -> index[0] = new GatedIndex3(SpatialIndexes.create(indexConfig, slots)),
                        retainedTicks, clock, tick, lock, new ConcurrentLinkedDeque<>()));
    }

    /**
     * Makes count random writes on P2 with the token and applies them to the model too:
     * insertions with new IDs from nextId, moves and removals. Returns the next free ID.
     */
    private long writeRandomly(WriteToken token, Random random, Map<Long, Entity3> model, long nextId, int count) {
        for (int i = 0; i < count; i++) {
            int choice = model.isEmpty() ? 0 : random.nextInt(3);
            if (choice == 0) {
                Entity3 entity = new Entity3(nextId++, randomPoint(random));
                node.insertAll(token, List.of(entity));
                model.put(entity.id(), entity);
            } else {
                long entityId = new ArrayList<>(model.keySet()).get(random.nextInt(model.size()));
                if (choice == 1) {
                    Entity3 entity = new Entity3(entityId, randomPoint(random));
                    node.updateAll(token, List.of(entity));
                    model.put(entityId, entity);
                } else {
                    node.removeAll(token, new long[] {entityId});
                    model.remove(entityId);
                }
            }
        }
        return nextId;
    }

    private static Point3 randomPoint(Random random) {
        return new Point3(random.nextDouble(-100, 100), random.nextDouble(-100, 100),
                random.nextDouble(-100, 100));
    }

    /** Returns the entities of the snapshot, sorted by ID. */
    private List<Entity3> content(Acquisition acquisition) {
        long[] entityIds = new long[MAX_ENTITY_ID];
        for (int i = 0; i < entityIds.length; i++) {
            entityIds[i] = i + 1;
        }
        return node.getAll(acquisition, entityIds);
    }

    private static List<Entity3> sorted(Iterable<Entity3> entities) {
        List<Entity3> result = new ArrayList<>();
        entities.forEach(result::add);
        result.sort(Comparator.comparingLong(Entity3::id));
        return result;
    }

    /**
     * Commits the writes of the owner of the token, moves the tick of the node by one and
     * acquires the snapshot just published.
     */
    private Acquisition publish(WriteToken token) {
        node.commit(token);
        node.setTick(node.tick() + 1);
        return node.acquireLatest(token.partitionId(), defaultTimeout());
    }

    private static void assertOwnershipChanged(Executable write) {
        assertThrows(OwnershipChangedException.class, write);
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

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
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
