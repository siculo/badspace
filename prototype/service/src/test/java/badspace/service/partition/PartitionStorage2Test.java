package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Box2;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.service.index.IndexVersion2;
import badspace.service.index.SlotView2;
import badspace.service.index.SpatialIndex2;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks the calls that the storage makes to its index. */
class PartitionStorage2Test {

    private static final Point2 A = new Point2(1, 2);
    private static final Point2 B = new Point2(3, 4);
    private static final Point2 C = new Point2(5, 6);
    private static final Point2 D = new Point2(7, 8);

    private static final Point2 OUTSIDE = new Point2(11, 0);
    private static final CoordinateLimits LIMITS = new CoordinateLimits(-10, 10);
    private static final Point2 LIMITS_MIN = new Point2(-10, -10);
    private static final Point2 LIMITS_MAX = new Point2(10, 10);
    private static final long[] ALL_IDS = {10, 20, 30, 40};
    private static final int READERS = 3;
    private static final int COMMITS = 3000;

    private RecordingIndex index;
    private PartitionStorage2 storage;

    @BeforeEach
    void setUp() {
        storage = new PartitionStorage2(LIMITS, s -> index = new RecordingIndex(s));
    }

    @Test
    void factoryGetsTheStorage() {
        assertEquals(storage, index.storage);
    }

