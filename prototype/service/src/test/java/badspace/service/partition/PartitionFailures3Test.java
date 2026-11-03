package badspace.service.partition;

import static badspace.service.partition.Owners.owner;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box3;
import badspace.common.geometry.Point3;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.FailureOutcome;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionComponent;
import badspace.common.partition.PartitionFailure;
import badspace.common.partition.PartitionFailureException;
import badspace.common.partition.PartitionHealth;
import badspace.common.partition.PartitionOperation;
import badspace.common.partition.RetentionWindow;
import badspace.service.index.SpatialIndexes;
import badspace.service.partition.slots.CoordinateBufferPool;
import badspace.service.partition.metadata.MetadataSnapshot;
import badspace.service.partition.slots.MutableSlots3;
import badspace.service.partition.slots.SlotSnapshot3;
import badspace.service.partition.snapshot.MonotonicClock;
import badspace.service.partition.snapshot.StorageSnapshot3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the failures of the writer of a 3D partition. A failure after the checks of the
 * input, in a write or in a commit, discards all the writes not committed and keeps the last
 * commit and the snapshot that waits for the move of the tick; a refusal changes nothing. If
 * the restore fails too, the partition needs a recovery until a rollback or a change of the
 * ownership restores it. The index and the pool of the buffers of the tests throw the failures.
 */
class PartitionFailures3Test {

    private static final CoordinateLimits LIMITS = new CoordinateLimits(-100, 100);
    private static final Box3 EVERYWHERE = new Box3(new Point3(-100, -100, -100), new Point3(100, 100, 100));
    private static final Point3 A = new Point3(1, 1, 1);
    private static final Point3 B = new Point3(2, 2, 2);
    private static final Point3 C = new Point3(3, 3, 3);
    private static final long[] IDS = {10, 20, 30};
    /** More than two pages of slots. */
    private static final int MANY = 2 * MutableSlots3.PAGE_SLOTS + 2;

    private GatedIndex3 index;
    private FailingBufferPool pool;
    private FailingComponent<SlotSnapshot3> slotCommits;
    private FailingComponent<MetadataSnapshot> metadataCommits;
    private PartitionStorage3 storage;

    @BeforeEach
    void setUp() {
        pool = new FailingBufferPool();
        storage = new PartitionStorage3(LIMITS,
                slots -> index = new GatedIndex3(SpatialIndexes.create(IndexConfig.gridQuadtree(4, 2), slots)),
                RetentionWindow.DEFAULT_RETAINED_TICKS, MonotonicClock.SYSTEM, 0, new ReentrantLock(),
                new CoordinateBufferPool(pool),
                slots -> slotCommits = new FailingComponent<>(slots),
                metadata -> metadataCommits = new FailingComponent<>(metadata));
    }

    @Test
    void newPartitionIsHealthy() {
        assertSame(PartitionHealth.HEALTHY, storage.health());
    }

