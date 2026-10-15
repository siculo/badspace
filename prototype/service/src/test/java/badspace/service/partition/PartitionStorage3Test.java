package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Box3;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.service.index.IndexSnapshot3;
import badspace.service.index.SlotView3;
import badspace.service.index.SpatialIndex3;
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
class PartitionStorage3Test {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Point3 A = new Point3(1, 2, 3);
    private static final Point3 B = new Point3(3, 4, 5);
    private static final Point3 C = new Point3(5, 6, 7);
    private static final Point3 D = new Point3(7, 8, 9);

    private static final Point3 OUTSIDE = new Point3(0, 0, -11);
    private static final CoordinateLimits LIMITS = new CoordinateLimits(-10, 10);
    private static final Point3 LIMITS_MIN = new Point3(-10, -10, -10);
    private static final Point3 LIMITS_MAX = new Point3(10, 10, 10);
    private static final long[] ALL_IDS = {10, 20, 30, 40};
    private static final int READERS = 3;
    private static final int TICKS = 3000;
    /** k of the partitions in the tests with concurrent readers. */
    private static final int RETAINED_TICKS = 3;

    private RecordingIndex index;
    private PartitionStorage3 storage;

    @BeforeEach
    void setUp() {
        storage = new PartitionStorage3(LIMITS, s -> index = new RecordingIndex(s));
    }

    @Test
    void factoryGetsTheStorage() {
        assertEquals(storage, index.storage);
    }

