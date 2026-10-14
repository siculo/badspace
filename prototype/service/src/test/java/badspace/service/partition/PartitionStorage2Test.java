package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Box2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.service.index.IndexVersion2;
import badspace.service.index.SlotView2;
import badspace.service.index.SpatialIndex2;
import java.time.Duration;
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

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
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
    /** k of the partitions in the tests with concurrent readers. */
    private static final int RETAINED_PAST_VERSIONS = 2;

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
    void storageKeepsTheNumberOfRetainedPastVersions() {
        assertEquals(0, new PartitionStorage2(IndexConfig.linearScan()).retainedPastVersions());
        assertEquals(2, new PartitionStorage2(IndexConfig.linearScan(), 2).retainedPastVersions());
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
        assertSame(first.slotEntityIds, moved.slotEntityIds);
        assertSame(first.slotById, moved.slotById);
        assertNotSame(first.coords, moved.coords);
        assertEquals(B, first.positionAt(1));
        assertEquals(C, moved.positionAt(1));

        storage.removeAll(new long[] {10});
        SlotSnapshot2 removed = storage.commit(3).slots();
        assertNotSame(moved.slotEntityIds, removed.slotEntityIds);
        assertNotSame(moved.slotById, removed.slotById);
        storage.insertAll(List.of(new Entity2(30, D)));
        SlotSnapshot2 inserted = storage.commit(4).slots();
        assertNotSame(removed.slotEntityIds, inserted.slotEntityIds);
        assertNotSame(removed.slotById, inserted.slotById);
        assertEquals(2, moved.size());
        assertEquals(1, removed.size());
        assertEquals(2, inserted.size());
    }

    @Test
    void catalogHoldsTheSameVersionsThatTheCommitsReturn() {
        PartitionStorage2 configured = new PartitionStorage2(IndexConfig.linearScan(), 1);
        StorageVersion2 empty = configured.lastVersion();
        StorageVersion2 first = configured.commit(1);
        assertSame(first, configured.catalog().previous(0));
        assertSame(empty, configured.catalog().previous(1));
        StorageVersion2 second = configured.commit(2);
        assertSame(second, configured.catalog().previous(0));
        assertSame(first, configured.catalog().previous(1));
        assertSame(second, configured.lastVersion());
    }

    @Test
    void acquisitionKeepsItsVersionUntilTheRelease() {
        StorageVersion2 first = storage.commit(1);
        long acquisitionId = storage.acquire(first);
        storage.commit(2);
        assertSame(first, storage.admit(acquisitionId));
        storage.release(acquisitionId);
        assertThrows(IllegalStateException.class, () -> storage.admit(acquisitionId));
        assertEquals(0, storage.acquisitionCount());
    }

    @Test
    void eachAcquisitionHasItsOwnNumber() {
        StorageVersion2 version = storage.commit(1);
        long first = storage.acquire(version);
        long second = storage.acquire(version);
        assertNotEquals(first, second);
        storage.release(first);
        assertSame(version, storage.admit(second));
        assertEquals(1, storage.acquisitionCount());
    }

    @Test
    void releaseAgainDoesNothing() {
        long acquisitionId = storage.acquire(storage.lastVersion());
        storage.release(acquisitionId);
        storage.release(acquisitionId);
        assertEquals(0, storage.acquisitionCount());
    }

    @Test
    void acquisitionNeverMadeIsRejected() {
        long acquisitionId = storage.acquire(storage.lastVersion());
        assertThrows(IllegalArgumentException.class, () -> storage.release(acquisitionId + 1));
        assertThrows(IllegalArgumentException.class, () -> storage.admit(acquisitionId + 1));
        assertThrows(IllegalArgumentException.class, () -> storage.release(0));
        assertEquals(1, storage.acquisitionCount());
    }

    @Test
    void expiredAcquisitionsAreRemovedAtEachEventOfThePartition() {
        FakeClock clock = new FakeClock();
        PartitionStorage2 timed = new PartitionStorage2(IndexConfig.linearScan(), 0, clock);
        AcquireOptions options = AcquireOptions.timeout(TIMEOUT);

        // The commit removes them.
        timed.acquire(timed.lastVersion(), options);
        clock.advance(TIMEOUT);
        assertEquals(1, timed.acquisitionCount());
        timed.commit(1);
        assertEquals(0, timed.acquisitionCount());

        // An acquisition removes them, and keeps the new one.
        timed.acquire(timed.lastVersion(), options);
        clock.advance(TIMEOUT);
        long fresh = timed.acquire(timed.lastVersion(), options);
        assertEquals(1, timed.acquisitionCount());

        // The release of another acquisition removes them.
        long kept = timed.acquire(timed.lastVersion());
        clock.advance(TIMEOUT);
        long released = timed.acquire(timed.lastVersion());
        timed.release(released);
        assertEquals(1, timed.acquisitionCount());
        assertThrows(IllegalStateException.class, () -> timed.admit(fresh));
        assertSame(timed.lastVersion(), timed.admit(kept));
    }

    @Test
    void readOfAnExpiredAcquisitionDoesNotRemoveIt() {
        FakeClock clock = new FakeClock();
        PartitionStorage2 timed = new PartitionStorage2(IndexConfig.linearScan(), 0, clock);
        long acquisitionId = timed.acquire(timed.lastVersion(), AcquireOptions.timeout(TIMEOUT));
        clock.advance(TIMEOUT);
        assertThrows(IllegalStateException.class, () -> timed.admit(acquisitionId));
        // Only the events of the partition remove it: a read is not one of them.
        assertEquals(1, timed.acquisitionCount());
    }

    @Test
    void versionWithoutUsesEndsWhenItLeavesTheWindow() {
        PartitionStorage2 kept = new PartitionStorage2(IndexConfig.linearScan(), 2);
        assertEquals(1, kept.liveVersionCount());
        StorageVersion2 empty = kept.lastVersion();
        kept.commit(100);
        kept.commit(103);
        assertEquals(3, kept.liveVersionCount());
        assertFalse(empty.uses().isEnded());
        StorageVersion2 last = kept.commit(107);
        assertTrue(empty.uses().isEnded());
        assertEquals(3, kept.liveVersionCount());
        assertFalse(last.uses().isEnded());
    }

    @Test
    void versionInTheWindowDoesNotEndAtTheRelease() {
        StorageVersion2 version = storage.commit(1);
        long acquisitionId = storage.acquire(version);
        storage.release(acquisitionId);
        assertFalse(version.uses().isEnded());
        storage.commit(2);
        assertTrue(version.uses().isEnded());
        assertEquals(1, storage.liveVersionCount());
    }

    @Test
    void versionOutOfTheWindowEndsAtTheReleaseOfTheLastAcquisition() {
        StorageVersion2 version = storage.commit(1);
        long first = storage.acquire(version);
        long second = storage.acquire(version);
        storage.commit(2);
        assertEquals(2, storage.liveVersionCount());
        storage.release(first);
        assertFalse(version.uses().isEnded());
        storage.release(second);
        assertTrue(version.uses().isEnded());
        assertEquals(1, storage.liveVersionCount());
        // A release again does not end it twice.
        storage.release(second);
        assertEquals(1, storage.liveVersionCount());
    }

    @Test
    void versionOutOfTheWindowEndsWhenItsLastAcquisitionExpires() {
        FakeClock clock = new FakeClock();
        PartitionStorage2 timed = new PartitionStorage2(IndexConfig.linearScan(), 0, clock);
        StorageVersion2 version = timed.commit(1);
        long acquisitionId = timed.acquire(version, AcquireOptions.timeout(TIMEOUT));
        clock.advance(TIMEOUT);
        // The expiry alone is not an event: the commit finds it, and also moves the window.
        assertFalse(version.uses().isEnded());
        timed.commit(2);
        assertTrue(version.uses().isEnded());
        assertEquals(1, timed.liveVersionCount());
        // The release after the removal does not end it twice.
        timed.release(acquisitionId);
        assertEquals(1, timed.liveVersionCount());
    }

    @Test
    void queryKeepsAReleasedVersionOutOfTheWindowUntilItEnds() {
        StorageVersion2 version = storage.commit(1);
        long acquisitionId = storage.acquire(version);
        StorageVersion2 queried = storage.admit(acquisitionId);
        storage.release(acquisitionId);
        storage.commit(2);
        assertFalse(version.uses().isEnded());
        assertEquals(2, storage.liveVersionCount());
        storage.endQuery(queried);
        assertTrue(version.uses().isEnded());
        assertEquals(1, storage.liveVersionCount());
    }

    @Test
    void versionOutOfTheWindowIsNotAcquiredAgain() {
        StorageVersion2 version = storage.commit(1);
        long kept = storage.acquire(version);
        storage.commit(2);
        assertEquals(PartitionStorage2.NOT_ACQUIRED, storage.acquire(version));
        // The failed acquisition used no number.
        assertEquals(kept + 1, storage.acquire(storage.lastVersion()));
        storage.release(kept);
        assertEquals(PartitionStorage2.NOT_ACQUIRED, storage.acquire(version));
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
     * they read is a whole commit, and that the catalog has the window of the
     * same commit.
     */
    @Test
    void readersOnOtherThreadsSeeOnlyWholeCommits() throws InterruptedException {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4))) {
            PartitionStorage2 configured = new PartitionStorage2(config, RETAINED_PAST_VERSIONS);
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
                            VersionCatalog2 catalog = configured.catalog();
                            StorageVersion2 version = catalog.lastVersion();
                            assertTrue(version.commit() >= last, "commits go back");
                            last = version.commit();
                            checkWindow(catalog);
                            checkWholeCommit(version);
                            assertTrue(configured.lastCommit() >= last, "last commit goes back");
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

    /**
     * Checks that the window of the catalog starts from its last version and has
     * the commits before it. writeCommits() makes the commits 1, 2, 3 and so on.
     */
    private static void checkWindow(VersionCatalog2 catalog) {
        long lastCommit = catalog.lastVersion().commit();
        int windowSize = (int) Math.min(lastCommit, RETAINED_PAST_VERSIONS) + 1;
        String where = "window at commit " + lastCommit;
        for (int offset = 0; offset < windowSize; offset++) {
            assertEquals(lastCommit - offset, catalog.previous(offset).commit(), where);
        }
        assertNull(catalog.previous(windowSize), where);
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
    private static Point2 positionAt(long commit, long entityId) {
        double base = commit % 16 - 8;
        return new Point2(base + (entityId % 10) * 0.25, -base - (entityId / 10 % 10) * 0.25);
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
