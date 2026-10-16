package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import badspace.service.index.SpatialIndexes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks storage semantics with real spatial indices. */
class PartitionStorage2Test {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Point2 A = new Point2(1, 2);
    private static final Point2 B = new Point2(3, 4);
    private static final Point2 C = new Point2(5, 6);
    private static final Point2 D = new Point2(7, 8);

    private static final CoordinateLimits LIMITS = new CoordinateLimits(-10, 10);
    private static final Point2 LIMITS_MIN = new Point2(-10, -10);
    private static final Point2 LIMITS_MAX = new Point2(10, 10);
    private static final long[] ALL_IDS = {10, 20, 30, 40};
    private static final int READERS = 3;
    private static final int TICKS = 3000;
    /** k of the partitions in the tests with concurrent readers. */
    private static final int RETAINED_TICKS = 3;

    private PartitionStorage2 storage;

    @BeforeEach
    void setUp() {
        storage = new PartitionStorage2(LIMITS, s -> SpatialIndexes.create(IndexConfig.linearScan(), s));
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
    void storageKeepsTheNumberOfRetainedTicks() {
        assertEquals(1, new PartitionStorage2(IndexConfig.linearScan()).catalog().retainedTicks());
        assertEquals(3, new PartitionStorage2(IndexConfig.linearScan(), 3).catalog().retainedTicks());
        assertThrows(IllegalArgumentException.class, () -> new PartitionStorage2(IndexConfig.linearScan(), 0));
    }

    @Test
    void storageIsCreatedAtTheGivenTick() {
        PartitionStorage2 created = new PartitionStorage2(IndexConfig.linearScan(), 1, MonotonicClock.SYSTEM, 7);
        assertEquals(7, created.tick());
        assertEquals(7, created.creationTick());
        assertNull(created.catalog().latest());
    }

    @Test
    void setTickRejectsAnEarlierTickAndTheSameTickDoesNothing() {
        storage.setTick(3);
        SnapshotCatalog2 catalog = storage.catalog();
        assertFalse(storage.setTick(3));
        assertSame(catalog, storage.catalog());
        assertThrows(IllegalArgumentException.class, () -> storage.setTick(2));
        assertEquals(3, storage.tick());
    }

    @Test
    void snapshotOfACommitDoesNotSeeLaterWrites() {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4, 2))) {
            PartitionStorage2 configured = new PartitionStorage2(config);
            configured.insertAll(List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C)));
            StorageSnapshot2 first = configured.commit();
            // The removal of 10 moves 30 into its slot.
            configured.updateAll(List.of(new Entity2(30, D)));
            configured.removeAll(new long[] {10});
            configured.insertAll(List.of(new Entity2(40, A)));
            StorageSnapshot2 second = configured.commit();
            configured.removeAll(new long[] {20});
            configured.updateAll(List.of(new Entity2(40, B)));

            String where = config.toString();
            checkSnapshot(first, List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C)), where);
            checkSnapshot(second, List.of(new Entity2(40, A), new Entity2(20, B), new Entity2(30, D)), where);
            assertEquals(List.of(new Entity2(30, D), new Entity2(40, B)), configured.getAll(ALL_IDS), where);
        }
    }

    @Test
    void snapshotsKeepTheirIdsAndPositionsAcrossUpdatesInsertionsAndRemovals() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        SlotSnapshot2 first = storage.commit().slots();
        storage.updateAll(List.of(new Entity2(20, C)));
        SlotSnapshot2 moved = storage.commit().slots();
        assertEquals(B, first.positionAt(1));
        assertEquals(C, moved.positionAt(1));

        storage.removeAll(new long[] {10});
        SlotSnapshot2 removed = storage.commit().slots();
        storage.insertAll(List.of(new Entity2(30, D)));
        SlotSnapshot2 inserted = storage.commit().slots();
        assertEquals(10, first.idAt(0));
        assertEquals(20, first.idAt(1));
        assertEquals(10, moved.idAt(0));
        assertEquals(20, moved.idAt(1));
        assertEquals(20, removed.idAt(0));
        assertEquals(30, inserted.idAt(1));
        assertEquals(2, moved.size());
        assertEquals(1, removed.size());
        assertEquals(2, inserted.size());
    }

    @Test
    void newPartitionPublishesTheEmptySnapshotAtTheFirstMoveOfTheTick() {
        assertNull(storage.catalog().latest());
        assertEquals(0, storage.liveSnapshotCount());
        storage.setTick(1);
        assertEquals(0, storage.catalog().latest().size());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void catalogHoldsTheSameSnapshotsThatTheCommitsReturn() {
        PartitionStorage2 configured = new PartitionStorage2(IndexConfig.linearScan(), 2);
        StorageSnapshot2 first = configured.commit();
        configured.setTick(1);
        assertSame(first, configured.catalog().at(0));
        StorageSnapshot2 second = configured.commit();
        configured.setTick(2);
        assertSame(first, configured.catalog().at(0));
        assertSame(second, configured.catalog().at(1));
        assertSame(second, configured.catalog().latest());
    }

    @Test
    void onlyTheLastCommitOfATickIsPublished() {
        storage.insertAll(List.of(new Entity2(10, A)));
        storage.commit();
        storage.insertAll(List.of(new Entity2(20, B)));
        StorageSnapshot2 last = storage.commit();
        storage.setTick(1);
        assertSame(last, storage.catalog().latest());
        assertEquals(2, last.size());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void writesAfterTheLastCommitGoToTheNextCommit() {
        storage.insertAll(List.of(new Entity2(10, A)));
        storage.commit();
        storage.insertAll(List.of(new Entity2(20, B)));
        storage.setTick(1);
        assertEquals(List.of(new Entity2(10, A)), storage.catalog().latest().getAll(ALL_IDS));
        storage.commit();
        storage.setTick(2);
        assertEquals(List.of(new Entity2(10, A), new Entity2(20, B)), storage.catalog().latest().getAll(ALL_IDS));
    }

    @Test
    void readersSeeTheWritesOnlyAfterTheCommitAndTheMoveOfTheTick() {
        assertNull(storage.catalog().latest());
        storage.setTick(1);
        StorageSnapshot2 empty = storage.catalog().latest();
        assertEquals(0, empty.size());
        assertEquals(List.of(), empty.findInRegion(new Box2(LIMITS_MIN, LIMITS_MAX)));
        assertEquals(List.of(), empty.findNearest(A, 1));

        storage.insertAll(List.of(new Entity2(10, A)));
        assertEquals(List.of(new Entity2(10, A)), storage.getAll(ALL_IDS));
        StorageSnapshot2 committed = storage.commit();
        // Committed, but not published until the tick moves.
        assertSame(empty, storage.catalog().latest());
        storage.setTick(2);
        assertSame(committed, storage.catalog().latest());
        assertEquals(List.of(new Entity2(10, A)), committed.getAll(ALL_IDS));

        // A tick without a commit keeps the snapshot before.
        storage.updateAll(List.of(new Entity2(10, B)));
        storage.setTick(3);
        assertEquals(List.of(new Entity2(10, B)), storage.getAll(ALL_IDS));
        assertSame(committed, storage.catalog().latest());
        assertSame(committed, storage.catalog().at(2));
    }

    @Test
    void acquisitionKeepsItsSnapshotUntilTheRelease() {
        StorageSnapshot2 first = publish(storage);
        long acquisitionId = storage.acquire(first, 0);
        publish(storage);
        assertAdmission(storage, acquisitionId, 0, first);
        storage.release(acquisitionId);
        assertThrows(IllegalStateException.class, () -> storage.admit(acquisitionId, 0));
        assertEquals(0, storage.acquisitionCount());
    }

    @Test
    void eachAcquisitionHasItsOwnNumber() {
        StorageSnapshot2 snapshot = publish(storage);
        long first = storage.acquire(snapshot, 0);
        long second = storage.acquire(snapshot, 0);
        assertNotEquals(first, second);
        storage.release(first);
        assertAdmission(storage, second, 0, snapshot);
        assertEquals(1, storage.acquisitionCount());
        storage.release(second);
        publish(storage);
        assertTrue(snapshot.uses().isEnded());
        assertEquals(0, storage.acquisitionCount());
    }

    @Test
    void admitRejectsAnotherTickAndKeepsTheAcquisition() {
        StorageSnapshot2 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        assertThrows(IllegalArgumentException.class, () -> storage.admit(acquisitionId, 1));
        assertAdmission(storage, acquisitionId, 0, snapshot);
        storage.release(acquisitionId);
        publish(storage);
        assertTrue(snapshot.uses().isEnded());
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
        storage.release(acquisitionId);
    }

    @Test
    void expiredAcquisitionsAreRemovedAtEachEventOfThePartition() {
        FakeClock clock = new FakeClock();
        PartitionStorage2 timed = new PartitionStorage2(IndexConfig.linearScan(), 1, clock);
        AcquireOptions options = AcquireOptions.timeout(TIMEOUT);
        timed.setTick(1);

        // The commit removes them.
        timed.acquire(timed.catalog().latest(), 0, options);
        clock.advance(TIMEOUT);
        assertEquals(1, timed.acquisitionCount());
        timed.commit();
        assertEquals(0, timed.acquisitionCount());

        // The move of the tick removes them.
        timed.acquire(timed.catalog().latest(), 0, options);
        clock.advance(TIMEOUT);
        timed.setTick(2);
        assertEquals(0, timed.acquisitionCount());

        // An acquisition removes them, and keeps the new one.
        timed.acquire(timed.catalog().latest(), 1, options);
        clock.advance(TIMEOUT);
        long fresh = timed.acquire(timed.catalog().latest(), 1, options);
        assertEquals(1, timed.acquisitionCount());

        // The release of another acquisition removes them.
        long kept = timed.acquire(timed.catalog().latest(), 1);
        clock.advance(TIMEOUT);
        long released = timed.acquire(timed.catalog().latest(), 1);
        timed.release(released);
        assertEquals(1, timed.acquisitionCount());
        assertThrows(IllegalStateException.class, () -> timed.admit(fresh, 1));
        assertAdmission(timed, kept, 1, timed.catalog().latest());
        timed.release(kept);
        assertEquals(0, timed.acquisitionCount());
    }

    @Test
    void readOfAnExpiredAcquisitionDoesNotRemoveIt() {
        FakeClock clock = new FakeClock();
        PartitionStorage2 timed = new PartitionStorage2(IndexConfig.linearScan(), 1, clock);
        long acquisitionId = timed.acquire(publish(timed), 0, AcquireOptions.timeout(TIMEOUT));
        clock.advance(TIMEOUT);
        assertThrows(IllegalStateException.class, () -> timed.admit(acquisitionId, 0));
        // Only the events of the partition remove it: a read is not one of them.
        assertEquals(1, timed.acquisitionCount());
    }

    @Test
    void snapshotWithoutUsesEndsWhenItLeavesTheWindow() {
        PartitionStorage2 kept = new PartitionStorage2(IndexConfig.linearScan(), 2);
        StorageSnapshot2 empty = publish(kept);
        publish(kept);
        assertEquals(2, kept.liveSnapshotCount());
        assertFalse(empty.uses().isEnded());
        StorageSnapshot2 last = publish(kept);
        // The window has the ticks 1 and 2: the snapshot of tick 0 left it.
        assertTrue(empty.uses().isEnded());
        assertEquals(2, kept.liveSnapshotCount());
        assertFalse(last.uses().isEnded());
    }

    @Test
    void snapshotOfManyTicksEndsWhenItsLastTickLeavesTheWindow() {
        PartitionStorage2 kept = new PartitionStorage2(IndexConfig.linearScan(), 2);
        kept.setTick(1);
        StorageSnapshot2 empty = kept.catalog().latest();
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
        StorageSnapshot2 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        storage.release(acquisitionId);
        assertFalse(snapshot.uses().isEnded());
        publish(storage);
        assertTrue(snapshot.uses().isEnded());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void snapshotOutOfTheWindowEndsAtTheReleaseOfTheLastAcquisition() {
        StorageSnapshot2 snapshot = publish(storage);
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
        PartitionStorage2 timed = new PartitionStorage2(IndexConfig.linearScan(), 1, clock);
        StorageSnapshot2 snapshot = publish(timed);
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
        StorageSnapshot2 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        StorageSnapshot2 queried = storage.admit(acquisitionId, 0);
        storage.release(acquisitionId);
        publish(storage);
        assertFalse(snapshot.uses().isEnded());
        assertEquals(2, storage.liveSnapshotCount());
        storage.endQuery(queried);
        assertTrue(snapshot.uses().isEnded());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void removalBecomesEffectiveAtTheNextMoveOfTheTick() {
        PartitionStorage2 kept = new PartitionStorage2(IndexConfig.linearScan(), 2);
        publish(kept);
        StorageSnapshot2 last = publish(kept);
        kept.requestRemoval(false);
        assertEquals(PartitionState.REMOVAL_PENDING, kept.state());
        // The same tick again does not make the removal effective.
        assertFalse(kept.setTick(2));
        assertEquals(PartitionState.REMOVAL_PENDING, kept.state());
        assertFalse(kept.setTick(3));
        assertEquals(PartitionState.RETIRED, kept.state());
        assertEquals(3, kept.catalog().removalTick());
        // The tick of the removal request has the state of the last commit.
        assertSame(last, kept.catalog().at(2));
        assertSame(last, kept.catalog().at(1));
        // At tick 5 the window starts at the removal tick: the partition is destroyed.
        assertTrue(kept.setTick(5));
        assertEquals(PartitionState.DESTROYED, kept.state());
        assertEquals(0, kept.liveSnapshotCount());
    }

    @Test
    void partitionRemovedBeforeItsFirstMoveExistsAtItsCreationTick() {
        storage.requestRemoval(true);
        assertFalse(storage.setTick(1));
        assertEquals(0, storage.catalog().latest().size());
        assertSame(storage.catalog().latest(), storage.catalog().at(0));
        // k = 1: at tick 2 the window starts at the removal tick.
        assertTrue(storage.setTick(2));
    }

    @Test
    void removalWithRequireEmptyChecksTheStateOfTheLastCommit() {
        storage.insertAll(List.of(new Entity2(10, A)));
        storage.commit();
        assertThrows(IllegalStateException.class, () -> storage.requestRemoval(true));
        assertEquals(PartitionState.ACTIVE, storage.state());
        // A removal not committed does not count.
        storage.removeAll(new long[] {10});
        assertThrows(IllegalStateException.class, () -> storage.requestRemoval(true));
        storage.commit();
        storage.requestRemoval(true);
        assertEquals(PartitionState.REMOVAL_PENDING, storage.state());
    }

    @Test
    void removalDiscardsTheWritesNotCommitted() {
        storage.insertAll(List.of(new Entity2(10, A)));
        storage.commit();
        storage.insertAll(List.of(new Entity2(20, B)));
        storage.requestRemoval(false);
        storage.setTick(1);
        assertEquals(List.of(new Entity2(10, A)), storage.catalog().latest().getAll(ALL_IDS));
    }

    @Test
    void secondRemovalIsRejected() {
        storage.requestRemoval(false);
        assertThrows(IllegalStateException.class, () -> storage.requestRemoval(false));
        storage.setTick(1);
        assertThrows(IllegalStateException.class, () -> storage.requestRemoval(false));
    }

    @Test
    void acquisitionKeepsARemovedPartitionAfterItsWindowUntilTheRelease() {
        StorageSnapshot2 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        storage.requestRemoval(false);
        // Removal tick 2: the window has only tick 1, then nothing.
        assertFalse(storage.setTick(2));
        assertFalse(storage.setTick(3));
        assertEquals(PartitionState.RETIRED, storage.state());
        assertEquals(PartitionStorage2.NOT_ACQUIRED, storage.acquire(snapshot, 0));
        assertNull(storage.catalog().at(1));
        assertFalse(storage.endQuery(storage.admit(acquisitionId, 0)));
        assertEquals(1, storage.liveSnapshotCount());
        assertTrue(storage.release(acquisitionId));
        assertEquals(0, storage.liveSnapshotCount());
        assertEquals(PartitionState.DESTROYED, storage.state());
        // A release again does not destroy it twice.
        assertFalse(storage.release(acquisitionId));
    }

    @Test
    void endOfTheLastQueryDestroysARemovedPartition() {
        long acquisitionId = storage.acquire(publish(storage), 0);
        StorageSnapshot2 queried = storage.admit(acquisitionId, 0);
        storage.requestRemoval(false);
        // Removal tick 2; at tick 3 the snapshot is out of the window.
        assertFalse(storage.setTick(2));
        assertFalse(storage.setTick(3));
        assertFalse(storage.release(acquisitionId));
        assertTrue(storage.endQuery(queried));
        assertEquals(0, storage.liveSnapshotCount());
    }

    @Test
    void expiryFoundAfterTheWindowDestroysTheRemovedPartition() {
        FakeClock clock = new FakeClock();
        PartitionStorage2 timed = new PartitionStorage2(IndexConfig.linearScan(), 1, clock);
        long acquisitionId = timed.acquire(publish(timed), 0, AcquireOptions.timeout(TIMEOUT));
        timed.requestRemoval(false);
        assertFalse(timed.setTick(2));
        assertFalse(timed.setTick(3));
        assertFalse(timed.removeExpired());
        clock.advance(TIMEOUT);
        assertTrue(timed.removeExpired());
        assertFalse(timed.removeExpired());
        assertThrows(IllegalStateException.class, () -> timed.admit(acquisitionId, 0));
    }

    @Test
    void moveOfTheTickFindsTheExpiryAndDestroysTheRemovedPartition() {
        FakeClock clock = new FakeClock();
        PartitionStorage2 timed = new PartitionStorage2(IndexConfig.linearScan(), 1, clock);
        timed.acquire(publish(timed), 0, AcquireOptions.timeout(TIMEOUT));
        timed.requestRemoval(false);
        assertFalse(timed.setTick(2));
        // Out of the window, but still acquired.
        assertFalse(timed.setTick(3));
        clock.advance(TIMEOUT);
        assertTrue(timed.setTick(4));
        assertEquals(0, timed.liveSnapshotCount());
    }

    @Test
    void eventsBeforeTheRemovalDoNotDestroyThePartition() {
        StorageSnapshot2 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        publish(storage);
        assertFalse(storage.release(acquisitionId));
        assertTrue(snapshot.uses().isEnded());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void snapshotOutOfTheWindowIsNotAcquiredAgain() {
        StorageSnapshot2 snapshot = publish(storage);
        long kept = storage.acquire(snapshot, 0);
        publish(storage);
        assertEquals(PartitionStorage2.NOT_ACQUIRED, storage.acquire(snapshot, 0));
        long current = storage.acquire(storage.catalog().latest(), 1);
        storage.release(current);
        storage.release(kept);
        assertEquals(PartitionStorage2.NOT_ACQUIRED, storage.acquire(snapshot, 0));
        assertTrue(snapshot.uses().isEnded());
        assertEquals(0, storage.acquisitionCount());
        assertEquals(1, storage.liveSnapshotCount());
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
            PartitionStorage2 configured = new PartitionStorage2(config, RETAINED_TICKS);
            // Publishes the empty snapshot of tick 0 before the readers start.
            configured.setTick(1);
            AtomicBoolean done = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> readers = new ArrayList<>();
            CountDownLatch started = new CountDownLatch(READERS);
            CountDownLatch observed = new CountDownLatch(READERS);
            for (int r = 0; r < READERS; r++) {
                Thread reader = new Thread(() -> {
                    try {
                        long last = 0;
                        boolean read = false;
                        started.countDown();
                        while (!done.get()) {
                            SnapshotCatalog2 catalog = configured.catalog();
                            assertTrue(catalog.tick() >= last, "ticks go back");
                            last = catalog.tick();
                            checkWindow(catalog);
                            checkWholeCommit(catalog.latest(), catalog.tick() - 1);
                            if (catalog.tick() > 1 && !read) {
                                observed.countDown();
                                read = true;
                            }
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
                assertTrue(started.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                writeCommits(configured, observed);
            } finally {
                done.set(true);
                for (Thread reader : readers) {
                    reader.join(TIMEOUT.toMillis());
                    if (reader.isAlive()) {
                        reader.interrupt();
                        reader.join(TIMEOUT.toMillis());
                        throw new AssertionError("Reader did not finish");
                    }
                }
            }
            if (failure.get() != null) {
                throw new AssertionError(config + ": a reader failed", failure.get());
            }
        }
    }

    private static void assertAdmission(PartitionStorage2 storage, long acquisitionId, long tick,
            StorageSnapshot2 expected) {
        StorageSnapshot2 admitted = storage.admit(acquisitionId, tick);
        try {
            assertSame(expected, admitted);
        } finally {
            storage.endQuery(admitted);
        }
    }

    /**
     * Commits in the current tick of the storage, moves to the next tick and returns the
     * published snapshot, which is the one of the tick before the new tick.
     */
    private static StorageSnapshot2 publish(PartitionStorage2 storage) {
        StorageSnapshot2 snapshot = storage.commit();
        storage.setTick(storage.tick() + 1);
        return snapshot;
    }

    /** Checks all the reads of the snapshot, which must find exactly the expected entities, nearest to A first. */
    private static void checkSnapshot(StorageSnapshot2 snapshot, List<Entity2> expected,
            String where) {
        assertEquals(Set.copyOf(expected), Set.copyOf(snapshot.getAll(ALL_IDS)), where);
        assertEquals(expected.size(), snapshot.getAll(ALL_IDS).size(), where);
        List<Entity2> inRegion = snapshot.findInRegion(new Box2(LIMITS_MIN, LIMITS_MAX));
        assertEquals(Set.copyOf(expected), Set.copyOf(inRegion), where);
        assertEquals(expected.size(), inRegion.size(), where);
        assertEquals(expected, snapshot.findNearest(A, ALL_IDS.length), where);
        assertEquals(List.of(), snapshot.findNearest(A, 0), where);
    }

    /**
     * Checks that the window of the catalog has the last RETAINED_TICKS ticks, and not
     * the ticks before them or the current one. writeCommits() commits at every tick.
     */
    private static void checkWindow(SnapshotCatalog2 catalog) {
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
    private static void writeCommits(PartitionStorage2 storage, CountDownLatch observed) throws InterruptedException {
        List<Long> ids = new ArrayList<>();
        long nextId = 1;
        for (long tick = 1; tick <= TICKS; tick++) {
            List<Entity2> moved = new ArrayList<>();
            for (long id : ids) {
                moved.add(new Entity2(id, positionAt(tick, id)));
            }
            int half = moved.size() / 2;
            storage.updateAll(moved.subList(0, half));
            storage.updateAll(moved.subList(half, moved.size()));
            while (ids.size() > sizeAt(tick)) {
                storage.removeAll(new long[] {ids.removeFirst()});
            }
            while (ids.size() < sizeAt(tick)) {
                long id = nextId++;
                storage.insertAll(List.of(new Entity2(id, positionAt(tick, id))));
                ids.add(id);
            }
            storage.commit();
            storage.setTick(tick + 1);
            if (tick == 1) {
                assertTrue(observed.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
        }
    }

    /** Checks that all the reads of the snapshot see the entities of its tick, and only them. */
    private static void checkWholeCommit(StorageSnapshot2 snapshot, long tick) {
        String where = "tick " + tick;
        assertEquals(sizeAt(tick), snapshot.size(), where);
        List<Entity2> found = snapshot.findInRegion(new Box2(LIMITS_MIN, LIMITS_MAX));
        assertEquals(sizeAt(tick), found.size(), where);
        for (Entity2 e : found) {
            assertEquals(positionAt(tick, e.id()), e.position(), where);
        }
        long[] ids = found.stream().mapToLong(Entity2::id).toArray();
        assertEquals(found.size(), snapshot.getAll(ids).size(), where);
        for (Entity2 e : snapshot.getAll(ids)) {
            assertEquals(positionAt(tick, e.id()), e.position(), where);
        }
        List<Entity2> nearest = snapshot.findNearest(positionAt(tick, 0), sizeAt(tick) + 1);
        assertEquals(found.size(), nearest.size(), where);
        assertEquals(Set.copyOf(found), Set.copyOf(nearest), where);
        for (Entity2 e : nearest) {
            assertEquals(positionAt(tick, e.id()), e.position(), where);
        }
    }

    /** The number of entities at the tick: 0 at tick 0, then from 0 to 22. */
    private static int sizeAt(long tick) {
        return (int) (tick * 7 % 23);
    }

    /** The position of an entity at the tick, inside the limits; it changes cell often. */
    private static Point2 positionAt(long tick, long entityId) {
        double base = tick % 16 - 8;
        return new Point2(base + (entityId % 10) * 0.25, -base - (entityId / 10 % 10) * 0.25);
    }

}