    @Test
    void insertNotifiesEachNewSlot() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
        assertEquals(List.of("inserted 0 " + A + " id=10", "inserted 1 " + B + " id=20"), index.calls);
    }

    @Test
    void updateNotifiesOldAndNewPosition() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
        index.calls.clear();
        storage.updateAll(List.of(new Entity3(20, C)));
        assertEquals(List.of("moved 1 " + B + " -> " + C + " now=" + C), index.calls);
    }

    @Test
    void removeOfLastSlotNotifiesOnlyTheRemoval() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
        index.calls.clear();
        storage.removeAll(new long[] {20});
        assertEquals(List.of("removed 1 " + B + " size=1"), index.calls);
    }

    @Test
    void removeOfOtherSlotNotifiesTheRemovalAndThenTheRelocation() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)));
        index.calls.clear();
        storage.removeAll(new long[] {10});
        assertEquals(List.of("removed 0 " + A + " size=2", "relocated 2 -> 0 " + C + " id=30"), index.calls);
    }

    @Test
    void removeOfManyNotifiesInOrder() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C), new Entity3(40, D)));
        index.calls.clear();
        storage.removeAll(new long[] {20, 40});
        assertEquals(List.of(
                "removed 1 " + B + " size=3", "relocated 3 -> 1 " + D + " id=40",
                "removed 1 " + D + " size=2", "relocated 2 -> 1 " + C + " id=30"), index.calls);
    }

    @Test
    void failedWritesNotifyNothing() {
        storage.insertAll(List.of(new Entity3(10, A)));
        index.calls.clear();
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity3(20, B), new Entity3(10, C))));
        assertThrows(NoSuchElementException.class,
                () -> storage.updateAll(List.of(new Entity3(10, B), new Entity3(99, C))));
        assertThrows(NoSuchElementException.class, () -> storage.removeAll(new long[] {10, 99}));
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity3(20, B), new Entity3(30, OUTSIDE))));
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateAll(List.of(new Entity3(10, B), new Entity3(10, OUTSIDE))));
        assertEquals(List.of(), index.calls);
        assertEquals(List.of(new Entity3(10, A)), storage.getAll(new long[] {10, 20, 30}));
    }

    @Test
    void writesOnTheLimitsAreAccepted() {
        storage.insertAll(List.of(new Entity3(10, new Point3(-10, -10, -10)), new Entity3(20, A)));
        storage.updateAll(List.of(new Entity3(20, new Point3(10, 10, 10))));
        assertEquals(2, storage.size());
        assertEquals(LIMITS, storage.limits());
    }

    @Test
    void storageWithAnIndexConfigUsesTheLimitsOfTheIndex() {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4, 2))) {
            PartitionStorage3 configured = new PartitionStorage3(config);
            double max = config.limits().max();
            configured.insertAll(List.of(new Entity3(10, new Point3(max, max, max))));
            assertThrows(IllegalArgumentException.class,
                    () -> configured.insertAll(List.of(new Entity3(20, new Point3(Math.nextUp(max), 0, 0)))));
            assertEquals(1, configured.size());
            assertEquals(config.limits(), configured.limits());
        }
    }

    @Test
    void storageKeepsTheNumberOfRetainedTicks() {
        assertEquals(1, new PartitionStorage3(IndexConfig.linearScan()).retainedTicks());
        assertEquals(3, new PartitionStorage3(IndexConfig.linearScan(), 3).retainedTicks());
        assertThrows(IllegalArgumentException.class, () -> new PartitionStorage3(IndexConfig.linearScan(), 0));
    }

    @Test
    void storageIsCreatedAtTheGivenTick() {
        PartitionStorage3 created = new PartitionStorage3(IndexConfig.linearScan(), 1, MonotonicClock.SYSTEM, 7);
        assertEquals(7, created.tick());
        assertEquals(7, created.creationTick());
        assertNull(created.lastSnapshot());
    }

    @Test
    void queriesReturnTheEntitiesOfTheSlotsInTheGivenOrder() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)));
        index.result = new int[] {2, 0};
        List<Entity3> expected = List.of(new Entity3(30, C), new Entity3(10, A));
        assertEquals(expected, storage.findInRegion(new Box3(A, B)));
        assertEquals(expected, storage.findNearest(A, 2));
    }

    @Test
    void commitGoesToTheIndexWithTheNextNumberOfTheSequence() {
        storage.commit();
        storage.commit();
        storage.setTick(1);
        storage.commit();
        assertEquals(List.of("commit 1", "commit 2", "commit 3"), index.calls);
    }

    @Test
    void setTickRejectsAnEarlierTickAndTheSameTickDoesNothing() {
        storage.setTick(3);
        SnapshotCatalog3 catalog = storage.catalog();
        assertFalse(storage.setTick(3));
        assertSame(catalog, storage.catalog());
        assertThrows(IllegalArgumentException.class, () -> storage.setTick(2));
        assertEquals(3, storage.tick());
    }

    @Test
    void snapshotOfACommitDoesNotSeeLaterWrites() {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4, 2))) {
            PartitionStorage3 configured = new PartitionStorage3(config);
            configured.insertAll(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)));
            StorageSnapshot3 first = configured.commit();
            // The removal of 10 moves 30 into its slot.
            configured.updateAll(List.of(new Entity3(30, D)));
            configured.removeAll(new long[] {10});
            configured.insertAll(List.of(new Entity3(40, A)));
            StorageSnapshot3 second = configured.commit();
            configured.removeAll(new long[] {20});
            configured.updateAll(List.of(new Entity3(40, B)));

            String where = config.toString();
            checkSnapshot(first, 1, List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)), where);
            checkSnapshot(second, 2, List.of(new Entity3(40, A), new Entity3(20, B), new Entity3(30, D)), where);
            assertEquals(List.of(new Entity3(30, D), new Entity3(40, B)), configured.getAll(ALL_IDS), where);
        }
    }

    @Test
    void commitWithoutInsertionsOrRemovalsSharesTheIds() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
        SlotSnapshot3 first = storage.commit().slots();
        storage.updateAll(List.of(new Entity3(20, C)));
        SlotSnapshot3 moved = storage.commit().slots();
        assertSame(first.slotEntityIds, moved.slotEntityIds);
        assertSame(first.slotById, moved.slotById);
        assertNotSame(first.coords, moved.coords);
        assertEquals(B, first.positionAt(1));
        assertEquals(C, moved.positionAt(1));

        storage.removeAll(new long[] {10});
        SlotSnapshot3 removed = storage.commit().slots();
        assertNotSame(moved.slotEntityIds, removed.slotEntityIds);
        assertNotSame(moved.slotById, removed.slotById);
        storage.insertAll(List.of(new Entity3(30, D)));
        SlotSnapshot3 inserted = storage.commit().slots();
        assertNotSame(removed.slotEntityIds, inserted.slotEntityIds);
        assertNotSame(removed.slotById, inserted.slotById);
        assertEquals(2, moved.size());
        assertEquals(1, removed.size());
        assertEquals(2, inserted.size());
    }

    @Test
    void newPartitionPublishesTheEmptySnapshotAtTheFirstMoveOfTheTick() {
        assertNull(storage.lastSnapshot());
        assertEquals(0, storage.liveSnapshotCount());
        storage.setTick(1);
        assertEquals(0, storage.lastSnapshot().size());
        assertEquals(0, storage.lastSnapshot().commitSequence());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void catalogHoldsTheSameSnapshotsThatTheCommitsReturn() {
        PartitionStorage3 configured = new PartitionStorage3(IndexConfig.linearScan(), 2);
        StorageSnapshot3 first = configured.commit();
        configured.setTick(1);
        assertSame(first, configured.catalog().at(0));
        StorageSnapshot3 second = configured.commit();
        configured.setTick(2);
        assertSame(first, configured.catalog().at(0));
        assertSame(second, configured.catalog().at(1));
        assertSame(second, configured.lastSnapshot());
    }

    @Test
    void onlyTheLastCommitOfATickIsPublished() {
        storage.insertAll(List.of(new Entity3(10, A)));
        storage.commit();
        storage.insertAll(List.of(new Entity3(20, B)));
        StorageSnapshot3 last = storage.commit();
        storage.setTick(1);
        assertSame(last, storage.lastSnapshot());
        assertEquals(2, last.size());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void writesAfterTheLastCommitGoToTheNextCommit() {
        storage.insertAll(List.of(new Entity3(10, A)));
        storage.commit();
        storage.insertAll(List.of(new Entity3(20, B)));
        storage.setTick(1);
        assertEquals(List.of(new Entity3(10, A)), storage.lastSnapshot().getAll(ALL_IDS));
        storage.commit();
        storage.setTick(2);
        assertEquals(List.of(new Entity3(10, A), new Entity3(20, B)), storage.lastSnapshot().getAll(ALL_IDS));
    }

    @Test
    void readersSeeTheWritesOnlyAfterTheCommitAndTheMoveOfTheTick() {
        assertNull(storage.lastSnapshot());
        storage.setTick(1);
        StorageSnapshot3 empty = storage.lastSnapshot();
        assertEquals(0, empty.size());
        assertEquals(List.of(), empty.findInRegion(new Box3(LIMITS_MIN, LIMITS_MAX)));
        assertEquals(List.of(), empty.findNearest(A, 1));

        storage.insertAll(List.of(new Entity3(10, A)));
        assertEquals(List.of(new Entity3(10, A)), storage.getAll(ALL_IDS));
        StorageSnapshot3 committed = storage.commit();
        // Committed, but not published until the tick moves.
        assertSame(empty, storage.lastSnapshot());
        storage.setTick(2);
        assertSame(committed, storage.lastSnapshot());
        assertEquals(List.of(new Entity3(10, A)), committed.getAll(ALL_IDS));

        // A tick without a commit keeps the snapshot before.
        storage.updateAll(List.of(new Entity3(10, B)));
        storage.setTick(3);
        assertEquals(List.of(new Entity3(10, B)), storage.getAll(ALL_IDS));
        assertSame(committed, storage.lastSnapshot());
        assertSame(committed, storage.catalog().at(2));
    }

    @Test
    void acquisitionKeepsItsSnapshotUntilTheRelease() {
        StorageSnapshot3 first = publish(storage);
        long acquisitionId = storage.acquire(first, 0);
        publish(storage);
        assertSame(first, storage.admit(acquisitionId, 0));
        storage.endQuery(first);
        storage.release(acquisitionId);
        assertThrows(IllegalStateException.class, () -> storage.admit(acquisitionId, 0));
        assertEquals(0, storage.acquisitionCount());
    }

    @Test
    void eachAcquisitionHasItsOwnNumber() {
        StorageSnapshot3 snapshot = publish(storage);
        long first = storage.acquire(snapshot, 0);
        long second = storage.acquire(snapshot, 0);
        assertNotEquals(first, second);
        storage.release(first);
        assertSame(snapshot, storage.admit(second, 0));
        assertEquals(1, storage.acquisitionCount());
    }

    @Test
    void admitRejectsAnotherTickAndKeepsTheAcquisition() {
        StorageSnapshot3 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        assertThrows(IllegalArgumentException.class, () -> storage.admit(acquisitionId, 1));
        assertSame(snapshot, storage.admit(acquisitionId, 0));
    }

    @Test
    void releaseAgainDoesNothing() {
        long acquisitionId = storage.acquire(publish(storage), 0);
        storage.release(acquisitionId);
        storage.release(acquisitionId);
        assertEquals(0, storage.acquisitionCount());
    }

    @Test
    void acquisitionNeverMadeIsRejected() {
        long acquisitionId = storage.acquire(publish(storage), 0);
        assertThrows(IllegalArgumentException.class, () -> storage.release(acquisitionId + 1));
        assertThrows(IllegalArgumentException.class, () -> storage.admit(acquisitionId + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> storage.release(0));
        assertEquals(1, storage.acquisitionCount());
    }

    @Test
    void expiredAcquisitionsAreRemovedAtEachEventOfThePartition() {
        FakeClock clock = new FakeClock();
        PartitionStorage3 timed = new PartitionStorage3(IndexConfig.linearScan(), 1, clock);
        AcquireOptions options = AcquireOptions.timeout(TIMEOUT);
        timed.setTick(1);

        // The commit removes them.
        timed.acquire(timed.lastSnapshot(), 0, options);
        clock.advance(TIMEOUT);
        assertEquals(1, timed.acquisitionCount());
        timed.commit();
        assertEquals(0, timed.acquisitionCount());

        // The move of the tick removes them.
        timed.acquire(timed.lastSnapshot(), 0, options);
        clock.advance(TIMEOUT);
        timed.setTick(2);
        assertEquals(0, timed.acquisitionCount());

        // An acquisition removes them, and keeps the new one.
        timed.acquire(timed.lastSnapshot(), 1, options);
        clock.advance(TIMEOUT);
        long fresh = timed.acquire(timed.lastSnapshot(), 1, options);
        assertEquals(1, timed.acquisitionCount());

        // The release of another acquisition removes them.
        long kept = timed.acquire(timed.lastSnapshot(), 1);
        clock.advance(TIMEOUT);
        long released = timed.acquire(timed.lastSnapshot(), 1);
        timed.release(released);
        assertEquals(1, timed.acquisitionCount());
        assertThrows(IllegalStateException.class, () -> timed.admit(fresh, 1));
        assertSame(timed.lastSnapshot(), timed.admit(kept, 1));
    }

    @Test
    void readOfAnExpiredAcquisitionDoesNotRemoveIt() {
        FakeClock clock = new FakeClock();
        PartitionStorage3 timed = new PartitionStorage3(IndexConfig.linearScan(), 1, clock);
        long acquisitionId = timed.acquire(publish(timed), 0, AcquireOptions.timeout(TIMEOUT));
        clock.advance(TIMEOUT);
        assertThrows(IllegalStateException.class, () -> timed.admit(acquisitionId, 0));
        // Only the events of the partition remove it: a read is not one of them.
        assertEquals(1, timed.acquisitionCount());
    }

    @Test
    void snapshotWithoutUsesEndsWhenItLeavesTheWindow() {
        PartitionStorage3 kept = new PartitionStorage3(IndexConfig.linearScan(), 2);
        StorageSnapshot3 empty = publish(kept);
        publish(kept);
        assertEquals(2, kept.liveSnapshotCount());
        assertFalse(empty.uses().isEnded());
        StorageSnapshot3 last = publish(kept);
        // The window has the ticks 1 and 2: the snapshot of tick 0 left it.
        assertTrue(empty.uses().isEnded());
        assertEquals(2, kept.liveSnapshotCount());
        assertFalse(last.uses().isEnded());
    }

    @Test
    void snapshotOfManyTicksEndsWhenItsLastTickLeavesTheWindow() {
        PartitionStorage3 kept = new PartitionStorage3(IndexConfig.linearScan(), 2);
        kept.setTick(1);
        StorageSnapshot3 empty = kept.lastSnapshot();
        // Without commits the empty snapshot is valid for the ticks from 0 to 3.
        kept.setTick(4);
        kept.commit();
        kept.setTick(5);
        assertFalse(empty.uses().isEnded());
        kept.setTick(6);
        assertTrue(empty.uses().isEnded());
        assertEquals(1, kept.liveSnapshotCount());
    }

    @Test
    void snapshotInTheWindowDoesNotEndAtTheRelease() {
        StorageSnapshot3 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        storage.release(acquisitionId);
        assertFalse(snapshot.uses().isEnded());
        publish(storage);
        assertTrue(snapshot.uses().isEnded());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void snapshotOutOfTheWindowEndsAtTheReleaseOfTheLastAcquisition() {
        StorageSnapshot3 snapshot = publish(storage);
        long first = storage.acquire(snapshot, 0);
        long second = storage.acquire(snapshot, 0);
        publish(storage);
        assertEquals(2, storage.liveSnapshotCount());
        storage.release(first);
        assertFalse(snapshot.uses().isEnded());
        storage.release(second);
        assertTrue(snapshot.uses().isEnded());
        assertEquals(1, storage.liveSnapshotCount());
        // A release again does not end it twice.
        storage.release(second);
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void snapshotOutOfTheWindowEndsWhenItsLastAcquisitionExpires() {
        FakeClock clock = new FakeClock();
        PartitionStorage3 timed = new PartitionStorage3(IndexConfig.linearScan(), 1, clock);
        StorageSnapshot3 snapshot = publish(timed);
        long acquisitionId = timed.acquire(snapshot, 0, AcquireOptions.timeout(TIMEOUT));
        clock.advance(TIMEOUT);
        // The expiry alone is not an event: the commit finds it, and the move of the tick
        // moves the window.
        assertFalse(snapshot.uses().isEnded());
        publish(timed);
        assertTrue(snapshot.uses().isEnded());
        assertEquals(1, timed.liveSnapshotCount());
        // The release after the removal does not end it twice.
        timed.release(acquisitionId);
        assertEquals(1, timed.liveSnapshotCount());
    }

    @Test
    void queryKeepsAReleasedSnapshotOutOfTheWindowUntilItEnds() {
        StorageSnapshot3 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        StorageSnapshot3 queried = storage.admit(acquisitionId, 0);
        storage.release(acquisitionId);
        publish(storage);
        assertFalse(snapshot.uses().isEnded());
        assertEquals(2, storage.liveSnapshotCount());
        storage.endQuery(queried);
        assertTrue(snapshot.uses().isEnded());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void markOfRemovalEndsTheSnapshotsWithoutUses() {
        PartitionStorage3 kept = new PartitionStorage3(IndexConfig.linearScan(), 2);
        publish(kept);
        publish(kept);
        assertTrue(kept.markRemoved());
        assertTrue(kept.isRemoved());
        assertEquals(0, kept.liveSnapshotCount());
    }

    @Test
    void markOfAPartitionThatPublishedNothingFinishesIt() {
        storage.commit();
        assertTrue(storage.markRemoved());
        // The tick still moves, but nothing is published.
        assertFalse(storage.setTick(1));
        assertNull(storage.lastSnapshot());
    }

    @Test
    void acquisitionMadeBeforeTheMarkKeepsThePartitionUntilItsRelease() {
        StorageSnapshot3 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        assertFalse(storage.markRemoved());
        // The mark closes the window.
        assertEquals(PartitionStorage3.NOT_ACQUIRED, storage.acquire(snapshot, 0));
        assertNull(storage.catalog().at(0));
        assertFalse(storage.endQuery(storage.admit(acquisitionId, 0)));
        assertEquals(1, storage.liveSnapshotCount());
        assertTrue(storage.release(acquisitionId));
        assertEquals(0, storage.liveSnapshotCount());
        // A release again does not finish it twice.
        assertFalse(storage.release(acquisitionId));
    }

    @Test
    void endOfTheLastQueryFinishesAMarkedPartition() {
        long acquisitionId = storage.acquire(publish(storage), 0);
        StorageSnapshot3 queried = storage.admit(acquisitionId, 0);
        assertFalse(storage.markRemoved());
        assertFalse(storage.release(acquisitionId));
        assertTrue(storage.endQuery(queried));
        assertEquals(0, storage.liveSnapshotCount());
    }

    @Test
    void expiryFoundAfterTheMarkFinishesThePartition() {
        FakeClock clock = new FakeClock();
        PartitionStorage3 timed = new PartitionStorage3(IndexConfig.linearScan(), 1, clock);
        long acquisitionId = timed.acquire(publish(timed), 0, AcquireOptions.timeout(TIMEOUT));
        assertFalse(timed.markRemoved());
        assertFalse(timed.removeExpired());
        clock.advance(TIMEOUT);
        assertTrue(timed.removeExpired());
        assertFalse(timed.removeExpired());
        assertThrows(IllegalStateException.class, () -> timed.admit(acquisitionId, 0));
    }

    @Test
    void moveOfTheTickAfterTheMarkFindsTheExpiryAndFinishesThePartition() {
        FakeClock clock = new FakeClock();
        PartitionStorage3 timed = new PartitionStorage3(IndexConfig.linearScan(), 1, clock);
        timed.acquire(publish(timed), 0, AcquireOptions.timeout(TIMEOUT));
        assertFalse(timed.markRemoved());
        assertFalse(timed.setTick(2));
        clock.advance(TIMEOUT);
        assertTrue(timed.setTick(3));
        assertEquals(0, timed.liveSnapshotCount());
    }

    @Test
    void eventsBeforeTheMarkDoNotFinishThePartition() {
        StorageSnapshot3 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        publish(storage);
        assertFalse(storage.release(acquisitionId));
        assertTrue(snapshot.uses().isEnded());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void snapshotOutOfTheWindowIsNotAcquiredAgain() {
        StorageSnapshot3 snapshot = publish(storage);
        long kept = storage.acquire(snapshot, 0);
        publish(storage);
        assertEquals(PartitionStorage3.NOT_ACQUIRED, storage.acquire(snapshot, 0));
        // The failed acquisition used no number.
        assertEquals(kept + 1, storage.acquire(storage.lastSnapshot(), 1));
        storage.release(kept);
        assertEquals(PartitionStorage3.NOT_ACQUIRED, storage.acquire(snapshot, 0));
    }

    /**
     * A writer and some readers on other threads. At each tick the writer moves all
     * the entities to positions that depend on the tick, changes their number, commits
     * and moves to the next tick; it moves the entities with more than one call, so the
     * states between the calls are mixed. The readers check that each snapshot they read
     * is the whole commit of its tick, and that the catalog has the window of the same tick.
     */
    @Test
    void readersOnOtherThreadsSeeOnlyWholeCommits() throws InterruptedException {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4))) {
            PartitionStorage3 configured = new PartitionStorage3(config, RETAINED_TICKS);
            // Publishes the empty snapshot of tick 0 before the readers start.
            configured.setTick(1);
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
                            SnapshotCatalog3 catalog = configured.catalog();
                            assertTrue(catalog.tick() >= last, "ticks go back");
                            last = catalog.tick();
                            checkWindow(catalog);
                            checkWholeCommit(catalog.latest(), catalog.tick() - 1);
                            assertTrue(configured.tick() >= last, "tick of the storage goes back");
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
        storage.insertAll(List.of(new Entity3(10, A)));
        index.calls.clear();
        assertEquals(List.of(), storage.findNearest(A, 0));
        assertEquals(List.of(), index.calls);
    }

    /**
     * Commits in the current tick of the storage, moves to the next tick and returns the
     * published snapshot, which is the one of the tick before the new tick.
     */
    private static StorageSnapshot3 publish(PartitionStorage3 storage) {
        StorageSnapshot3 snapshot = storage.commit();
        storage.setTick(storage.tick() + 1);
        return snapshot;
    }

    /** Checks all the reads of the snapshot, which must find exactly the expected entities, nearest to A first. */
    private static void checkSnapshot(StorageSnapshot3 snapshot, long commitSequence, List<Entity3> expected,
            String where) {
        assertEquals(commitSequence, snapshot.commitSequence(), where);
        assertEquals(Set.copyOf(expected), Set.copyOf(snapshot.getAll(ALL_IDS)), where);
        assertEquals(expected.size(), snapshot.getAll(ALL_IDS).size(), where);
        List<Entity3> inRegion = snapshot.findInRegion(new Box3(LIMITS_MIN, LIMITS_MAX));
        assertEquals(Set.copyOf(expected), Set.copyOf(inRegion), where);
        assertEquals(expected.size(), inRegion.size(), where);
        assertEquals(expected, snapshot.findNearest(A, ALL_IDS.length), where);
        assertEquals(List.of(), snapshot.findNearest(A, 0), where);
    }

    /**
     * Checks that the window of the catalog has the last RETAINED_TICKS ticks, and not
     * the ticks before them or the current one. writeCommits() commits at every tick.
     */
    private static void checkWindow(SnapshotCatalog3 catalog) {
        long tick = catalog.tick();
        long windowStart = Math.max(tick - RETAINED_TICKS, 0);
        String where = "window at tick " + tick;
        assertEquals(tick - windowStart, catalog.size(), where);
        for (long n = windowStart; n < tick; n++) {
            assertTrue(catalog.at(n) != null, where);
        }
        assertNull(catalog.at(tick), where);
        if (windowStart > 0) {
            assertNull(catalog.at(windowStart - 1), where);
        }
    }

    /** Writes the state of each tick from 1 to TICKS, commits it and moves to the next tick. */
    private static void writeCommits(PartitionStorage3 storage) {
        List<Long> ids = new ArrayList<>();
        long nextId = 1;
        for (long tick = 1; tick <= TICKS; tick++) {
            List<Entity3> moved = new ArrayList<>();
            for (long id : ids) {
                moved.add(new Entity3(id, positionAt(tick, id)));
            }
            int half = moved.size() / 2;
            storage.updateAll(moved.subList(0, half));
            storage.updateAll(moved.subList(half, moved.size()));
            while (ids.size() > sizeAt(tick)) {
                storage.removeAll(new long[] {ids.removeFirst()});
            }
            while (ids.size() < sizeAt(tick)) {
                long id = nextId++;
                storage.insertAll(List.of(new Entity3(id, positionAt(tick, id))));
                ids.add(id);
            }
            storage.commit();
            storage.setTick(tick + 1);
        }
    }

    /** Checks that all the reads of the snapshot see the entities of its tick, and only them. */
    private static void checkWholeCommit(StorageSnapshot3 snapshot, long tick) {
        String where = "tick " + tick;
        assertEquals(sizeAt(tick), snapshot.size(), where);
        List<Entity3> found = snapshot.findInRegion(new Box3(LIMITS_MIN, LIMITS_MAX));
        assertEquals(sizeAt(tick), found.size(), where);
        for (Entity3 e : found) {
            assertEquals(positionAt(tick, e.id()), e.position(), where);
        }
        long[] ids = found.stream().mapToLong(Entity3::id).toArray();
        assertEquals(found.size(), snapshot.getAll(ids).size(), where);
        for (Entity3 e : snapshot.getAll(ids)) {
            assertEquals(positionAt(tick, e.id()), e.position(), where);
        }
        for (Entity3 e : snapshot.findNearest(positionAt(tick, 0), sizeAt(tick) + 1)) {
            assertEquals(positionAt(tick, e.id()), e.position(), where);
        }
    }

    /** The number of entities at the tick: 0 at tick 0, then from 0 to 22. */
    private static int sizeAt(long tick) {
        return (int) (tick * 7 % 23);
    }

    /** The position of an entity at the tick, inside the limits; it changes cell often. */
    private static Point3 positionAt(long tick, long entityId) {
        double base = tick % 16 - 8;
        return new Point3(base + (entityId % 10) * 0.25, -base - (entityId / 10 % 10) * 0.25, base / 2);
    }

    /**
     * Index that records each call as a string, with some state of the
     * storage at that moment, and gives a fixed result to the queries.
     */
    private static final class RecordingIndex implements SpatialIndex3 {

        final SlotView3 storage;
        final List<String> calls = new ArrayList<>();
        int[] result = new int[0];

        RecordingIndex(SlotView3 storage) {
            this.storage = storage;
        }

        @Override
        public void inserted(int slot, Point3 position) {
            calls.add("inserted " + slot + " " + position + " id=" + storage.idAt(slot));
        }

        @Override
        public void moved(int slot, Point3 from, Point3 to) {
            calls.add("moved " + slot + " " + from + " -> " + to + " now=" + storage.positionAt(slot));
        }

        @Override
        public void removed(int slot, Point3 position) {
            calls.add("removed " + slot + " " + position + " size=" + storage.size());
        }

        @Override
        public void relocated(int from, int to, Point3 position) {
            calls.add("relocated " + from + " -> " + to + " " + position + " id=" + storage.idAt(to));
        }

        @Override
        public IndexSnapshot3 commit(long n) {
            calls.add("commit " + n);
            // These tests do not read the snapshot of the index.
            return null;
        }

        @Override
        public int[] findInRegion(Region3 region) {
            calls.add("findInRegion");
            return result;
        }

        @Override
        public int[] findNearest(Point3 point, int count) {
            calls.add("findNearest");
            return result;
        }
    }
}
