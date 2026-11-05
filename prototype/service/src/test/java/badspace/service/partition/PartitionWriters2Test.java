package badspace.service.partition;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.service.partition.Names.nameOf;
import static badspace.service.partition.Owners.owner;
import static badspace.service.partition.Owners.writes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.index.SpatialIndexes;
import badspace.service.partition.slots.CoordinateBufferPool;
import badspace.service.partition.snapshot.MonotonicClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Writers of different partitions of the same node, each on its own thread, while the tick
 * moves on another thread and readers acquire the snapshots. The tests use latches and
 * barriers, not sleeps; a writer that waits for another partition fails the test after a
 * timeout instead of hanging.
 */
class PartitionWriters2Test {

    private static final List<IndexConfig> CONFIGS = List.of(
            IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4));
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Point2 A = point(1, 2);
    private static final Point2 B = point(3, 4);
    private static final Point2 C = point(5, 6);
    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);
    private static final int WRITERS = 4;
    private static final int READERS = 3;
    /** The commits of each writer: positionAt() keeps x below 1000. */
    private static final int VERSIONS = 999;
    private static final int ENTITIES = 4;
    /** The distance in y between the entities of two partitions. */
    private static final int PARTITION_ROW = 100;

    /**
     * The commit of P1 is stopped in the index, while it holds the lock of P1, and a move of
     * the tick waits for it. The writer of P2 writes and commits without waiting, and the
     * commit of P1 ends in the snapshot of the tick that ends.
     */
    @Test
    void moveOfTheTickThatWaitsForAPartitionDoesNotStopTheWritersOfTheOthers() throws Exception {
        for (IndexConfig config : CONFIGS) {
            // The index and the lock of each partition, in the order of creation.
            List<GatedIndex2> indexes = new ArrayList<>();
            List<ObservedLock> locks = new ArrayList<>();
            LocalPartitionNode2 node = new LocalPartitionNode2(MonotonicClock.SYSTEM, (index, retainedTicks, clock, tick) -> {
                ObservedLock lock = new ObservedLock();
                locks.add(lock);
                return new PartitionStorage2(index.limits(), slots -> {
                    GatedIndex2 gated = new GatedIndex2(SpatialIndexes.create(index, slots));
                    indexes.add(gated);
                    return gated;
                }, retainedTicks, clock, tick, lock, new CoordinateBufferPool());
            });
            node.createPartition(P1, nameOf(P1), config, 1);
            node.createPartition(P2, nameOf(P2), config, 1);
            writes(node, P1).setAll(List.of(new Entity2(10, A)));
            Gate gate = new Gate();
            indexes.getFirst().commitGate = gate;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread writer = worker(() -> writes(node, P1).commit(), failure);
            Thread mover = worker(() -> node.setTick(1), failure);
            writer.start();
            try {
                gate.awaitEntered();
                mover.start();
                // The move of the tick waits for the lock of P1, so it holds no other lock of a partition.
                locks.getFirst().awaitWaiting();

                assertTimeoutPreemptively(TIMEOUT, () -> {
                    writes(node, P2).setAll(List.of(new Entity2(20, B)));
                    writes(node, P2).setAll(List.of(new Entity2(20, C)));
                    writes(node, P2).commit();
                }, config + ": the writer of P2 waited for P1");

            } finally {
                gate.open();
                join(writer);
                join(mover);
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": commit or tick worker failed", failure.get());
            }
            assertEquals(1, node.tick(P1), config.toString());
            assertEquals(List.of(new Entity2(10, A)), read(node, P1, 0, 10), config.toString());
            // The commit of P2 is in the snapshot of tick 0 or of tick 1, depending on when the
            // move reached P2; after the next move it is published in any case.
            node.setTick(2);
            assertEquals(List.of(new Entity2(20, C)), read(node, P2, 1, 20), config.toString());
        }
    }

    /**
     * The commit of P1 is stopped in the index, while it holds the lock of P1. A move of the
     * tick moves at once the other partitions, whose lock is free, and then waits only for P1,
     * which moves when its commit ends.
     */
    @Test
    void moveOfTheTickMovesTheFreePartitionsWhileItWaitsForABusyOne() throws Exception {
        List<GatedIndex2> indexes = new ArrayList<>();
        List<ObservedLock> locks = new ArrayList<>();
        LocalPartitionNode2 node = new LocalPartitionNode2(MonotonicClock.SYSTEM, (index, retainedTicks, clock, tick) -> {
            ObservedLock lock = new ObservedLock();
            locks.add(lock);
            return new PartitionStorage2(index.limits(), slots -> {
                GatedIndex2 gated = new GatedIndex2(SpatialIndexes.create(index, slots));
                indexes.add(gated);
                return gated;
            }, retainedTicks, clock, tick, lock, new CoordinateBufferPool());
        });
        PartitionId p3 = new PartitionId(3);
        node.createPartition(P1, nameOf(P1));
        node.createPartition(P2, nameOf(P2));
        node.createPartition(p3, nameOf(p3));
        writes(node, P1).setAll(List.of(new Entity2(10, A)));
        Gate gate = new Gate();
        indexes.getFirst().commitGate = gate;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = worker(() -> writes(node, P1).commit(), failure);
        Thread mover = worker(() -> node.setTick(1), failure);
        writer.start();
        try {
            gate.awaitEntered();
            mover.start();
            // The move of the tick waits for P1 only after it moved the free partitions.
            locks.getFirst().awaitWaiting();
            assertEquals(1, node.tick(P2));
            assertEquals(1, node.tick(p3));
            assertEquals(0, node.tick(P1));
        } finally {
            gate.open();
            join(writer);
            join(mover);
        }
        if (failure.get() != null) {
            throw new AssertionError("commit or tick worker failed", failure.get());
        }
        assertEquals(1, node.tick(P1));
        assertEquals(List.of(new Entity2(10, A)), read(node, P1, 0, 10));
    }

    /**
     * The commits of P1 and P2 are stopped in the index, while they hold their locks, and a
     * move of the tick moves at once P3, which is free, and then waits for one of the two
     * busy partitions. The other one ends its commit while the move waits: it stays at the old
     * tick until the commit of the first one ends, because the move tries it again only then.
     * At the end all the partitions are at the new tick.
     */
    @Test
    void busyPartitionThatEndsItsCommitWaitsForTheOneThatTheMoveWaitsFor() throws Exception {
        List<GatedIndex2> indexes = new ArrayList<>();
        List<ObservedLock> locks = new ArrayList<>();
        LocalPartitionNode2 node = new LocalPartitionNode2(MonotonicClock.SYSTEM, (index, retainedTicks, clock, tick) -> {
            ObservedLock lock = new ObservedLock();
            locks.add(lock);
            return new PartitionStorage2(index.limits(), slots -> {
                GatedIndex2 gated = new GatedIndex2(SpatialIndexes.create(index, slots));
                indexes.add(gated);
                return gated;
            }, retainedTicks, clock, tick, lock, new CoordinateBufferPool());
        });
        PartitionId p3 = new PartitionId(3);
        List<PartitionId> busy = List.of(P1, P2);
        node.createPartition(P1, nameOf(P1));
        node.createPartition(P2, nameOf(P2));
        node.createPartition(p3, nameOf(p3));
        List<Gate> gates = List.of(new Gate(), new Gate());
        List<Thread> writers = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < busy.size(); i++) {
            PartitionId partitionId = busy.get(i);
            writes(node, partitionId).setAll(List.of(new Entity2(10, A)));
            indexes.get(i).commitGate = gates.get(i);
            writers.add(worker(() -> writes(node, partitionId).commit(), failure));
        }
        Thread mover = worker(() -> node.setTick(1), failure);
        try {
            for (int i = 0; i < busy.size(); i++) {
                writers.get(i).start();
                gates.get(i).awaitEntered();
            }
            mover.start();
            // The move waits for the lock of one of the two busy partitions: the other one is
            // the one that ends its commit first.
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!locks.get(0).waited() && !locks.get(1).waited()) {
                assertTrue(System.nanoTime() < deadline, "the move of the tick did not wait for a partition");
                Thread.onSpinWait();
            }
            int waitedFor = locks.get(0).waited() ? 0 : 1;
            int other = 1 - waitedFor;
            assertEquals(1, node.tick(p3));
            gates.get(other).open();
            join(writers.get(other));
            assertEquals(0, node.tick(busy.get(other)), "the move moved a partition before the one it waits for");
            assertEquals(0, node.tick(busy.get(waitedFor)));
        } finally {
            gates.forEach(Gate::open);
            writers.forEach(writer -> {
                try {
                    join(writer);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            join(mover);
        }
        if (failure.get() != null) {
            throw new AssertionError("commit or tick worker failed", failure.get());
        }
        assertEquals(1, node.tick(P1));
        assertEquals(1, node.tick(P2));
        assertEquals(1, node.tick(p3));
    }

    /**
     * Each writer moves the entities of its partition at each commit, and removes and inserts
     * one again at each even commit; the tick moves on another thread. The entities of all
     * the partitions have the same IDs but a different row in y, so a snapshot with an entity
     * of another partition, or with a mix of two commits, fails the test. A barrier in the
     * middle makes all the writers work at the same time.
     */
    @Test
    void writersOnTheirOwnThreadsPublishOnlyTheWholeCommitsOfTheirPartition() throws Exception {
        LocalPartitionNode2 node = new LocalPartitionNode2();
        PartitionId[] partitionIds = new PartitionId[WRITERS];
        long[] entityIds = new long[ENTITIES];
        for (int i = 0; i < ENTITIES; i++) {
            entityIds[i] = i + 1;
        }
        for (int w = 0; w < WRITERS; w++) {
            partitionIds[w] = new PartitionId(w + 1);
            node.createPartition(partitionIds[w], nameOf(partitionIds[w]), CONFIGS.get(w % CONFIGS.size()), 2);
            writes(node, partitionIds[w]).setAll(entitiesAt(w, 0, entityIds));
        }

        CountDownLatch observed = new CountDownLatch(READERS);
        CyclicBarrier middle = new CyclicBarrier(WRITERS);
        CyclicBarrier start = new CyclicBarrier(WRITERS + READERS + 2);
        CountDownLatch writersDone = new CountDownLatch(WRITERS);
        AtomicBoolean done = new AtomicBoolean();
        AtomicLong lastTick = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> writers = new ArrayList<>();
        List<Thread> others = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) {
            int partition = w;
            writers.add(new Thread(() -> {
                try {
                    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    PartitionId partitionId = partitionIds[partition];
                    for (long version = 1; version <= VERSIONS; version++) {
                        List<Entity2> moved = entitiesAt(partition, version, entityIds);
                        if (version % 2 == 0) {
                            // A removal and an insertion: the IDs change in this commit.
                            writes(node, partitionId).removeAll(new long[] {entityIds[ENTITIES - 1]});
                            writes(node, partitionId).setAll(moved.subList(ENTITIES - 1, ENTITIES));
                            writes(node, partitionId).setAll(moved.subList(0, ENTITIES - 1));
                        } else {
                            // Two calls make one batch: the commit sends it to the node whole.
                            writes(node, partitionId).setAll(moved.subList(0, ENTITIES / 2));
                            writes(node, partitionId).setAll(moved.subList(ENTITIES / 2, ENTITIES));
                        }
                        writes(node, partitionId).commit();
                        if (version == VERSIONS / 2) {
                            middle.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                            await(observed);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    writersDone.countDown();
                }
            }));
        }
        for (int r = 0; r < READERS; r++) {
            others.add(new Thread(() -> {
                try {
                    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    long[] lastVersions = new long[WRITERS];
                    int seen = 0;
                    boolean counted = false;
                    for (int round = 0; !done.get(); round++) {
                        int partition = round % WRITERS;
                        Acquisition acquisition;
                        try {
                            acquisition = node.acquireLatest(partitionIds[partition], defaultTimeout());
                        } catch (SnapshotNotAvailableException e) {
                            // No tick has ended yet.
                            continue;
                        }
                        long version;
                        try {
                            version = versionOf(node, acquisition, partition, entityIds);
                        } finally {
                            node.release(acquisition);
                        }
                        // The latest snapshot of a partition never goes back.
                        assertTrue(version >= lastVersions[partition],
                                "partition " + partition + " went back to an older commit");
                        if (version > 0 && lastVersions[partition] == 0) {
                            seen++;
                        }
                        lastVersions[partition] = version;
                        if (seen == WRITERS && !counted) {
                            observed.countDown();
                            counted = true;
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }
        others.add(new Thread(() -> {
            try {
                start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                long tick = 0;
                while (writersDone.getCount() > 0 && !done.get()) {
                    node.setTick(++tick);
                }
                lastTick.set(tick);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }));
        List<Thread> threads = new ArrayList<>(writers);
        threads.addAll(others);
        for (Thread thread : threads) {
            thread.start();
        }

        try {
            start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            // The writers and the mover end by themselves; then the readers stop.
            for (Thread writer : writers) {
                join(writer);
            }
            join(others.getLast());
        } finally {
            done.set(true);
            for (Thread thread : threads) {
                join(thread);
            }
        }
        if (failure.get() != null) {
            throw new AssertionError("A thread failed", failure.get());
        }
        // The next move publishes the last commits, if they are still waiting.
        node.setTick(lastTick.get() + 1);
        for (int w = 0; w < WRITERS; w++) {
            Acquisition latest = node.acquireLatest(partitionIds[w], defaultTimeout());
            try {
                assertEquals(VERSIONS, versionOf(node, latest, w, entityIds), "the last commit of partition " + w + " was lost");
            } finally {
                node.release(latest);
            }
        }
    }

    /** The entities of a partition at a commit: all of them at x = version, each partition in its own row. */
    private static List<Entity2> entitiesAt(int partition, long version, long[] entityIds) {
        List<Entity2> entities = new ArrayList<>();
        for (long id : entityIds) {
            entities.add(new Entity2(id, positionAt(partition, version, id)));
        }
        return entities;
    }

    private static Point2 positionAt(int partition, long version, long entityId) {
        return point(version % 1000, partition * PARTITION_ROW + entityId);
    }

    /**
     * Returns the number of the commit in the acquired snapshot of a partition: the x of all
     * its entities, or 0 for the empty snapshot before the first commit. Fails if the
     * entities do not all have the same x, or are not in the row of the partition, in the
     * slots or in the index.
     */
    private static long versionOf(LocalPartitionNode2 node, Acquisition acquisition, int partition, long[] entityIds) {
        List<Entity2> byId = node.getAll(acquisition, entityIds);
        // A region with the rows of all the partitions, so it would find the entities of another one.
        Box2 all = new Box2(point(-1, -1), point(1000, WRITERS * PARTITION_ROW));
        List<Entity2> inRegion = node.findInRegion(acquisition, all);
        String where = "partition " + partition + " at tick " + acquisition.tick();
        if (byId.isEmpty()) {
            assertEquals(0, node.size(acquisition), where);
            assertEquals(List.of(), inRegion, where);
            return 0;
        }
        assertEquals(entityIds.length, byId.size(), where);
        long version = (long) byId.getFirst().position().x();
        assertEquals(entitiesAt(partition, version, entityIds), byId, where);
        assertEquals(Set.copyOf(byId), Set.copyOf(inRegion), where);
        assertEquals(byId.size(), inRegion.size(), where);
        return version;
    }

    /** Acquires the snapshot of the tick, reads the entity and releases the snapshot. */
    private static List<Entity2> read(LocalPartitionNode2 node, PartitionId partitionId, long tick, long entityId) {
        Acquisition acquisition = node.acquire(partitionId, tick, defaultTimeout());
        try {
            return node.getAll(acquisition, new long[] {entityId});
        } finally {
            node.release(acquisition);
        }
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

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "a worker made no progress");
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
