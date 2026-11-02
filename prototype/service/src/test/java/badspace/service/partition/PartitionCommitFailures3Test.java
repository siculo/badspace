package badspace.service.partition;

import static badspace.service.partition.Owners.owner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box3;
import badspace.common.geometry.Point3;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.RetentionWindow;
import badspace.service.index.SpatialIndexes;
import badspace.service.partition.slots.CoordinateBufferPool;
import badspace.service.partition.slots.MutableSlots3;
import badspace.service.partition.snapshot.MonotonicClock;
import badspace.service.partition.snapshot.StorageSnapshot3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks that a commit is all or nothing: when the preparation of the slots or of the index
 * fails, the last commit, the snapshot that waits for the move of the tick and the writes not
 * committed stay as they were, and the writer can commit again.
 */
class PartitionCommitFailures3Test {

    private static final CoordinateLimits LIMITS = new CoordinateLimits(-100, 100);
    private static final Box3 EVERYWHERE = new Box3(new Point3(-100, -100, -100), new Point3(100, 100, 100));
    private static final Point3 A = new Point3(1, 1, 1);
    private static final Point3 B = new Point3(2, 2, 2);
    private static final Point3 C = new Point3(3, 3, 3);
    private static final long[] TWO_IDS = {10, 20};
    /** More than two pages of slots. */
    private static final int MANY = 2 * MutableSlots3.PAGE_SLOTS + 2;

    private GatedIndex3 index;
    private FailingBufferPool pool;
    private PartitionStorage3 storage;

    @BeforeEach
    void setUp() {
        pool = new FailingBufferPool();
        storage = new PartitionStorage3(LIMITS,
                slots -> index = new GatedIndex3(SpatialIndexes.create(IndexConfig.gridQuadtree(4, 2), slots)),
                RetentionWindow.DEFAULT_RETAINED_TICKS, MonotonicClock.SYSTEM, 0, new ReentrantLock(),
                new CoordinateBufferPool(pool));
    }

    @Test
    void failureInTheIndexKeepsTheCommitOfTheTickAndTheWrites() {
        commitFirstState();
        writeSecondState();
        RuntimeException failure = new IllegalStateException("Index failure for the test");
        index.prepareFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> storage.commit(owner(storage))));
        index.prepareFailure = null;
        checkAfterTheFailure();
    }

    @Test
    void failureInTheSlotsKeepsTheCommitOfTheTickAndTheWrites() {
        commitFirstState();
        writeSecondState();
        RuntimeException failure = new IllegalStateException("Buffer failure for the test");
        pool.takeFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> storage.commit(owner(storage))));
        pool.takeFailure = null;
        checkAfterTheFailure();
    }

    /**
     * The abandoned commit took a buffer and copied into it the moved entities. The release
     * brings the slots back to the commit before; the buffer goes back to the pool and the
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
        assertThrows(IllegalStateException.class, () -> storage.commit(owner(storage)));
        index.prepareFailure = null;
        assertEquals(1, storage.pooledBufferCount());

        storage.releaseOwnership(owner(storage));
        Entity3 changed = new Entity3(0, C);
        storage.updateAll(owner(storage), List.of(changed));
        StorageSnapshot3 snapshot = storage.commit(owner(storage));
        assertEquals(0, storage.pooledBufferCount());
        List<Entity3> expected = new ArrayList<>(committed);
        expected.set(0, changed);
        assertEquals(expected, snapshot.getAll(idsOf(expected)));
        assertEquals(MANY, snapshot.findInRegion(EVERYWHERE).size());
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
     * The move of the tick publishes the first state, whose buffer the failed commit did not
     * take; then a new commit confirms the second state, which the failure did not discard.
     */
    private void checkAfterTheFailure() {
        storage.setTick(1);
        StorageSnapshot3 published = storage.catalog().latest();
        assertEquals(List.of(new Entity3(10, A), new Entity3(20, B)), published.getAll(TWO_IDS));
        assertEquals(List.of(new Entity3(10, A)), published.findNearest(A, 1));
        assertEquals(List.of(new ApplicationMetadata(10, Map.of("hp", 1L))), published.getMetadataAll(new long[] {10}));

        StorageSnapshot3 retried = storage.commit(owner(storage));
        assertEquals(List.of(new Entity3(10, C), new Entity3(20, B)), retried.getAll(TWO_IDS));
        assertEquals(List.of(new Entity3(10, C)), retried.findNearest(C, 1));
        assertEquals(List.of(new ApplicationMetadata(10, Map.of("hp", 2L))), retried.getMetadataAll(new long[] {10}));
        assertEquals(List.of(new Entity3(10, A), new Entity3(20, B)), published.getAll(TWO_IDS));
    }

    private static long[] idsOf(List<Entity3> entities) {
        long[] entityIds = new long[entities.size()];
        for (int i = 0; i < entityIds.length; i++) {
            entityIds[i] = entities.get(i).id();
        }
        return entityIds;
    }
}
