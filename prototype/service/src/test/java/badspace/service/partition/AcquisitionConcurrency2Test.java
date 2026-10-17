package badspace.service.partition;

import static badspace.service.partition.Owners.owner;
import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.common.geometry.Point2.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.service.index.SpatialIndexes;
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
 * Readers that acquire and query snapshots while the writer commits and the tick
 * moves. The tests stop a query in the middle with a gate in the index, so the
 * commit, the move of the tick or the release happens during the query; they use
 * latches and barriers, not sleeps.
 * A writer that waits for a query fails the test after a timeout instead of
 * hanging.
 */
class AcquisitionConcurrency2Test {

    private static final List<IndexConfig> CONFIGS = List.of(
            IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4));
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Point2 A = point(1, 2);
    private static final Point2 B = point(3, 4);
    private static final Point2 C = point(5, 6);
    private static final Point2 D = point(7, 8);
    private static final long[] ALL_IDS = {10, 20, 30};
    private static final PartitionId P1 = new PartitionId(1);
    private static final int READERS = 3;
    private static final int TICKS = 2000;
    /** The commits of the writer with the tick on another thread: positionAt() keeps x below 1000. */
    private static final int VERSIONS = 999;
    private static final int ENTITIES = 4;
    /** The readers keep one acquisition in this many, to read it again at the end. */
    private static final int KEEP_EVERY = 50;
    /** How long a reader keeps its acquisition shared, in spins. */
    private static final int SPINS = 100;

    @Test
    void commitsAndMovesOfTheTickDuringAQueryDoNotChangeItsResultAndDoNotWait() throws Exception {
        for (IndexConfig config : CONFIGS) {
            GatedIndex2[] index = new GatedIndex2[1];
            PartitionStorage2 storage = gatedStorage(config, index, 1);
            storage.insertAll(owner(storage), List.of(new Entity2(10, A), new Entity2(20, B)));
            long acquisitionId = storage.acquire(publish(storage), 0);
            Gate gate = new Gate();
            index[0].gate = gate;
            QueryThread query = new QueryThread(storage, acquisitionId, 0);
            query.start();
            try {
                gate.awaitEntered();

                // The query is stopped in the index. The writer changes the slots and the
                // index, commits and moves the tick twice: it must not wait for the query.
                assertTimeoutPreemptively(TIMEOUT, () -> {
                    storage.updateAll(owner(storage), List.of(new Entity2(10, C)));
                    storage.removeAll(owner(storage), new long[] {20});
                    storage.insertAll(owner(storage), List.of(new Entity2(30, D)));
                    publish(storage);
                    storage.updateAll(owner(storage), List.of(new Entity2(30, A)));
                    publish(storage);
                    }, config + ": the writer waited for a query");
                assertEquals(3, storage.tick());

            } finally {
                gate.open();
                join(query);
            }
            List<Entity2> expected = List.of(new Entity2(10, A), new Entity2(20, B));
            query.check(expected, config.toString());
            storage.release(acquisitionId);
        }
    }

    @Test
    void releaseDuringAQueryLetsTheQueryEnd() throws Exception {
        for (IndexConfig config : CONFIGS) {
            GatedIndex2[] index = new GatedIndex2[1];
            PartitionStorage2 storage = gatedStorage(config, index, 1);
            storage.insertAll(owner(storage), List.of(new Entity2(10, A)));
            StorageSnapshot2 snapshot = publish(storage);
            long acquisitionId = storage.acquire(snapshot, 0);
            Gate gate = new Gate();
            index[0].gate = gate;
            QueryThread query = new QueryThread(storage, acquisitionId, 0);
            query.start();
            try {
                gate.awaitEntered();

                storage.release(acquisitionId);
                assertThrows(IllegalStateException.class, () -> storage.admit(acquisitionId, 0));
                // The snapshot leaves the window too: only the query still holds it.
                storage.removeAll(owner(storage), new long[] {10});
                publish(storage);
                assertEquals(0, storage.acquisitionCount());
                assertFalse(snapshot.uses().isEnded(), config + ": the snapshot ended during the query");

            } finally {
                gate.open();
                join(query);
            }
            query.check(List.of(new Entity2(10, A)), config.toString());
            assertTrue(snapshot.uses().isEnded(), config + ": the snapshot did not end with the query");
            assertEquals(1, storage.liveSnapshotCount());
        }
    }

    /**
     * The acquisition of reader A expires during its query, and the acquisition of
     * reader B removes it. The query of A ends with the snapshot it admitted; the next
     * read of A fails.
     */
    @Test
    void expiryDuringAQueryLetsTheQueryEnd() throws Exception {
        for (IndexConfig config : CONFIGS) {
            FakeClock clock = new FakeClock();
            GatedIndex2[] index = new GatedIndex2[1];
            PartitionStorage2 storage = new PartitionStorage2(config.limits(),
                    slots -> index[0] = new GatedIndex2(SpatialIndexes.create(config, slots)), 1, clock);
            storage.insertAll(owner(storage), List.of(new Entity2(10, A)));
            StorageSnapshot2 snapshot = publish(storage);
            long acquisitionA = storage.acquire(snapshot, 0, AcquireOptions.timeout(TIMEOUT));
            Gate gate = new Gate();
            index[0].gate = gate;
            QueryThread query = new QueryThread(storage, acquisitionA, 0);
            query.start();
            long acquisitionB;
            try {
                gate.awaitEntered();

                clock.advance(TIMEOUT);
                acquisitionB = storage.acquire(snapshot, 0, AcquireOptions.timeout(TIMEOUT));
                // Like the node, which removes the expired acquisitions after each acquisition.
                storage.removeExpired();
                assertEquals(1, storage.acquisitionCount(), config + ": the expired acquisition was not removed");
                assertThrows(IllegalStateException.class, () -> storage.admit(acquisitionA, 0));

            } finally {
                gate.open();
                join(query);
            }
            query.check(List.of(new Entity2(10, A)), config.toString());
            assertThrows(IllegalStateException.class, () -> storage.admit(acquisitionA, 0));
            StorageSnapshot2 admitted = storage.admit(acquisitionB, 0);
            try {
                assertSame(snapshot, admitted);
            } finally {
                storage.endQuery(admitted);
                storage.release(acquisitionB);
            }
        }
    }

    /**
     * A writer commits and moves the tick while the readers acquire the latest snapshot
     * or the one of the tick before it, so each acquisition races with the move of the
     * window. Each snapshot read must be a whole commit, in the slots and in the index;
     * the snapshots kept by the readers must stay readable after they leave the window.
     */
    @Test
    void acquisitionsDuringTheMoveOfTheWindowReadWholeCommits() throws Exception {
        for (IndexConfig config : CONFIGS) {
            LocalPartitionNode2 node = new LocalPartitionNode2();
            node.createPartition(P1, config, 2);
            long[] entityIds = new long[ENTITIES];
            List<Entity2> entities = new ArrayList<>();
            for (int i = 0; i < ENTITIES; i++) {
                entityIds[i] = i + 1;
                entities.add(new Entity2(entityIds[i], positionAt(0, entityIds[i])));
            }
            node.insertAll(owner(node, P1), entities);

            AtomicBoolean done = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CyclicBarrier start = new CyclicBarrier(READERS + 1);
            AtomicLong previousAcquired = new AtomicLong();
            CountDownLatch observed = new CountDownLatch(READERS);
            List<List<Acquisition>> kept = new ArrayList<>();
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < READERS; r++) {
                List<Acquisition> readerKept = new ArrayList<>();
                kept.add(readerKept);
                boolean previous = r % 2 == 0;
                Thread reader = new Thread(() -> {
                    try {
                        start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        int reads = 0;
                        while (!done.get()) {
                            Acquisition acquisition;
                            try {
                                acquisition = previous
                                        ? node.acquire(P1, node.tick(P1) - 2, defaultTimeout())
                                        : node.acquireLatest(P1, defaultTimeout());
                            } catch (SnapshotNotAvailableException | IllegalArgumentException e) {
                                // The tick moved after it was read, or the window does not have
                                // two ticks yet (then the tick asked for is negative).
                                continue;
                            }
                            if (previous) {
                                previousAcquired.incrementAndGet();
                            }
                            checkWholeCommit(node, acquisition, entityIds);
                            if (reads == 0) {
                                observed.countDown();
                            }
                            if (reads++ % KEEP_EVERY == 0) {
                                readerKept.add(acquisition);
                            } else {
                                node.release(acquisition);
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
                reader.start();
                readers.add(reader);
            }

            try {
                start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                for (long tick = 1; tick <= TICKS; tick++) {
                    node.setTick(tick);
                    List<Entity2> moved = new ArrayList<>();
                    for (long id : entityIds) {
                        moved.add(new Entity2(id, positionAt(tick, id)));
                    }
                    node.updateAll(owner(node, P1), moved);
                    node.commit(owner(node, P1));
                    if (tick == 2) {
                        await(observed);
                    }
                }
                node.setTick(TICKS + 1);
            } finally {
                done.set(true);
                for (Thread reader : readers) {
                    join(reader);
                }
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": a reader failed", failure.get());
            }
            assertTrue(previousAcquired.get() > 0, config + ": no snapshot before the last one was acquired");

            // The kept snapshots left the window long ago, but they are still acquired.
            for (List<Acquisition> readerKept : kept) {
                for (Acquisition acquisition : readerKept) {
                    checkWholeCommit(node, acquisition, entityIds);
                    node.release(acquisition);
                    assertThrows(IllegalStateException.class, () -> node.size(acquisition));
                }
            }
            // The queries of the node gave back their uses: only the window stays (k = 2).
            node.dropPartition(owner(node, P1));
            node.setTick(TICKS + 2);
            node.setTick(TICKS + 4);
            assertThrows(IllegalArgumentException.class, () -> node.tick(P1));
        }
    }

    /**
     * The writer commits and moves the tick while each reader acquires a snapshot and a
     * querier reads the same acquisition until the reader releases it. Acquisitions race
     * with the moves of the tick that move the window, and admissions race with the
     * releases. At the end every snapshot out of the window must have ended once: only
     * the window stays.
     */
    @Test
    void racingAcquisitionsQueriesAndReleasesEndEachSnapshotOnce() throws Exception {
        for (IndexConfig config : CONFIGS) {
            PartitionStorage2 storage = new PartitionStorage2(config, 2);
            storage.insertAll(owner(storage), List.of(new Entity2(10, A), new Entity2(20, B)));
            storage.commit(owner(storage));
            storage.setTick(2);
            CountDownLatch observed = new CountDownLatch(READERS);
            AtomicBoolean done = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CyclicBarrier start = new CyclicBarrier(2 * READERS + 1);
            List<Thread> threads = new ArrayList<>();
            for (int r = 0; r < READERS; r++) {
                int ticksBack = r % 2 + 1;
                // The number and the tick of the acquisition that the reader shares with its
                // querier, or null.
                AtomicReference<long[]> shared = new AtomicReference<>();
                CountDownLatch firstAdmitted = new CountDownLatch(1);
                threads.add(new Thread(() -> {
                    try {
                        start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        while (!done.get()) {
                            SnapshotCatalog2 catalog = storage.catalog();
                            long tick = catalog.tick() - ticksBack;
                            StorageSnapshot2 snapshot = catalog.at(tick);
                            if (snapshot == null) {
                                continue;
                            }
                            long acquisitionId = storage.acquire(snapshot, tick);
                            if (acquisitionId == PartitionStorage2.NOT_ACQUIRED) {
                                continue;
                            }
                            shared.set(new long[] {acquisitionId, tick});
                            await(firstAdmitted);
                            // Gives the querier some time to admit queries before the release.
                            for (int i = 0; i < SPINS; i++) {
                                Thread.onSpinWait();
                            }
                            shared.set(null);
                            storage.release(acquisitionId);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }));
                threads.add(new Thread(() -> {
                    try {
                        start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        while (!done.get()) {
                            long[] acquisition = shared.get();
                            if (acquisition == null) {
                                continue;
                            }
                            StorageSnapshot2 snapshot;
                            try {
                                snapshot = storage.admit(acquisition[0], acquisition[1]);
                            } catch (IllegalStateException e) {
                                // The reader released it first.
                                continue;
                            }
                            try {
                                assertEquals(2, snapshot.size());
                                if (firstAdmitted.getCount() != 0) {
                                    firstAdmitted.countDown();
                                    observed.countDown();
                                }
                            } finally {
                                storage.endQuery(snapshot);
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.start();
            }

            try {
                start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                for (long tick = 3; tick <= TICKS; tick++) {
                    storage.commit(owner(storage));
                    storage.setTick(tick);
                    if (tick == 3) {
                        await(observed);
                    }
                }
            } finally {
                done.set(true);
                for (Thread thread : threads) {
                    join(thread);
                }
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": a thread failed", failure.get());
            }
            assertEquals(0, storage.acquisitionCount(), config.toString());
            // k = 2: the snapshots of the last two ticks.
            assertEquals(2, storage.liveSnapshotCount(), config + ": a snapshot did not end, or ended twice");
        }
    }

    /**
     * Readers acquire, read and release the latest snapshot while the writer commits and
     * moves the tick, and then drops the partition and moves the tick until no tick of the
     * partition is in the window. The acquisitions made before stay readable until their
     * release; the new ones fail when the latest tick is after the removal. When the last
     * acquisition is released, the node no longer has the partition.
     */
    @Test
    void removalDuringAcquisitionsEndsWithTheLastRelease() throws Exception {
        for (IndexConfig config : CONFIGS) {
            LocalPartitionNode2 node = new LocalPartitionNode2();
            node.createPartition(P1, config, 1);
            node.insertAll(owner(node, P1), List.of(new Entity2(10, A), new Entity2(20, B)));
            CountDownLatch observed = new CountDownLatch(READERS);
            AtomicBoolean done = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CyclicBarrier start = new CyclicBarrier(READERS + 1);
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < READERS; r++) {
                readers.add(new Thread(() -> {
                    try {
                        start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        boolean read = false;
                        while (!done.get()) {
                            Acquisition acquisition;
                            try {
                                acquisition = node.acquireLatest(P1, defaultTimeout());
                            } catch (SnapshotNotAvailableException e) {
                                if (e.reason() == Reason.NOT_YET_PUBLISHED) {
                                    // Nothing is published before the first move of the tick.
                                    continue;
                                }
                                // The latest tick is after the removal.
                                return;
                            } catch (IllegalArgumentException e) {
                                // The partition is already destroyed: then it is unknown.
                                return;
                            }
                            // Gives the writer some time to commit or to remove the partition.
                            for (int i = 0; i < SPINS; i++) {
                                Thread.onSpinWait();
                            }
                            // The entities are inserted at tick 0, and committed from tick 1.
                            int expectedSize = acquisition.tick() == 0 ? 0 : 2;
                            assertEquals(expectedSize, node.size(acquisition), "tick " + acquisition.tick());
                            node.release(acquisition);
                            if (!read) {
                                observed.countDown();
                                read = true;
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }));
            }
            for (Thread reader : readers) {
                reader.start();
            }

            try {
                start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                for (long tick = 1; tick <= TICKS; tick++) {
                    node.setTick(tick);
                    node.commit(owner(node, P1));
                    if (tick == 2) {
                        await(observed);
                    }
                }
                node.dropPartition(owner(node, P1));
                // The removal tick, and then the first tick with no tick of the partition in
                // the window (k = 1).
                node.setTick(TICKS + 1);
                node.setTick(TICKS + 2);
            } finally {
                done.set(true);
                for (Thread reader : readers) {
                    join(reader);
                }
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": a reader failed", failure.get());
            }
            assertThrows(IllegalArgumentException.class, () -> node.tick(P1), config.toString());
        }
    }

    /**
     * The commit is stopped in the index, while it holds the lock of the partition. A move of
     * the tick on another thread waits for it, so the commit is in the snapshot of the tick
     * that ends.
     */
    @Test
    void moveOfTheTickWaitsForACommitThatIsRunning() throws Exception {
        for (IndexConfig config : CONFIGS) {
            GatedIndex2[] index = new GatedIndex2[1];
            ObservedLock lock = new ObservedLock();
            PartitionStorage2 storage = new PartitionStorage2(config.limits(),
                    slots -> index[0] = new GatedIndex2(SpatialIndexes.create(config, slots)), 2,
                    MonotonicClock.SYSTEM, 0, lock, new CoordinateBufferPool());
            storage.insertAll(owner(storage), List.of(new Entity2(10, A)));
            Gate gate = new Gate();
            index[0].commitGate = gate;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            long generation = owner(storage);
            Thread writer = worker(() -> storage.commit(generation), failure);
            Thread mover = worker(() -> storage.setTick(1), failure);
            writer.start();
            try {
                gate.awaitEntered();

                mover.start();
                lock.awaitWaiting();
                assertEquals(0, storage.tick(), config + ": the tick moved during a commit");

            } finally {
                gate.open();
                join(writer);
                join(mover);
            }
            if (failure.get() != null) {
                throw new AssertionError("Commit or tick worker failed", failure.get());
            }
            assertEquals(1, storage.tick(), config.toString());
            assertEquals(List.of(new Entity2(10, A)), storage.catalog().at(0).getAll(ALL_IDS), config.toString());
        }
    }

    /**
     * The writer commits and the tick moves on two different threads, with no order between
     * them, while readers acquire the latest snapshot or the one of the tick before it. All
     * the entities of the commit with number v are at x = v, so a snapshot is a whole commit
     * if all its entities have the same x; a later tick never has an older commit. At the end
     * the last commit is published, and only the snapshots of the window are left.
     */
    @Test
    void writerAndTickOnDifferentThreadsPublishWholeCommits() throws Exception {
        for (IndexConfig config : CONFIGS) {
            LocalPartitionNode2 node = new LocalPartitionNode2();
            node.createPartition(P1, config, 2);
            long[] entityIds = new long[ENTITIES];
            List<Entity2> entities = new ArrayList<>();
            for (int i = 0; i < ENTITIES; i++) {
                entityIds[i] = i + 1;
                entities.add(new Entity2(entityIds[i], positionAt(0, entityIds[i])));
            }
            node.insertAll(owner(node, P1), entities);

            CountDownLatch observed = new CountDownLatch(READERS);
            AtomicBoolean done = new AtomicBoolean();
            AtomicBoolean writerDone = new AtomicBoolean();
            AtomicLong lastTick = new AtomicLong();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CyclicBarrier start = new CyclicBarrier(READERS + 3);
            List<Thread> threads = new ArrayList<>();
            for (int r = 0; r < READERS; r++) {
                long ticksBack = r % 2 + 1;
                threads.add(new Thread(() -> {
                    try {
                        start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        long lastVersion = 0;
                        boolean read = false;
                        while (!done.get()) {
                            long tick = node.tick(P1) - ticksBack;
                            if (tick < 0) {
                                continue;
                            }
                            Acquisition acquisition;
                            try {
                                acquisition = node.acquire(P1, tick, defaultTimeout());
                            } catch (SnapshotNotAvailableException e) {
                                // The tick moved after it was read.
                                continue;
                            }
                            long version = versionOf(node, acquisition, entityIds);
                            // The tick read by this reader never goes back.
                            assertTrue(version >= lastVersion, "tick " + tick + " has an older commit");
                            lastVersion = version;
                            node.release(acquisition);
                            if (version > 0 && !read) {
                                observed.countDown();
                                read = true;
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }));
            }
            threads.add(new Thread(() -> {
                try {
                    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    for (long version = 1; version <= VERSIONS; version++) {
                        List<Entity2> moved = new ArrayList<>();
                        for (long id : entityIds) {
                            moved.add(new Entity2(id, positionAt(version, id)));
                        }
                        // Two calls, so the state of the writer between them is mixed.
                        node.updateAll(owner(node, P1), moved.subList(0, ENTITIES / 2));
                        node.updateAll(owner(node, P1), moved.subList(ENTITIES / 2, ENTITIES));
                        node.commit(owner(node, P1));
                        if (version == VERSIONS / 2) {
                            await(observed);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    writerDone.set(true);
                }
            }));
            threads.add(new Thread(() -> {
                try {
                    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    long tick = 0;
                    while (!writerDone.get() && !done.get()) {
                        node.setTick(++tick);
                    }
                    lastTick.set(tick);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
            for (Thread thread : threads) {
                thread.start();
            }

            try {
                start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                // The writer and the mover end by themselves; then the readers stop.
                join(threads.get(READERS));
                join(threads.get(READERS + 1));
            } finally {
                done.set(true);
                for (Thread thread : threads) {
                    join(thread);
                }
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": a thread failed", failure.get());
            }
            // The next move publishes the last commit, if it is still waiting.
            node.setTick(lastTick.get() + 1);
            Acquisition latest = node.acquireLatest(P1, defaultTimeout());
            assertEquals(VERSIONS, versionOf(node, latest, entityIds), config + ": the last commit was lost");
            node.release(latest);
            node.dropPartition(owner(node, P1));
            node.setTick(lastTick.get() + 2);
            node.setTick(lastTick.get() + 4);
            assertThrows(IllegalArgumentException.class, () -> node.tick(P1));
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


    /**
     * Returns the number of the commit in the acquired snapshot: the x of all its entities,
     * or 0 for the empty snapshot before the first commit. Fails if the entities do not all
     * have the same x, in the slots and in the index: then the snapshot is not a whole commit.
     */
    private static long versionOf(LocalPartitionNode2 node, Acquisition acquisition, long[] entityIds) {
        List<Entity2> byId = node.getAll(acquisition, entityIds);
        if (byId.isEmpty()) {
            assertEquals(0, node.size(acquisition));
            assertEquals(List.of(), node.findNearest(acquisition, A, entityIds.length));
            return 0;
        }
        String where = "tick " + acquisition.tick();
        assertEquals(entityIds.length, byId.size(), where);
        long version = (long) byId.getFirst().position().x();
        for (Entity2 e : byId) {
            assertEquals(positionAt(version, e.id()), e.position(), where);
        }
        Region2 all = new Box2(point(-1, -1), point(1000, entityIds.length + 1));
        assertEquals(Set.copyOf(byId), Set.copyOf(node.findInRegion(acquisition, all)), where);
        return version;
    }

    /** The position of an entity at a tick: all the entities move at each tick. */
    private static Point2 positionAt(long tick, long entityId) {
        return point(tick % 1000, entityId);
    }

    /**
     * Commits in the current tick of the storage, moves to the next tick and returns the
     * published snapshot, which is the one of the tick before the new tick.
     */
    private static StorageSnapshot2 publish(PartitionStorage2 storage) {
        StorageSnapshot2 snapshot = storage.commit(owner(storage));
        storage.setTick(storage.tick() + 1);
        return snapshot;
    }

    /**
     * Checks that the acquired snapshot has all the entities at the positions of its tick,
     * in the slots and in the index. The empty snapshot of tick 0 has no entities, because
     * the insertions are committed at tick 1.
     */
    private static void checkWholeCommit(LocalPartitionNode2 node, Acquisition acquisition, long[] entityIds) {
        long tick = acquisition.tick();
        String where = "tick " + tick;
        int expectedSize = tick == 0 ? 0 : entityIds.length;
        List<Entity2> byId = node.getAll(acquisition, entityIds);
        assertEquals(expectedSize, byId.size(), where);
        for (Entity2 e : byId) {
            assertEquals(positionAt(tick, e.id()), e.position(), where);
        }
        Region2 all = new Box2(point(-1, -1), point(1000, entityIds.length + 1));
        assertEquals(Set.copyOf(byId), Set.copyOf(node.findInRegion(acquisition, all)), where);
        assertEquals(expectedSize, node.size(acquisition), where);
    }

    private static PartitionStorage2 gatedStorage(IndexConfig config, GatedIndex2[] index, int retainedTicks) {
        return new PartitionStorage2(config.limits(),
                slots -> index[0] = new GatedIndex2(SpatialIndexes.create(config, slots)), retainedTicks);
    }

    /** A reader on another thread: admits the acquisition, then queries the snapshot by region and by ID. */
    private static final class QueryThread extends Thread {

        private final PartitionStorage2 storage;
        private final long acquisitionId;
        private final long tick;
        private volatile List<Entity2> inRegion;
        private volatile List<Entity2> byId;
        private volatile Throwable failure;

        QueryThread(PartitionStorage2 storage, long acquisitionId, long tick) {
            this.storage = storage;
            this.acquisitionId = acquisitionId;
            this.tick = tick;
        }

        @Override
        public void run() {
            try {
                StorageSnapshot2 snapshot = storage.admit(acquisitionId, tick);
                try {
                    inRegion = snapshot.findInRegion(new Box2(point(-100, -100), point(100, 100)));
                    byId = snapshot.getAll(ALL_IDS);
                } finally {
                    storage.endQuery(snapshot);
                }
            } catch (Throwable e) {
                failure = e;
            }
        }

        /** Checks that the query read exactly the expected entities, in the slots and in the index. */
        void check(List<Entity2> expected, String where) {
            if (failure != null) {
                throw new AssertionError(where + ": the query failed", failure);
            }
            assertEquals(Set.copyOf(expected), Set.copyOf(inRegion), where);
            assertEquals(expected.size(), inRegion.size(), where);
            assertEquals(expected, byId, where);
        }
    }
}