    @Test
    void insertNotifiesEachNewSlot() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        assertEquals(List.of("inserted 0 " + A + " id=10", "inserted 1 " + B + " id=20"), index.calls);
    }

    @Test
    void updateNotifiesOldAndNewPosition() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        index.calls.clear();
        storage.updateAll(List.of(new Entity2(20, C)));
        assertEquals(List.of("moved 1 " + B + " -> " + C + " now=" + C), index.calls);
    }

    @Test
    void removeOfLastSlotNotifiesOnlyTheRemoval() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        index.calls.clear();
        storage.removeAll(new long[] {20});
        assertEquals(List.of("removed 1 " + B + " size=1"), index.calls);
    }

    @Test
    void removeOfOtherSlotNotifiesTheRemovalAndThenTheRelocation() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C)));
        index.calls.clear();
        storage.removeAll(new long[] {10});
        assertEquals(List.of("removed 0 " + A + " size=2", "relocated 2 -> 0 " + C + " id=30"), index.calls);
    }

    @Test
    void removeOfManyNotifiesInOrder() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C), new Entity2(40, D)));
        index.calls.clear();
        storage.removeAll(new long[] {20, 40});
        assertEquals(List.of(
                "removed 1 " + B + " size=3", "relocated 3 -> 1 " + D + " id=40",
                "removed 1 " + D + " size=2", "relocated 2 -> 1 " + C + " id=30"), index.calls);
    }

    @Test
    void failedWritesNotifyNothing() {
        storage.insertAll(List.of(new Entity2(10, A)));
        index.calls.clear();
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity2(20, B), new Entity2(10, C))));
        assertThrows(NoSuchElementException.class,
                () -> storage.updateAll(List.of(new Entity2(10, B), new Entity2(99, C))));
        assertThrows(NoSuchElementException.class, () -> storage.removeAll(new long[] {10, 99}));
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity2(20, B), new Entity2(30, OUTSIDE))));
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateAll(List.of(new Entity2(10, B), new Entity2(10, OUTSIDE))));
        assertEquals(List.of(), index.calls);
        assertEquals(List.of(new Entity2(10, A)), storage.getAll(new long[] {10, 20, 30}));
    }

    @Test
    void writesOnTheLimitsAreAccepted() {
        storage.insertAll(List.of(new Entity2(10, new Point2(-10, -10)), new Entity2(20, A)));
        storage.updateAll(List.of(new Entity2(20, new Point2(10, 10))));
        assertEquals(2, storage.size());
        assertEquals(LIMITS, storage.limits());
    }

    @Test
    void storageWithAnIndexConfigUsesTheLimitsOfTheIndex() {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4, 2))) {
            PartitionStorage2 configured = new PartitionStorage2(config);
            double max = config.limits().max();
            configured.insertAll(List.of(new Entity2(10, new Point2(max, max))));
            assertThrows(IllegalArgumentException.class,
                    () -> configured.insertAll(List.of(new Entity2(20, new Point2(Math.nextUp(max), 0)))));
            assertEquals(1, configured.size());
            assertEquals(config.limits(), configured.limits());
        }
    }

    @Test
    void queriesReturnTheEntitiesOfTheSlotsInTheGivenOrder() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C)));
        index.result = new int[] {2, 0};
        List<Entity2> expected = List.of(new Entity2(30, C), new Entity2(10, A));
        assertEquals(expected, storage.findInRegion(new Box2(A, B)));
        assertEquals(expected, storage.findNearest(A, 2));
    }

    @Test
    void commitGoesToTheIndexOnlyWhenTheNumberIsValid() {
        storage.commit(3);
        assertThrows(IllegalArgumentException.class, () -> storage.commit(3));
        storage.commit(7);
        assertEquals(List.of("commit 3", "commit 7"), index.calls);
        assertEquals(7, storage.lastCommit());
    }

    @Test
    void versionOfACommitDoesNotSeeLaterWrites() {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4, 2))) {
            PartitionStorage2 configured = new PartitionStorage2(config);
            configured.insertAll(List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C)));
            StorageVersion2 first = configured.commit(1);
            // The removal of 10 moves 30 into its slot.
            configured.updateAll(List.of(new Entity2(30, D)));
            configured.removeAll(new long[] {10});
            configured.insertAll(List.of(new Entity2(40, A)));
            StorageVersion2 second = configured.commit(2);
            configured.removeAll(new long[] {20});
            configured.updateAll(List.of(new Entity2(40, B)));

            String where = config.toString();
            checkVersion(first, 1, List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C)), where);
            checkVersion(second, 2, List.of(new Entity2(40, A), new Entity2(20, B), new Entity2(30, D)), where);
            assertEquals(List.of(new Entity2(30, D), new Entity2(40, B)), configured.getAll(ALL_IDS), where);
        }
    }

    @Test
    void commitWithoutInsertionsOrRemovalsSharesTheIds() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        SlotSnapshot2 first = storage.commit(1).slots();
        storage.updateAll(List.of(new Entity2(20, C)));
        SlotSnapshot2 moved = storage.commit(2).slots();
        assertSame(first.ids, moved.ids);
        assertSame(first.slotById, moved.slotById);
        assertNotSame(first.coords, moved.coords);
        assertEquals(B, first.positionAt(1));
        assertEquals(C, moved.positionAt(1));

        storage.removeAll(new long[] {10});
        SlotSnapshot2 removed = storage.commit(3).slots();
        assertNotSame(moved.ids, removed.ids);
        assertNotSame(moved.slotById, removed.slotById);
        storage.insertAll(List.of(new Entity2(30, D)));
        SlotSnapshot2 inserted = storage.commit(4).slots();
        assertNotSame(removed.ids, inserted.ids);
        assertNotSame(removed.slotById, inserted.slotById);
        assertEquals(2, moved.size());
        assertEquals(1, removed.size());
        assertEquals(2, inserted.size());
    }

    @Test
    void readersSeeTheWritesOnlyAfterTheCommit() {
        StorageVersion2 empty = storage.lastVersion();
        assertEquals(0, empty.commit());
        assertEquals(0, empty.size());
        assertEquals(List.of(), empty.findInRegion(new Box2(LIMITS_MIN, LIMITS_MAX)));
        assertEquals(List.of(), empty.findNearest(A, 1));
        assertEquals(0, storage.commit(1).size());

        storage.insertAll(List.of(new Entity2(10, A)));
        assertEquals(List.of(new Entity2(10, A)), storage.getAll(ALL_IDS));
        assertEquals(1, storage.lastVersion().commit());
        assertEquals(List.of(), storage.lastVersion().getAll(ALL_IDS));
        StorageVersion2 second = storage.commit(2);
        assertSame(second, storage.lastVersion());
        assertEquals(List.of(new Entity2(10, A)), second.getAll(ALL_IDS));

        storage.updateAll(List.of(new Entity2(10, B)));
        assertEquals(List.of(new Entity2(10, B)), storage.getAll(ALL_IDS));
        assertSame(second, storage.lastVersion());
        assertEquals(List.of(new Entity2(10, A)), storage.lastVersion().getAll(ALL_IDS));
    }

    /**
     * A writer and some readers on other threads. At each commit the writer
     * moves all the entities to positions that depend on the commit, and
     * changes their number; it moves them with more than one call, so the
     * states between the calls are mixed. The readers check that each version
     * they read is a whole commit.
     */
    @Test
    void readersOnOtherThreadsSeeOnlyWholeCommits() throws InterruptedException {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4))) {
            PartitionStorage2 configured = new PartitionStorage2(config);
            AtomicBoolean done = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> readers = new ArrayList<>();
            CountDownLatch started = new CountDownLatch(READERS);
            for (int r = 0; r < READERS; r++) {
                Thread reader = new Thread(() -> {
                    try {
                        long last = 0;
                        started.countDown();
                        while (!done.get()) {
                            StorageVersion2 version = configured.lastVersion();
                            assertTrue(version.commit() >= last, "commits go back");
                            last = version.commit();
                            checkWholeCommit(version);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
                reader.start();
                readers.add(reader);
            }
            try {
                started.await();
                writeCommits(configured);
            } finally {
                done.set(true);
                for (Thread reader : readers) {
                    reader.join(10_000);
                }
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": a reader failed", failure.get());
            }
        }
    }

    @Test
    void findNearestWithZeroCountDoesNotCallTheIndex() {
        storage.insertAll(List.of(new Entity2(10, A)));
        index.calls.clear();
        assertEquals(List.of(), storage.findNearest(A, 0));
        assertEquals(List.of(), index.calls);
    }

    /** Checks all the reads of the version, which must find exactly the expected entities, nearest to A first. */
    private static void checkVersion(StorageVersion2 version, long commit, List<Entity2> expected, String where) {
        assertEquals(commit, version.commit(), where);
        assertEquals(Set.copyOf(expected), Set.copyOf(version.getAll(ALL_IDS)), where);
        assertEquals(expected.size(), version.getAll(ALL_IDS).size(), where);
        List<Entity2> inRegion = version.findInRegion(new Box2(LIMITS_MIN, LIMITS_MAX));
        assertEquals(Set.copyOf(expected), Set.copyOf(inRegion), where);
        assertEquals(expected.size(), inRegion.size(), where);
        assertEquals(expected, version.findNearest(A, ALL_IDS.length), where);
        assertEquals(List.of(), version.findNearest(A, 0), where);
    }

    private static void writeCommits(PartitionStorage2 storage) {
        List<Long> ids = new ArrayList<>();
        long nextId = 1;
        for (long commit = 1; commit <= COMMITS; commit++) {
            List<Entity2> moved = new ArrayList<>();
            for (long id : ids) {
                moved.add(new Entity2(id, positionAt(commit, id)));
            }
            int half = moved.size() / 2;
            storage.updateAll(moved.subList(0, half));
            storage.updateAll(moved.subList(half, moved.size()));
            while (ids.size() > sizeAt(commit)) {
                storage.removeAll(new long[] {ids.removeFirst()});
            }
            while (ids.size() < sizeAt(commit)) {
                long id = nextId++;
                storage.insertAll(List.of(new Entity2(id, positionAt(commit, id))));
                ids.add(id);
            }
            storage.commit(commit);
        }
    }

    /** Checks that all the reads of the version see the entities of its commit, and only them. */
    private static void checkWholeCommit(StorageVersion2 version) {
        long commit = version.commit();
        String where = "commit " + commit;
        assertEquals(sizeAt(commit), version.size(), where);
        List<Entity2> found = version.findInRegion(new Box2(LIMITS_MIN, LIMITS_MAX));
        assertEquals(sizeAt(commit), found.size(), where);
        for (Entity2 e : found) {
            assertEquals(positionAt(commit, e.id()), e.position(), where);
        }
        long[] ids = found.stream().mapToLong(Entity2::id).toArray();
        assertEquals(found.size(), version.getAll(ids).size(), where);
        for (Entity2 e : version.getAll(ids)) {
            assertEquals(positionAt(commit, e.id()), e.position(), where);
        }
        for (Entity2 e : version.findNearest(positionAt(commit, 0), sizeAt(commit) + 1)) {
            assertEquals(positionAt(commit, e.id()), e.position(), where);
        }
    }

    /** The number of entities at the commit: 0 at commit 0, then from 0 to 22. */
    private static int sizeAt(long commit) {
        return (int) (commit * 7 % 23);
    }

    /** The position of an entity at the commit, inside the limits; it changes cell often. */
    private static Point2 positionAt(long commit, long id) {
        double base = commit % 16 - 8;
        return new Point2(base + (id % 10) * 0.25, -base - (id / 10 % 10) * 0.25);
    }

    /**
     * Index that records each call as a string, with some state of the
     * storage at that moment, and gives a fixed result to the queries.
     */
    private static final class RecordingIndex implements SpatialIndex2 {

        final SlotView2 storage;
        final List<String> calls = new ArrayList<>();
        int[] result = new int[0];

        RecordingIndex(SlotView2 storage) {
            this.storage = storage;
        }

        @Override
        public void inserted(int slot, Point2 position) {
            calls.add("inserted " + slot + " " + position + " id=" + storage.idAt(slot));
        }

        @Override
        public void moved(int slot, Point2 from, Point2 to) {
            calls.add("moved " + slot + " " + from + " -> " + to + " now=" + storage.positionAt(slot));
        }

        @Override
        public void removed(int slot, Point2 position) {
            calls.add("removed " + slot + " " + position + " size=" + storage.size());
        }

        @Override
        public void relocated(int from, int to, Point2 position) {
            calls.add("relocated " + from + " -> " + to + " " + position + " id=" + storage.idAt(to));
        }

        @Override
        public IndexVersion2 commit(long n) {
            calls.add("commit " + n);
            // These tests do not read the version of the index.
            return null;
        }

        @Override
        public int[] findInRegion(Region2 region) {
            calls.add("findInRegion");
            return result;
        }

        @Override
        public int[] findNearest(Point2 point, int count) {
            calls.add("findNearest");
            return result;
        }
    }
}
