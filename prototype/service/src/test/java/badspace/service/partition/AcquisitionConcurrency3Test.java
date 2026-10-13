package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Box3;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.VersionNotAvailableException;
import badspace.service.index.IndexVersion3;
import badspace.service.index.SlotView3;
import badspace.service.index.SpatialIndex3;
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
 * Readers that acquire and query versions while the writer commits. The tests
 * stop a query in the middle with a gate in the index, so the commit or the
 * release happens during the query; they use latches and barriers, not sleeps.
 * A writer that waits for a query fails the test after a timeout instead of
 * hanging.
 */
class AcquisitionConcurrency3Test {

    private static final List<IndexConfig> CONFIGS = List.of(
            IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4));
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Point3 A = point(1, 2);
    private static final Point3 B = point(3, 4);
    private static final Point3 C = point(5, 6);
    private static final Point3 D = point(7, 8);
    private static final long[] ALL_IDS = {10, 20, 30};
    private static final PartitionId P1 = new PartitionId(1);
    private static final int READERS = 3;
    private static final int COMMITS = 2000;
    private static final int ENTITIES = 4;
    /** The readers keep one acquisition in this many, to read it again at the end. */
    private static final int KEEP_EVERY = 50;

    /** Returns a point with z = 0: the positions of these tests do not need z. */
    private static Point3 point(double x, double y) {
        return new Point3(x, y, 0);
    }

    @Test
    void commitDuringAQueryDoesNotChangeItsResultAndDoesNotWait() throws Exception {
        for (IndexConfig config : CONFIGS) {
            GatedIndex[] index = new GatedIndex[1];
            PartitionStorage3 storage = gatedStorage(config, index, 0);
            storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
            long acquisitionId = storage.acquire(storage.commit(1));
            Gate gate = new Gate();
            index[0].gate = gate;
            QueryThread query = new QueryThread(storage, acquisitionId);
            query.start();
            gate.awaitEntered();

            // The query is stopped in the index. The writer changes the slots and the
            // index and commits twice: it must not wait for the query.
            assertTimeoutPreemptively(TIMEOUT, () -> {
                storage.updateAll(List.of(new Entity3(10, C)));
                storage.removeAll(new long[] {20});
                storage.insertAll(List.of(new Entity3(30, D)));
                storage.commit(2);
                storage.updateAll(List.of(new Entity3(30, A)));
                storage.commit(3);
            }, config + ": the writer waited for a query");
            assertEquals(3, storage.lastCommit());

            gate.open();
            query.join();
            List<Entity3> expected = List.of(new Entity3(10, A), new Entity3(20, B));
            query.check(expected, config.toString());
        }
    }

    @Test
    void releaseDuringAQueryLetsTheQueryEnd() throws Exception {
        for (IndexConfig config : CONFIGS) {
            GatedIndex[] index = new GatedIndex[1];
            PartitionStorage3 storage = gatedStorage(config, index, 0);
            storage.insertAll(List.of(new Entity3(10, A)));
            long acquisitionId = storage.acquire(storage.commit(1));
            Gate gate = new Gate();
            index[0].gate = gate;
            QueryThread query = new QueryThread(storage, acquisitionId);
            query.start();
            gate.awaitEntered();

            storage.release(acquisitionId);
            assertThrows(IllegalStateException.class, () -> storage.admit(acquisitionId));
            // The version leaves the window too: only the query still holds it.
            storage.removeAll(new long[] {10});
            storage.commit(2);
            assertEquals(0, storage.acquisitionCount());

            gate.open();
            query.join();
            query.check(List.of(new Entity3(10, A)), config.toString());
        }
    }

    /**
     * A writer commits while the readers acquire the version before the last one,
     * so each acquisition races with the move of the window. Each version read must
     * be a whole commit, in the slots and in the index; the versions kept by the
     * readers must stay readable after they leave the window.
     */
    @Test
    void acquisitionsDuringTheMoveOfTheWindowReadWholeCommits() throws Exception {
        for (IndexConfig config : CONFIGS) {
            LocalPartitionNode3 node = new LocalPartitionNode3();
            node.createPartition(P1, config, 1);
            long[] entityIds = new long[ENTITIES];
            List<Entity3> entities = new ArrayList<>();
            for (int i = 0; i < ENTITIES; i++) {
                entityIds[i] = i + 1;
                entities.add(new Entity3(entityIds[i], positionAt(0, entityIds[i])));
            }
            node.insertAll(P1, entities);

            AtomicBoolean done = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CyclicBarrier start = new CyclicBarrier(READERS + 1);
            AtomicLong previousAcquired = new AtomicLong();
            List<List<Acquisition>> kept = new ArrayList<>();
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < READERS; r++) {
                List<Acquisition> readerKept = new ArrayList<>();
                kept.add(readerKept);
                boolean previous = r % 2 == 0;
                Thread reader = new Thread(() -> {
                    try {
                        start.await();
                        int reads = 0;
                        while (!done.get()) {
                            Acquisition acquisition;
                            try {
                                acquisition = previous ? node.acquirePrevious(P1, 1) : node.acquireLatest(P1);
                            } catch (VersionNotAvailableException e) {
                                // At commit 0 the window has no version before the last one.
                                continue;
                            }
                            if (previous) {
                                previousAcquired.incrementAndGet();
                            }
                            checkWholeCommit(node, acquisition, entityIds);
                            if (++reads % KEEP_EVERY == 0) {
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
                for (long commit = 1; commit <= COMMITS; commit++) {
                    List<Entity3> moved = new ArrayList<>();
                    for (long id : entityIds) {
                        moved.add(new Entity3(id, positionAt(commit, id)));
                    }
                    node.updateAll(P1, moved);
                    node.commit(P1, commit);
                }
            } finally {
                done.set(true);
                for (Thread reader : readers) {
                    reader.join(TIMEOUT.toMillis());
                }
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": a reader failed", failure.get());
            }
            assertTrue(previousAcquired.get() > 0, config + ": no version before the last one was acquired");

            // The kept versions left the window long ago, but they are still acquired.
            for (List<Acquisition> readerKept : kept) {
                for (Acquisition acquisition : readerKept) {
                    checkWholeCommit(node, acquisition, entityIds);
                    node.release(acquisition);
                    assertThrows(IllegalStateException.class, () -> node.size(acquisition));
                }
            }
        }
    }

    /** The position of an entity at a commit: all the entities move at each commit. */
    private static Point3 positionAt(long commit, long entityId) {
        return point(commit % 1000, entityId);
    }

    /**
     * Checks that the acquired version has all the entities at the positions of its commit,
     * in the slots and in the index. The empty version at commit 0 has no entities, because
     * the insertions are committed with commit 1.
     */
    private static void checkWholeCommit(LocalPartitionNode3 node, Acquisition acquisition, long[] entityIds) {
        long commit = acquisition.commit();
        String where = "commit " + commit;
        int expectedSize = commit == 0 ? 0 : entityIds.length;
        List<Entity3> byId = node.getAll(acquisition, entityIds);
        assertEquals(expectedSize, byId.size(), where);
        for (Entity3 e : byId) {
            assertEquals(positionAt(commit, e.id()), e.position(), where);
        }
        Region3 all = new Box3(point(-1, -1), point(1000, entityIds.length + 1));
        assertEquals(Set.copyOf(byId), Set.copyOf(node.findInRegion(acquisition, all)), where);
        assertEquals(expectedSize, node.size(acquisition), where);
    }

    private static PartitionStorage3 gatedStorage(IndexConfig config, GatedIndex[] index, int retainedPastVersions) {
        return new PartitionStorage3(config.limits(),
                slots -> index[0] = new GatedIndex(SpatialIndexes.create(config, slots)), retainedPastVersions);
    }

    /** A reader on another thread: admits the acquisition, then queries the version by region and by ID. */
    private static final class QueryThread extends Thread {

        private final PartitionStorage3 storage;
        private final long acquisitionId;
        private volatile List<Entity3> inRegion;
        private volatile List<Entity3> byId;
        private volatile Throwable failure;

        QueryThread(PartitionStorage3 storage, long acquisitionId) {
            this.storage = storage;
            this.acquisitionId = acquisitionId;
        }

        @Override
        public void run() {
            try {
                StorageVersion3 version = storage.admit(acquisitionId);
                inRegion = version.findInRegion(new Box3(point(-100, -100), point(100, 100)));
                byId = version.getAll(ALL_IDS);
            } catch (Throwable e) {
                failure = e;
            }
        }

        /** Checks that the query read exactly the expected entities, in the slots and in the index. */
        void check(List<Entity3> expected, String where) {
            if (failure != null) {
                throw new AssertionError(where + ": the query failed", failure);
            }
            assertEquals(Set.copyOf(expected), Set.copyOf(inRegion), where);
            assertEquals(expected.size(), inRegion.size(), where);
            assertEquals(expected, byId, where);
        }
    }

    /** Stops the queries that reach it until the test opens it. */
    private static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch opened = new CountDownLatch(1);

        void pass() throws InterruptedException {
            entered.countDown();
            assertTrue(opened.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the gate was never opened");
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "no query reached the gate");
        }

        void open() {
            opened.countDown();
        }
    }

    /**
     * A real index whose versions stop findInRegion at the gate, if there is one,
     * before they read the index.
     */
    private static final class GatedIndex implements SpatialIndex3 {

        private final SpatialIndex3 index;
        // Set by the test thread before the query starts, read by the query thread.
        volatile Gate gate;

        GatedIndex(SpatialIndex3 index) {
            this.index = index;
        }

        @Override
        public void inserted(int slot, Point3 position) {
            index.inserted(slot, position);
        }

        @Override
        public void moved(int slot, Point3 from, Point3 to) {
            index.moved(slot, from, to);
        }

        @Override
        public void removed(int slot, Point3 position) {
            index.removed(slot, position);
        }

        @Override
        public void relocated(int from, int to, Point3 position) {
            index.relocated(from, to, position);
        }

        @Override
        public IndexVersion3 commit(long n) {
            IndexVersion3 version = index.commit(n);
            return new IndexVersion3() {
                @Override
                public long commit() {
                    return version.commit();
                }

                @Override
                public int[] findInRegion(Region3 region, SlotView3 slots) {
                    Gate current = gate;
                    if (current != null) {
                        try {
                            current.pass();
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return version.findInRegion(region, slots);
                }

                @Override
                public int[] findNearest(Point3 point, int count, SlotView3 slots) {
                    return version.findNearest(point, count, slots);
                }
            };
        }

        @Override
        public int[] findInRegion(Region3 region) {
            return index.findInRegion(region);
        }

        @Override
        public int[] findNearest(Point3 point, int count) {
            return index.findNearest(point, count);
        }
    }
}
