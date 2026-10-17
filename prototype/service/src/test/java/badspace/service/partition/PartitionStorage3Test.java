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
import badspace.service.index.SpatialIndexes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks storage semantics with real spatial indices. */
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
    /** How many times the race between the end of a snapshot and a commit runs. */
    private static final int RACE_ROUNDS = 500;
    /** The steps and the seed of the test with random writes. */
    private static final int RANDOM_STEPS = 3000;
    private static final long RANDOM_SEED = 42;

    private PartitionStorage3 storage;

    @BeforeEach
    void setUp() {
        storage = new PartitionStorage3(LIMITS, s -> SpatialIndexes.create(IndexConfig.linearScan(), s));
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
        assertEquals(1, new PartitionStorage3(IndexConfig.linearScan()).catalog().retainedTicks());
        assertEquals(3, new PartitionStorage3(IndexConfig.linearScan(), 3).catalog().retainedTicks());
        assertThrows(IllegalArgumentException.class, () -> new PartitionStorage3(IndexConfig.linearScan(), 0));
    }

    @Test
    void storageIsCreatedAtTheGivenTick() {
        PartitionStorage3 created = new PartitionStorage3(IndexConfig.linearScan(), 1, MonotonicClock.SYSTEM, 7);
        assertEquals(7, created.tick());
        assertEquals(7, created.creationTick());
        assertNull(created.catalog().latest());
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
            // Two retained ticks, so both snapshots stay in the window: a snapshot that ends
            // gives its buffer to a later commit.
            PartitionStorage3 configured = new PartitionStorage3(config, 2);
            configured.insertAll(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)));
            StorageSnapshot3 first = publish(configured);
            // The removal of 10 moves 30 into its slot.
            configured.updateAll(List.of(new Entity3(30, D)));
            configured.removeAll(new long[] {10});
            configured.insertAll(List.of(new Entity3(40, A)));
            StorageSnapshot3 second = publish(configured);
            configured.removeAll(new long[] {20});
            configured.updateAll(List.of(new Entity3(40, B)));

            String where = config.toString();
            checkSnapshot(first, List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)), where);
            checkSnapshot(second, List.of(new Entity3(40, A), new Entity3(20, B), new Entity3(30, D)), where);
            assertEquals(List.of(new Entity3(30, D), new Entity3(40, B)), configured.commit().getAll(ALL_IDS), where);
        }
    }

    @Test
    void snapshotsKeepTheirIdsAndPositionsAcrossUpdatesInsertionsAndRemovals() {
        // Four retained ticks, so all the snapshots stay in the window.
        PartitionStorage3 retained = new PartitionStorage3(LIMITS, s -> SpatialIndexes.create(IndexConfig.linearScan(), s), 4);
        retained.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
        SlotSnapshot3 first = publish(retained).slots();
        retained.updateAll(List.of(new Entity3(20, C)));
        SlotSnapshot3 moved = publish(retained).slots();
        assertEquals(B, first.positionAt(1));
        assertEquals(C, moved.positionAt(1));

        retained.removeAll(new long[] {10});
        SlotSnapshot3 removed = publish(retained).slots();
        retained.insertAll(List.of(new Entity3(30, D)));
        SlotSnapshot3 inserted = publish(retained).slots();
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
        PartitionStorage3 configured = new PartitionStorage3(IndexConfig.linearScan(), 2);
        StorageSnapshot3 first = configured.commit();
        configured.setTick(1);
        assertSame(first, configured.catalog().at(0));
        StorageSnapshot3 second = configured.commit();
        configured.setTick(2);
        assertSame(first, configured.catalog().at(0));
        assertSame(second, configured.catalog().at(1));
        assertSame(second, configured.catalog().latest());
    }

    @Test
    void onlyTheLastCommitOfATickIsPublished() {
        storage.insertAll(List.of(new Entity3(10, A)));
        storage.commit();
        storage.insertAll(List.of(new Entity3(20, B)));
        StorageSnapshot3 last = storage.commit();
        storage.setTick(1);
        assertSame(last, storage.catalog().latest());
        assertEquals(2, last.size());
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void writesAfterTheLastCommitGoToTheNextCommit() {
        storage.insertAll(List.of(new Entity3(10, A)));
        storage.commit();
        storage.insertAll(List.of(new Entity3(20, B)));
        storage.setTick(1);
        assertEquals(List.of(new Entity3(10, A)), storage.catalog().latest().getAll(ALL_IDS));
        storage.commit();
        storage.setTick(2);
        assertEquals(List.of(new Entity3(10, A), new Entity3(20, B)), storage.catalog().latest().getAll(ALL_IDS));
    }

    @Test
    void readersSeeTheWritesOnlyAfterTheCommitAndTheMoveOfTheTick() {
        assertNull(storage.catalog().latest());
        storage.setTick(1);
        StorageSnapshot3 empty = storage.catalog().latest();
        assertEquals(0, empty.size());
        assertEquals(List.of(), empty.findInRegion(new Box3(LIMITS_MIN, LIMITS_MAX)));
        assertEquals(List.of(), empty.findNearest(A, 1));

        storage.insertAll(List.of(new Entity3(10, A)));
        StorageSnapshot3 committed = storage.commit();
        // Committed, but not published until the tick moves.
        assertSame(empty, storage.catalog().latest());
        storage.setTick(2);
        assertSame(committed, storage.catalog().latest());
        assertEquals(List.of(new Entity3(10, A)), committed.getAll(ALL_IDS));

        // A tick without a commit keeps the snapshot before.
        storage.updateAll(List.of(new Entity3(10, B)));
        storage.setTick(3);
        assertSame(committed, storage.catalog().latest());
        assertSame(committed, storage.catalog().at(2));
    }

    @Test
    void acquisitionKeepsItsSnapshotUntilTheRelease() {
        StorageSnapshot3 first = publish(storage);
        long acquisitionId = storage.acquire(first, 0);
        publish(storage);
        assertAdmission(storage, acquisitionId, 0, first);
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
        assertAdmission(storage, second, 0, snapshot);
        assertEquals(1, storage.acquisitionCount());
        storage.release(second);
        publish(storage);
        assertTrue(snapshot.uses().isEnded());
        assertEquals(0, storage.acquisitionCount());
    }

    @Test
    void admitRejectsAnotherTickAndKeepsTheAcquisition() {
        StorageSnapshot3 snapshot = publish(storage);
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
        PartitionStorage3 timed = new PartitionStorage3(IndexConfig.linearScan(), 1, clock);
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
        StorageSnapshot3 empty = kept.catalog().latest();
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
    void removalBecomesEffectiveAtTheNextMoveOfTheTick() {
        PartitionStorage3 kept = new PartitionStorage3(IndexConfig.linearScan(), 2);
        publish(kept);
        StorageSnapshot3 last = publish(kept);
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
        storage.insertAll(List.of(new Entity3(10, A)));
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
        storage.insertAll(List.of(new Entity3(10, A)));
        storage.commit();
        storage.insertAll(List.of(new Entity3(20, B)));
        storage.requestRemoval(false);
        storage.setTick(1);
        assertEquals(List.of(new Entity3(10, A)), storage.catalog().latest().getAll(ALL_IDS));
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
        StorageSnapshot3 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        storage.requestRemoval(false);
        // Removal tick 2: the window has only tick 1, then nothing.
        assertFalse(storage.setTick(2));
        assertFalse(storage.setTick(3));
        assertEquals(PartitionState.RETIRED, storage.state());
        assertEquals(PartitionStorage3.NOT_ACQUIRED, storage.acquire(snapshot, 0));
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
        StorageSnapshot3 queried = storage.admit(acquisitionId, 0);
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
        PartitionStorage3 timed = new PartitionStorage3(IndexConfig.linearScan(), 1, clock);
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
        PartitionStorage3 timed = new PartitionStorage3(IndexConfig.linearScan(), 1, clock);
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
        long current = storage.acquire(storage.catalog().latest(), 1);
        storage.release(current);
        storage.release(kept);
        assertEquals(PartitionStorage3.NOT_ACQUIRED, storage.acquire(snapshot, 0));
        assertTrue(snapshot.uses().isEnded());
        assertEquals(0, storage.acquisitionCount());
        assertEquals(1, storage.liveSnapshotCount());
    }

    /**
     * A writer and some readers on other threads. At each tick the writer moves all
     * the entities to positions that depend on the tick, changes their number, commits
     * and moves to the next tick; it moves the entities with more than one call, so the
     * states between the calls are mixed. The readers check that each snapshot they acquire
     * is the whole commit of its tick, and that the catalog has the window of the same tick.
     * The buffers of the ended snapshots go to the later commits during the test.
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
            CountDownLatch observed = new CountDownLatch(READERS);
            for (int r = 0; r < READERS; r++) {
                Thread reader = new Thread(() -> {
                    try {
                        long last = 0;
                        boolean read = false;
                        started.countDown();
                        while (!done.get()) {
                            SnapshotCatalog3 catalog = configured.catalog();
                            assertTrue(catalog.tick() >= last, "ticks go back");
                            last = catalog.tick();
                            checkWindow(catalog);
                            // The acquisition keeps the snapshot: without it, the snapshot could
                            // end while the reader reads it, and its buffer go to a later commit.
                            StorageSnapshot3 latest = catalog.latest();
                            long acquisitionId = configured.acquire(latest, catalog.tick() - 1);
                            if (acquisitionId != PartitionStorage3.NOT_ACQUIRED) {
                                try {
                                    checkWholeCommit(latest, catalog.tick() - 1);
                                } finally {
                                    configured.release(acquisitionId);
                                }
                            }
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

    @Test
    void bufferOfAnEndedSnapshotGoesToALaterCommit() {
        storage.insertAll(List.of(new Entity3(10, A)));
        StorageSnapshot3 first = publish(storage);
        storage.updateAll(List.of(new Entity3(10, B)));
        publish(storage);
        // With one retained tick the first snapshot left the window, with no uses.
        assertTrue(first.uses().isEnded());
        assertEquals(1, storage.pooledBufferCount());

        storage.updateAll(List.of(new Entity3(10, C)));
        StorageSnapshot3 third = storage.commit();
        assertSame(first.slots().coordinateBuffer(), third.slots().coordinateBuffer());
        assertEquals(0, storage.pooledBufferCount());
        storage.setTick(storage.tick() + 1);
        checkSnapshot(third, List.of(new Entity3(10, C)), "reused buffer");
    }

    @Test
    void snapshotInTheWindowKeepsItsBuffer() {
        PartitionStorage3 retained = new PartitionStorage3(LIMITS, s -> SpatialIndexes.create(IndexConfig.linearScan(), s), 2);
        retained.insertAll(List.of(new Entity3(10, A)));
        StorageSnapshot3 first = publish(retained);
        retained.updateAll(List.of(new Entity3(10, B)));
        StorageSnapshot3 second = publish(retained);
        retained.updateAll(List.of(new Entity3(10, C)));
        StorageSnapshot3 third = retained.commit();

        assertEquals(0, retained.pooledBufferCount());
        assertNotSame(first.slots().coordinateBuffer(), third.slots().coordinateBuffer());
        assertNotSame(second.slots().coordinateBuffer(), third.slots().coordinateBuffer());
        checkSnapshot(first, List.of(new Entity3(10, A)), "first");
        checkSnapshot(second, List.of(new Entity3(10, B)), "second");
    }

    /**
     * A slow reader keeps its snapshot out of the window: the writer does not wait for it,
     * and allocates a new buffer at each commit until the pool has one.
     */
    @Test
    void acquiredSnapshotKeepsItsBufferAndTheWriterAllocatesOthers() {
        storage.insertAll(List.of(new Entity3(10, A)));
        StorageSnapshot3 kept = publish(storage);
        long acquisitionId = storage.acquire(kept, 0);
        storage.updateAll(List.of(new Entity3(10, B)));
        StorageSnapshot3 second = publish(storage);
        storage.updateAll(List.of(new Entity3(10, C)));
        StorageSnapshot3 third = publish(storage);

        assertNotSame(kept.slots().coordinateBuffer(), second.slots().coordinateBuffer());
        assertNotSame(kept.slots().coordinateBuffer(), third.slots().coordinateBuffer());
        // The second snapshot ended when the third one was published: the next commit reuses it.
        storage.updateAll(List.of(new Entity3(10, D)));
        assertSame(second.slots().coordinateBuffer(), storage.commit().slots().coordinateBuffer());
        checkSnapshot(kept, List.of(new Entity3(10, A)), "acquired");

        storage.release(acquisitionId);
        assertTrue(kept.uses().isEnded());
        assertEquals(1, storage.pooledBufferCount());
    }

    @Test
    void queryKeepsTheBufferOfAReleasedSnapshotUntilItEnds() {
        storage.insertAll(List.of(new Entity3(10, A)));
        StorageSnapshot3 snapshot = publish(storage);
        long acquisitionId = storage.acquire(snapshot, 0);
        StorageSnapshot3 admitted = storage.admit(acquisitionId, 0);
        storage.release(acquisitionId);
        storage.updateAll(List.of(new Entity3(10, B)));
        publish(storage);
        storage.updateAll(List.of(new Entity3(10, C)));
        StorageSnapshot3 later = storage.commit();

        assertNotSame(snapshot.slots().coordinateBuffer(), later.slots().coordinateBuffer());
        checkSnapshot(admitted, List.of(new Entity3(10, A)), "in a query");
        storage.endQuery(admitted);
        assertTrue(snapshot.uses().isEnded());
        assertEquals(1, storage.pooledBufferCount());
    }

    @Test
    void snapshotReplacedInTheSameTickGivesItsBufferToTheNextCommit() {
        storage.insertAll(List.of(new Entity3(10, A)));
        StorageSnapshot3 replaced = storage.commit();
        storage.updateAll(List.of(new Entity3(10, B)));
        StorageSnapshot3 last = storage.commit();
        assertSame(replaced.slots().coordinateBuffer(), last.slots().coordinateBuffer());

        storage.setTick(1);
        assertSame(last, storage.catalog().latest());
        checkSnapshot(last, List.of(new Entity3(10, B)), "published");
        assertEquals(1, storage.liveSnapshotCount());
    }

    @Test
    void bufferTooSmallForTheEntitiesIsDropped() {
        storage.insertAll(List.of(new Entity3(10, A)));
        StorageSnapshot3 small = publish(storage);
        storage.updateAll(List.of(new Entity3(10, B)));
        publish(storage);
        assertEquals(1, storage.pooledBufferCount());

        // More entities than the capacity of the buffer of the first snapshot.
        List<Entity3> many = new ArrayList<>();
        int count = small.slots().coordinateBuffer().values().length + 1;
        for (int i = 0; i < count; i++) {
            many.add(new Entity3(100 + i, B));
        }
        storage.insertAll(many);
        StorageSnapshot3 large = storage.commit();
        assertNotSame(small.slots().coordinateBuffer(), large.slots().coordinateBuffer());
        assertEquals(0, storage.pooledBufferCount());
        assertEquals(count + 1, large.size());
    }

    @Test
    void destructionEmptiesThePool() {
        storage.insertAll(List.of(new Entity3(10, A)));
        publish(storage);
        storage.updateAll(List.of(new Entity3(10, B)));
        publish(storage);
        storage.updateAll(List.of(new Entity3(10, C)));
        publish(storage);
        assertEquals(1, storage.pooledBufferCount());

        storage.requestRemoval(false);
        storage.setTick(storage.tick() + 1);
        assertTrue(storage.setTick(storage.tick() + 1));
        assertEquals(PartitionState.DESTROYED, storage.state());
        assertEquals(0, storage.pooledBufferCount());
    }

    /**
     * A reader ends a snapshot with its release while the writer commits, both after the
     * same barrier. The buffer is either in the new snapshot or in the pool, never in both
     * and never lost, and the new snapshot has the state of its commit. This stress test
     * repeats the race; the tests above check each order alone.
     */
    @Test
    void endOfASnapshotOnAnotherThreadDuringACommitGivesItsBufferOnce() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            PartitionStorage3 raced = new PartitionStorage3(IndexConfig.linearScan());
            raced.insertAll(List.of(new Entity3(10, A)));
            StorageSnapshot3 first = publish(raced);
            long acquisitionId = raced.acquire(first, 0);
            raced.updateAll(List.of(new Entity3(10, B)));
            publish(raced);
            raced.updateAll(List.of(new Entity3(10, C)));

            CyclicBarrier start = new CyclicBarrier(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    raced.release(acquisitionId);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            reader.start();
            StorageSnapshot3 committed;
            try {
                start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                committed = raced.commit();
            } finally {
                reader.join(TIMEOUT.toMillis());
            }
            assertFalse(reader.isAlive(), "the reader did not finish");
            if (failure.get() != null) {
                throw new AssertionError("The reader failed", failure.get());
            }
            assertTrue(first.uses().isEnded());
            boolean reused = committed.slots().coordinateBuffer() == first.slots().coordinateBuffer();
            assertEquals(reused ? 0 : 1, raced.pooledBufferCount(), "round " + round);
            raced.setTick(raced.tick() + 1);
            checkSnapshot(committed, List.of(new Entity3(10, C)), "round " + round);
        }
    }

    /**
     * Two snapshots out of the window of a retired partition, each with an acquisition. The
     * release of A stops while it gives back its buffer; then the release of B ends the other
     * snapshot. The partition is destroyed only by the last of the two, and no buffer comes
     * back to the pool after the destruction empties it.
     */
    @Test
    void bufferGivenBackDuringTheDestructionDoesNotStayInThePool() throws Exception {
        GatedBufferPool pool = new GatedBufferPool();
        PartitionStorage3 retired = new PartitionStorage3(LIMITS, s -> SpatialIndexes.create(IndexConfig.linearScan(), s),
                1, MonotonicClock.SYSTEM, 0, new ReentrantLock(), pool);
        retired.insertAll(List.of(new Entity3(10, A)));
        StorageSnapshot3 first = publish(retired);
        long acquisitionA = retired.acquire(first, 0);
        retired.updateAll(List.of(new Entity3(10, B)));
        StorageSnapshot3 second = publish(retired);
        long acquisitionB = retired.acquire(second, 1);
        retired.requestRemoval(false);
        // Retired at tick 3; at tick 4 no snapshot is in the window, but both are acquired.
        retired.setTick(3);
        retired.setTick(4);
        assertEquals(2, retired.liveSnapshotCount());
        assertEquals(0, retired.pooledBufferCount());

        Gate gate = new Gate();
        pool.gate = gate;
        AtomicBoolean destroyedByA = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread readerA = new Thread(() -> {
            try {
                destroyedByA.set(retired.release(acquisitionA));
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        readerA.start();
        boolean destroyedByB;
        try {
            gate.awaitEntered();
            // A ended its snapshot and is giving back the buffer: B ends the other one.
            destroyedByB = retired.release(acquisitionB);
            assertFalse(destroyedByB, "the partition was destroyed while A gives back its buffer");
            assertEquals(PartitionState.RETIRED, retired.state());
        } finally {
            gate.open();
            readerA.join(TIMEOUT.toMillis());
        }
        assertFalse(readerA.isAlive(), "reader A did not finish");
        if (failure.get() != null) {
            throw new AssertionError("Reader A failed", failure.get());
        }
        assertTrue(destroyedByA.get());
        assertEquals(PartitionState.DESTROYED, retired.state());
        assertEquals(0, retired.liveSnapshotCount());
        assertEquals(0, retired.pooledBufferCount());
    }

    /**
     * Random insertions, moves and removals, with one or more commits in a tick, ticks
     * without commits and acquisitions kept for some ticks, so the buffers come back in
     * any order and with any state. Each published snapshot, and each acquired one until
     * its release, must have the state of its commit, which the test keeps in a map.
     */
    @Test
    void snapshotsHaveTheStateOfTheirCommitAfterAnySequenceOfWrites() {
        for (IndexConfig config : List.of(IndexConfig.linearScan(), IndexConfig.gridQuadtree(4))) {
            PartitionStorage3 random = new PartitionStorage3(config, 2);
            Random rnd = new Random(RANDOM_SEED);
            Map<Long, Point3> written = new HashMap<>();
            Map<Long, Point3> committed = Map.of();
            Map<Long, Point3> published = Map.of();
            List<HeldSnapshot3> held = new ArrayList<>();
            long nextId = 1;
            for (int step = 0; step < RANDOM_STEPS; step++) {
                List<Long> ids = new ArrayList<>(written.keySet());
                int operation = rnd.nextInt(10);
                if (operation < 3 || ids.isEmpty()) {
                    List<Entity3> inserted = new ArrayList<>();
                    for (int i = rnd.nextInt(PartitionStorage3.PAGE_SLOTS / 2) + 1; i > 0; i--) {
                        Entity3 e = new Entity3(nextId++, randomPoint(rnd));
                        inserted.add(e);
                        written.put(e.id(), e.position());
                    }
                    random.insertAll(inserted);
                } else if (operation < 6) {
                    // Few moves, so most pages do not change.
                    List<Entity3> moved = new ArrayList<>();
                    for (int i = rnd.nextInt(3) + 1; i > 0; i--) {
                        long id = ids.get(rnd.nextInt(ids.size()));
                        if (moved.stream().noneMatch(e -> e.id() == id)) {
                            moved.add(new Entity3(id, randomPoint(rnd)));
                        }
                    }
                    random.updateAll(moved);
                    moved.forEach(e -> written.put(e.id(), e.position()));
                } else if (operation < 7) {
                    long id = ids.get(rnd.nextInt(ids.size()));
                    random.removeAll(new long[] {id});
                    written.remove(id);
                } else if (operation < 8) {
                    random.commit();
                    committed = Map.copyOf(written);
                } else {
                    if (operation == 9) {
                        random.commit();
                        committed = Map.copyOf(written);
                    }
                    // The move publishes the last commit, of this tick or of one before.
                    random.setTick(random.tick() + 1);
                    published = committed;
                    StorageSnapshot3 latest = random.catalog().latest();
                    checkState(latest, published, config + " at step " + step);
                    if (rnd.nextInt(3) == 0) {
                        held.add(new HeldSnapshot3(random.acquire(latest, random.tick() - 1), latest, published));
                    }
                    if (!held.isEmpty() && (held.size() > 3 || rnd.nextInt(3) == 0)) {
                        HeldSnapshot3 release = held.remove(rnd.nextInt(held.size()));
                        checkState(release.snapshot(), release.state(), config + " held until step " + step);
                        random.release(release.acquisitionId());
                    }
                }
            }
            for (HeldSnapshot3 h : held) {
                checkState(h.snapshot(), h.state(), config + " held to the end");
                random.release(h.acquisitionId());
            }
        }
    }

    /**
     * Only the pages of the moved entities are copied into a buffer that comes back from the
     * pool: it holds the state of an older commit, and gets the pages changed since.
     */
    @Test
    void fewMovingEntitiesCopyOnlyTheirPages() {
        int pages = 10;
        List<Entity3> entities = new ArrayList<>();
        for (int i = 0; i < pages * PartitionStorage3.PAGE_SLOTS; i++) {
            entities.add(new Entity3(i + 1, A));
        }
        storage.insertAll(entities);
        StorageSnapshot3 first = publish(storage);
        assertEquals(pages, storage.copiedPageCount());
        // A new buffer, because the first snapshot is still in the window.
        storage.updateAll(List.of(new Entity3(idInPage(3), B)));
        publish(storage);
        assertEquals(2 * pages, storage.copiedPageCount());
        assertEquals(1, storage.pooledBufferCount());

        storage.updateAll(List.of(new Entity3(idInPage(7), C)));
        StorageSnapshot3 third = storage.commit();
        assertSame(first.slots().coordinateBuffer(), third.slots().coordinateBuffer());
        // Page 3 changed after the commit of the first snapshot, page 7 now.
        assertEquals(2 * pages + 2, storage.copiedPageCount());
        storage.setTick(storage.tick() + 1);
        assertEquals(List.of(new Entity3(1, A), new Entity3(idInPage(3), B), new Entity3(idInPage(7), C)),
                third.getAll(new long[] {1, idInPage(3), idInPage(7)}));
        assertEquals(pages * PartitionStorage3.PAGE_SLOTS, third.findInRegion(new Box3(LIMITS_MIN, LIMITS_MAX)).size());
    }

    @Test
    void partitionWithoutMovementsCopiesNothing() {
        List<Entity3> entities = new ArrayList<>();
        for (int i = 0; i < 2 * PartitionStorage3.PAGE_SLOTS; i++) {
            entities.add(new Entity3(i + 1, A));
        }
        storage.insertAll(entities);
        StorageSnapshot3 first = publish(storage);
        long copied = storage.copiedPageCount();
        for (int i = 0; i < 5; i++) {
            StorageSnapshot3 next = publish(storage);
            assertSame(first.slots().coordinateBuffer(), next.slots().coordinateBuffer());
        }
        assertEquals(copied, storage.copiedPageCount());
        assertEquals(0, storage.pooledBufferCount());
    }

    @Test
    void sharedBufferGoesBackToThePoolAfterItsLastSnapshot() {
        storage.insertAll(List.of(new Entity3(10, A)));
        StorageSnapshot3 first = publish(storage);
        long acquisitionId = storage.acquire(first, 0);
        // No moves: the second snapshot shares the buffer of the first one.
        StorageSnapshot3 second = publish(storage);
        assertSame(first.slots().coordinateBuffer(), second.slots().coordinateBuffer());
        storage.updateAll(List.of(new Entity3(10, B)));
        publish(storage);

        // The second snapshot ended, the first one is out of the window but acquired.
        assertTrue(second.uses().isEnded());
        assertEquals(0, storage.pooledBufferCount());
        checkSnapshot(first, List.of(new Entity3(10, A)), "acquired");
        storage.release(acquisitionId);
        assertEquals(1, storage.pooledBufferCount());
    }

    private static void assertAdmission(PartitionStorage3 storage, long acquisitionId, long tick,
            StorageSnapshot3 expected) {
        StorageSnapshot3 admitted = storage.admit(acquisitionId, tick);
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
    private static StorageSnapshot3 publish(PartitionStorage3 storage) {
        StorageSnapshot3 snapshot = storage.commit();
        storage.setTick(storage.tick() + 1);
        return snapshot;
    }

    /** Checks all the reads of the snapshot, which must find exactly the expected entities, nearest to A first. */
    private static void checkSnapshot(StorageSnapshot3 snapshot, List<Entity3> expected,
            String where) {
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
    private static void writeCommits(PartitionStorage3 storage, CountDownLatch observed) throws InterruptedException {
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
            if (tick == 1) {
                assertTrue(observed.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
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
        List<Entity3> nearest = snapshot.findNearest(positionAt(tick, 0), sizeAt(tick) + 1);
        assertEquals(found.size(), nearest.size(), where);
        assertEquals(Set.copyOf(found), Set.copyOf(nearest), where);
        for (Entity3 e : nearest) {
            assertEquals(positionAt(tick, e.id()), e.position(), where);
        }
    }

    /** An acquisition kept by the random test, with the state that its snapshot must have. */
    private record HeldSnapshot3(long acquisitionId, StorageSnapshot3 snapshot, Map<Long, Point3> state) {
    }

    /** Checks that the snapshot has exactly the entities of the state, in the slots and in the index. */
    private static void checkState(StorageSnapshot3 snapshot, Map<Long, Point3> state, String where) {
        assertEquals(state.size(), snapshot.size(), where);
        long[] ids = state.keySet().stream().mapToLong(Long::longValue).toArray();
        List<Entity3> byId = snapshot.getAll(ids);
        assertEquals(ids.length, byId.size(), where);
        for (Entity3 e : byId) {
            assertEquals(state.get(e.id()), e.position(), where);
        }
        assertEquals(Set.copyOf(byId), Set.copyOf(snapshot.findInRegion(new Box3(LIMITS_MIN, LIMITS_MAX))), where);
    }

    private static Point3 randomPoint(Random rnd) {
        return new Point3(rnd.nextDouble() * 20 - 10, rnd.nextDouble() * 20 - 10, rnd.nextDouble() * 20 - 10);
    }

    /** The ID of an entity in the given page, when the entities are inserted with IDs from 1 in order. */
    private static long idInPage(int page) {
        return page * PartitionStorage3.PAGE_SLOTS + 6;
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

}