    @Test
    void refusedWriteKeepsTheWritesBefore() {
        commitFirstState();
        storage.insertAll(owner(storage), List.of(new Entity3(30, B)));
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(30, C))));
        assertEquals(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, B)),
                storage.commit(owner(storage)).getAll(IDS));
        assertSame(PartitionHealth.HEALTHY, storage.health());
    }

    @Test
    void failureOfAWriteDiscardsAlsoTheWritesOfTheTicksBefore() {
        commitFirstState();
        storage.insertAll(owner(storage), List.of(new Entity3(30, C)));
        storage.setTick(1);
        RuntimeException cause = new IllegalStateException("Index failure for the test");
        index.notificationFailure = cause;
        PartitionFailure failure = assertThrows(PartitionFailureException.class,
                () -> storage.updateAll(owner(storage), List.of(new Entity3(10, C)))).failure();
        index.notificationFailure = null;
        assertEquals(new PartitionFailure(PartitionOperation.WRITE, PartitionComponent.INDEX,
                FailureOutcome.WRITES_DISCARDED, 0, cause, null), failure);
        assertSame(PartitionHealth.HEALTHY, storage.health());
        assertEquals(2, storage.size());

        storage.insertAll(owner(storage), List.of(new Entity3(30, B)));
        StorageSnapshot3 next = storage.commit(owner(storage));
        assertEquals(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, B)), next.getAll(IDS));
        assertEquals(List.of(new ApplicationMetadata(10, Map.of("hp", 1L))), next.getMetadataAll(new long[] {10}));
    }

    @Test
    void failureOfTheIndexInACommitDiscardsTheWritesAndKeepsTheCommitOfTheTick() {
        commitFirstState();
        writeSecondState();
        RuntimeException cause = new IllegalStateException("Index failure for the test");
        index.prepareFailure = cause;
        PartitionFailure failure = assertThrows(PartitionFailureException.class,
                () -> storage.commit(owner(storage))).failure();
        index.prepareFailure = null;
        assertEquals(new PartitionFailure(PartitionOperation.COMMIT, PartitionComponent.INDEX,
                FailureOutcome.WRITES_DISCARDED, 0, cause, null), failure);
        checkFirstStateAfterTheFailure();
    }

    @Test
    void failureOfTheSlotsInACommitDiscardsTheWritesAndKeepsTheCommitOfTheTick() {
        commitFirstState();
        writeSecondState();
        RuntimeException cause = new IllegalStateException("Buffer failure for the test");
        pool.takeFailure = cause;
        PartitionFailure failure = assertThrows(PartitionFailureException.class,
                () -> storage.commit(owner(storage))).failure();
        pool.takeFailure = null;
        assertEquals(new PartitionFailure(PartitionOperation.COMMIT, PartitionComponent.SLOTS,
                FailureOutcome.WRITES_DISCARDED, 0, cause, null), failure);
        checkFirstStateAfterTheFailure();
    }

    /**
     * The abandoned commit took a buffer and copied into it the moved entities; the failure
     * brings the slots back to the commit before. The buffer goes back to the pool and the
     * next commit takes it, so it must copy into it all the pages, also the ones that it does
     * not change.
     */
    @Test
    void bufferOfAnAbandonedCommitGetsAllThePagesInTheNextCommit() {
        List<Entity3> committed = new ArrayList<>();
        for (int i = 0; i < MANY; i++) {
            committed.add(new Entity3(i, new Point3(i % 50, i / 50, 1)));
        }
        storage.insertAll(owner(storage), committed);
        storage.commit(owner(storage));
        storage.setTick(1);
        List<Entity3> moved = new ArrayList<>();
        for (Entity3 entity : committed) {
            Point3 position = entity.position();
            moved.add(new Entity3(entity.id(), new Point3(-position.x(), -position.y(), -position.z())));
        }
        storage.updateAll(owner(storage), moved);
        index.prepareFailure = new IllegalStateException("Index failure for the test");
        assertThrows(PartitionFailureException.class, () -> storage.commit(owner(storage)));
        index.prepareFailure = null;
        assertEquals(1, storage.pooledBufferCount());

        Entity3 changed = new Entity3(0, C);
        storage.updateAll(owner(storage), List.of(changed));
        StorageSnapshot3 snapshot = storage.commit(owner(storage));
        assertEquals(0, storage.pooledBufferCount());
        List<Entity3> expected = new ArrayList<>(committed);
        expected.set(0, changed);
        assertEquals(expected, snapshot.getAll(idsOf(expected)));
        assertEquals(MANY, snapshot.findInRegion(EVERYWHERE).size());
    }

    @Test
    void failedRestoreRequiresARecoveryAndRefusesWritesAndCommits() {
        commitFirstState();
        writeSecondState();
        RuntimeException cause = new IllegalStateException("Index failure for the test");
        RuntimeException restoreCause = new IllegalStateException("Restore failure for the test");
        index.notificationFailure = cause;
        index.restoreFailure = restoreCause;
        PartitionFailure failure = assertThrows(PartitionFailureException.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(30, C)))).failure();
        index.notificationFailure = null;
        assertEquals(new PartitionFailure(PartitionOperation.WRITE, PartitionComponent.INDEX,
                FailureOutcome.RECOVERY_REQUIRED, 0, cause, restoreCause), failure);
        assertEquals(new PartitionHealth.RecoveryRequired(failure), storage.health());

        assertThrows(IllegalStateException.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(30, C))));
        assertThrows(IllegalStateException.class, () -> storage.commit(owner(storage)));
        // A token that is not current is refused for the ownership first.
        assertThrows(OwnershipChangedException.class, () -> storage.commit(owner(storage) + 1));
        // The move of the tick publishes the last commit, which is whole.
        storage.setTick(1);
        checkFirstState(storage.catalog().latest());
    }

    /**
     * The claim tries the restore again: while it fails, the ownership changes anyway and the
     * partition still needs a recovery; when it works, the partition is healthy again.
     */
    @Test
    void changeOfTheOwnershipTriesTheRestoreAgain() {
        commitFirstState();
        writeSecondState();
        RuntimeException restoreCause = new IllegalStateException("Restore failure for the test");
        index.notificationFailure = new IllegalStateException("Index failure for the test");
        index.restoreFailure = restoreCause;
        assertThrows(PartitionFailureException.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(30, C))));
        index.notificationFailure = null;

        long first = owner(storage);
        long second = storage.claimOwnership(first);
        assertEquals(first + 1, second);
        assertEquals(new PartitionHealth.RecoveryRequired(new PartitionFailure(PartitionOperation.CLAIM,
                PartitionComponent.INDEX, FailureOutcome.RECOVERY_REQUIRED, 0, restoreCause, null)), storage.health());

        index.restoreFailure = null;
        storage.releaseOwnership(second);
        assertSame(PartitionHealth.HEALTHY, storage.health());
        storage.insertAll(owner(storage), List.of(new Entity3(30, C)));
        StorageSnapshot3 next = storage.commit(owner(storage));
        assertEquals(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)), next.getAll(IDS));
        assertEquals(List.of(new ApplicationMetadata(10, Map.of("hp", 1L))), next.getMetadataAll(new long[] {10}));
    }

    /** The rollback of the owner is the recovery of a partition that needs one. */
    @Test
    void rollbackRecoversAPartitionThatNeedsIt() {
        commitFirstState();
        writeSecondState();
        RuntimeException restoreCause = new IllegalStateException("Restore failure for the test");
        index.notificationFailure = new IllegalStateException("Index failure for the test");
        index.restoreFailure = restoreCause;
        assertThrows(PartitionFailureException.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(30, C))));
        index.notificationFailure = null;

        PartitionFailure failure = assertThrows(PartitionFailureException.class,
                () -> storage.rollback(owner(storage))).failure();
        PartitionFailure expected = new PartitionFailure(PartitionOperation.ROLLBACK, PartitionComponent.INDEX,
                FailureOutcome.RECOVERY_REQUIRED, 0, restoreCause, null);
        assertEquals(expected, failure);
        assertEquals(new PartitionHealth.RecoveryRequired(expected), storage.health());

        index.restoreFailure = null;
        long generation = owner(storage);
        storage.rollback(generation);
        assertSame(PartitionHealth.HEALTHY, storage.health());
        assertEquals(generation, owner(storage));
        checkFirstState(storage.commit(owner(storage)));
    }

    @Test
    void errorInTheRestoreOfARollbackGoesToTheCallerAsItIs() {
        commitFirstState();
        writeSecondState();
        OutOfMemoryError restoreCause = new OutOfMemoryError("Memory failure for the test");
        index.restoreFailure = restoreCause;
        assertSame(restoreCause, assertThrows(OutOfMemoryError.class, () -> storage.rollback(owner(storage))));
        PartitionHealth.RecoveryRequired health = assertInstanceOf(PartitionHealth.RecoveryRequired.class,
                storage.health());
        assertSame(restoreCause, health.failure().cause());
    }

    @Test
    void partitionThatNeedsARecoveryCanBeRemoved() {
        commitFirstState();
        index.notificationFailure = new IllegalStateException("Index failure for the test");
        index.restoreFailure = new IllegalStateException("Restore failure for the test");
        assertThrows(PartitionFailureException.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(30, C))));
        storage.requestRemoval(owner(storage), false);
        storage.setTick(1);
        assertEquals(PartitionState.RETIRED, storage.state());
        checkFirstState(storage.catalog().latest());
    }

    @Test
    void failureOfTheMetadataInACommitDiscardsTheWritesAndKeepsTheCommitOfTheTick() {
        commitFirstState();
        writeSecondState();
        RuntimeException cause = new IllegalStateException("Metadata failure for the test");
        metadataCommits.prepareFailure = cause;
        PartitionFailure failure = assertThrows(PartitionFailureException.class,
                () -> storage.commit(owner(storage))).failure();
        metadataCommits.prepareFailure = null;
        assertEquals(new PartitionFailure(PartitionOperation.COMMIT, PartitionComponent.METADATA,
                FailureOutcome.WRITES_DISCARDED, 0, cause, null), failure);
        checkFirstStateAfterTheFailure();
    }

    /** The failure of an abandon goes with the failure of the commit, and the writes are discarded all the same. */
    @Test
    void failureOfAnAbandonIsSuppressedInTheFailureOfTheCommit() {
        commitFirstState();
        writeSecondState();
        RuntimeException cause = new IllegalStateException("Metadata failure for the test");
        RuntimeException abandonCause = new IllegalStateException("Abandon failure for the test");
        metadataCommits.prepareFailure = cause;
        slotCommits.abandonFailure = abandonCause;
        PartitionFailureException thrown = assertThrows(PartitionFailureException.class,
                () -> storage.commit(owner(storage)));
        metadataCommits.prepareFailure = null;
        slotCommits.abandonFailure = null;
        assertSame(cause, thrown.getCause());
        assertArrayEquals(new Throwable[] {abandonCause}, cause.getSuppressed());
        checkFirstStateAfterTheFailure();
    }

    /**
     * The restore of the metadata fails after the slots and the index are restored: the
     * partition needs a recovery, and a rollback restores it when the failure ends. The slots,
     * already restored, have nothing to do the second time.
     */
    /**
     * The JVM can throw the same instance of OutOfMemoryError again: the abandon that fails with
     * the failure of the preparation does not hide it, and the writes are discarded all the same.
     */
    @Test
    void sameErrorInTheAbandonKeepsTheFailureOfTheCommit() {
        commitFirstState();
        writeSecondState();
        OutOfMemoryError cause = new OutOfMemoryError("Memory failure for the test");
        metadataCommits.prepareFailure = cause;
        slotCommits.abandonFailure = cause;
        assertSame(cause, assertThrows(OutOfMemoryError.class, () -> storage.commit(owner(storage))));
        metadataCommits.prepareFailure = null;
        slotCommits.abandonFailure = null;
        assertEquals(0, cause.getSuppressed().length);
        checkFirstStateAfterTheFailure();
    }

    @Test
    void failedRestoreOfTheMetadataRequiresARecovery() {
        commitFirstState();
        writeSecondState();
        RuntimeException cause = new IllegalStateException("Index failure for the test");
        RuntimeException restoreCause = new IllegalStateException("Metadata restore failure for the test");
        index.notificationFailure = cause;
        metadataCommits.restoreFailure = restoreCause;
        PartitionFailure failure = assertThrows(PartitionFailureException.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(30, C)))).failure();
        index.notificationFailure = null;
        assertEquals(new PartitionFailure(PartitionOperation.WRITE, PartitionComponent.INDEX,
                FailureOutcome.RECOVERY_REQUIRED, 0, cause, restoreCause), failure);
        assertEquals(new PartitionHealth.RecoveryRequired(failure), storage.health());

        PartitionFailure retry = assertThrows(PartitionFailureException.class,
                () -> storage.rollback(owner(storage))).failure();
        assertEquals(new PartitionFailure(PartitionOperation.ROLLBACK, PartitionComponent.METADATA,
                FailureOutcome.RECOVERY_REQUIRED, 0, restoreCause, null), retry);

        metadataCommits.restoreFailure = null;
        storage.rollback(owner(storage));
        assertSame(PartitionHealth.HEALTHY, storage.health());
        checkFirstState(storage.commit(owner(storage)));
    }

    /**
     * The restore of the slots fails before the index and the metadata are restored: the
     * partition needs a recovery, and a claim restores all the structures when the failure ends.
     */
    @Test
    void failedRestoreOfTheSlotsRequiresARecovery() {
        commitFirstState();
        writeSecondState();
        RuntimeException restoreCause = new IllegalStateException("Slot restore failure for the test");
        slotCommits.restoreFailure = restoreCause;
        long first = owner(storage);
        long second = storage.claimOwnership(first);
        assertEquals(new PartitionHealth.RecoveryRequired(new PartitionFailure(PartitionOperation.CLAIM,
                PartitionComponent.SLOTS, FailureOutcome.RECOVERY_REQUIRED, 0, restoreCause, null)), storage.health());
        assertThrows(IllegalStateException.class, () -> storage.commit(second));

        slotCommits.restoreFailure = null;
        long third = storage.claimOwnership(second);
        assertSame(PartitionHealth.HEALTHY, storage.health());
        checkFirstState(storage.commit(third));
    }

    @Test
    void failedCommitKeepsTheTickOfTheLastCommitAndTheOwnerReadsItInARecovery() {
        commitFirstState();
        storage.setTick(2);
        writeSecondState();
        index.prepareFailure = new IllegalStateException("Index failure for the test");
        index.restoreFailure = new IllegalStateException("Restore failure for the test");
        assertThrows(PartitionFailureException.class, () -> storage.commit(owner(storage)));
        index.prepareFailure = null;
        assertInstanceOf(PartitionHealth.RecoveryRequired.class, storage.health());
        assertEquals(0, storage.lastCommitTick(owner(storage)));
    }

    /** An Error goes to the caller as it is, and the partition discards the writes all the same. */
    @Test
    void errorGoesToTheCallerAsItIs() {
        commitFirstState();
        writeSecondState();
        OutOfMemoryError cause = new OutOfMemoryError("Memory failure for the test");
        index.notificationFailure = cause;
        assertSame(cause, assertThrows(OutOfMemoryError.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(30, C)))));
        index.notificationFailure = null;
        assertSame(PartitionHealth.HEALTHY, storage.health());
        checkFirstState(storage.commit(owner(storage)));
    }

    @Test
    void errorWithAFailedRestoreHasTheRestoreFailureAsSuppressed() {
        commitFirstState();
        OutOfMemoryError cause = new OutOfMemoryError("Memory failure for the test");
        RuntimeException restoreCause = new IllegalStateException("Restore failure for the test");
        index.notificationFailure = cause;
        index.restoreFailure = restoreCause;
        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(30, C))));
        assertSame(cause, thrown);
        assertArrayEquals(new Throwable[] {restoreCause}, thrown.getSuppressed());
        PartitionHealth.RecoveryRequired health = assertInstanceOf(PartitionHealth.RecoveryRequired.class,
                storage.health());
        assertSame(cause, health.failure().cause());
        assertSame(restoreCause, health.failure().restoreFailure());
    }

    @Test
    void reportOfAFailureWithoutRestoreFailureHasNoRestoreFailure() {
        commitFirstState();
        index.notificationFailure = new IllegalStateException("Index failure for the test");
        PartitionFailureException thrown = assertThrows(PartitionFailureException.class,
                () -> storage.removeAll(owner(storage), new long[] {10}));
        assertNull(thrown.failure().restoreFailure());
        assertSame(thrown.failure().cause(), thrown.getCause());
    }

    /** Commits 10 in A and 20 in B, with metadata on 10, in tick 0. */
    private void commitFirstState() {
        storage.insertAll(owner(storage), List.of(new Entity3(10, A), new Entity3(20, B)));
        storage.updateMetadataAll(owner(storage), List.of(MetadataWrite.put(10, "hp", 1)));
        storage.commit(owner(storage));
    }

    /** Moves 10 to C and changes its metadata, without a commit. */
    private void writeSecondState() {
        storage.updateAll(owner(storage), List.of(new Entity3(10, C)));
        storage.updateMetadataAll(owner(storage), List.of(MetadataWrite.put(10, "hp", 2)));
    }

    /**
     * After a failed commit the partition is healthy with the first state: the move of the tick
     * publishes it, whole, and a new commit without writes confirms it again.
     */
    private void checkFirstStateAfterTheFailure() {
        assertSame(PartitionHealth.HEALTHY, storage.health());
        storage.setTick(1);
        StorageSnapshot3 published = storage.catalog().latest();
        checkFirstState(published);
        checkFirstState(storage.commit(owner(storage)));
        checkFirstState(published);
    }

    private static void checkFirstState(StorageSnapshot3 snapshot) {
        assertEquals(List.of(new Entity3(10, A), new Entity3(20, B)), snapshot.getAll(IDS));
        assertEquals(List.of(new Entity3(10, A)), snapshot.findNearest(A, 1));
        assertEquals(List.of(new ApplicationMetadata(10, Map.of("hp", 1L))), snapshot.getMetadataAll(new long[] {10}));
    }

    private static long[] idsOf(List<Entity3> entities) {
        long[] entityIds = new long[entities.size()];
        for (int i = 0; i < entityIds.length; i++) {
            entityIds[i] = entities.get(i).id();
        }
        return entityIds;
    }
}
