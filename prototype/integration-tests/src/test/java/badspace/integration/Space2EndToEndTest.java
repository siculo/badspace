package badspace.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.RemovalPolicy;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.service.partition.LocalPartitionNode2;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The API together with the local nodes of the service: spaces, partitions, ticks, commits,
 * acquisitions and removal from the point of view of the software. The semantics in detail is
 * tested in the service; Space2Test in the api module checks the calls of the API with framework mocks.
 */
class Space2EndToEndTest {

    private final AtomicLong entityIds = new AtomicLong();
    private final AtomicLong partitionIds = new AtomicLong();
    private final SpaceConfig config = SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet);
    private static final Box2 ALL = new Box2(new Point2(-100, -100), new Point2(100, 100));

    private final Space2 space = new Space2(config);

    @Test
    void readerSeesACommitOnlyAfterTheMoveOfTheTick() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        long a = partition.insert(1, 2);
        long b = partition.insert(5, 5);
        partition.commit();
        assertReason(Reason.NOT_YET_PUBLISHED, partition::acquireLatest);

        space.setTick(1);
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            assertEquals(0, snapshot.tick());
            assertEquals(2, snapshot.size());
            assertEquals(List.of(new Entity2(a, new Point2(1, 2))), snapshot.findNearest(new Point2(0, 0), 1));
            assertEquals(2, snapshot.findInRegion(ALL).size());
            assertEquals(new Point2(5, 5), snapshot.get(b).orElseThrow());
        }
    }

    @Test
    void snapshotsOfTheWindowKeepTheStateOfTheirTick() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2(),
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(4)).withRetainedTicks(3));
        long id = partition.insert(0, 0);
        for (int tick = 0; tick < 5; tick++) {
            partition.update(id, new Point2(tick, 0));
            partition.commit();
            space.setTick(tick + 1);
        }
        // At tick 5 with k = 3: the snapshots of the ticks 2, 3 and 4.
        for (long tick = 2; tick < 5; tick++) {
            try (AcquiredSnapshot2 snapshot = partition.acquire(tick)) {
                assertEquals(new Point2(tick, 0), snapshot.get(id).orElseThrow());
            }
        }
        assertReason(Reason.OUT_OF_RETENTION, () -> partition.acquire(1));
        assertReason(Reason.NOT_YET_PUBLISHED, () -> partition.acquire(5));
        // The writer reads its own state, also the writes not committed.
        partition.update(id, new Point2(9, 9));
        assertEquals(new Point2(9, 9), partition.get(id).orElseThrow());
    }

    @Test
    void acquiredSnapshotStaysReadableAfterItLeavesTheWindow() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        long id = partition.insert(1, 1);
        partition.commit();
        space.setTick(1);
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            partition.remove(id);
            partition.commit();
            space.setTick(5);
            assertReason(Reason.OUT_OF_RETENTION, () -> partition.acquire(0));
            assertEquals(1, snapshot.size());
            try (AcquiredSnapshot2 latest = partition.acquireLatest()) {
                assertEquals(0, latest.size());
            }
        }
    }

    @Test
    void partitionsOnDifferentNodesMoveWithTheSpace() {
        LocalPartitionNode2 first = new LocalPartitionNode2();
        Partition2 a = space.createPartition(first);
        Partition2 b = space.createPartition(new LocalPartitionNode2());
        space.setTick(5);
        assertEquals(5, a.tick());
        assertEquals(5, b.tick());
        assertEquals(5, first.tick());

        // A new node gets the tick of the space before the creation.
        LocalPartitionNode2 later = new LocalPartitionNode2();
        Partition2 c = space.createPartition(later);
        assertEquals(5, later.tick());
        assertEquals(5, c.creationTick());
        assertEquals(0, a.creationTick());
        space.setTick(6);
        assertEquals(6, c.tick());
    }

    @Test
    void removedPartitionGivesItsTicksUntilItIsDestroyed() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2(),
                PartitionConfig.defaults().withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES).withRetainedTicks(2));
        partition.insert(1, 1);
        partition.commit();
        space.setTick(1);
        partition.insert(2, 2);
        space.removePartition(partition);
        assertThrows(IllegalStateException.class, () -> partition.insert(3, 3));
        assertThrows(IllegalStateException.class, partition::commit);

        // The removal is effective at tick 2: the writes not committed were discarded.
        space.setTick(2);
        AcquiredSnapshot2 kept = partition.acquire(1);
        assertEquals(1, kept.size());
        assertReason(Reason.AFTER_REMOVAL, () -> partition.acquire(2));

        // At tick 4 no tick of the partition is in the window, but the acquisition keeps it.
        space.setTick(4);
        assertEquals(1, kept.size());
        assertEquals(4, partition.tick());
        kept.close();
        // Destroyed: the node no longer knows the partition.
        assertThrows(IllegalArgumentException.class, partition::tick);
        assertThrows(IllegalArgumentException.class, partition::acquireLatest);
        kept.close();
    }

    @Test
    void removalThatRequiresEmptyLooksAtTheLastCommit() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        long id = partition.insert(1, 1);
        partition.commit();
        assertThrows(IllegalStateException.class, () -> space.removePartition(partition));
        partition.remove(id);
        assertThrows(IllegalStateException.class, () -> space.removePartition(partition),
                "the removal of the entity is not committed");
        partition.commit();
        space.removePartition(partition);
        space.setTick(1);
        try (AcquiredSnapshot2 last = partition.acquireLatest()) {
            assertEquals(0, last.size());
        }
    }

    @Test
    void getUpdateAndRemoveUseTheEntityId() {
        Partition2 p = space.createPartition(new LocalPartitionNode2());
        long[] ids = p.insertAll(List.of(new Point2(1, 1), new Point2(2, 2)));
        assertEquals(Optional.of(new Point2(1, 1)), p.get(ids[0]));

        p.update(ids[0], new Point2(5, 5));
        p.updateAll(List.of(new Entity2(ids[1], new Point2(6, 6))));
        assertEquals(List.of(new Entity2(ids[1], new Point2(6, 6)), new Entity2(ids[0], new Point2(5, 5))),
                p.getAll(new long[] {ids[1], ids[0]}));

        p.remove(ids[0]);
        assertEquals(Optional.empty(), p.get(ids[0]));
        p.removeAll(new long[] {ids[1]});
        assertEquals(0, p.size());
    }

    @Test
    void removalDiscardsUncommittedInsertionsWhenTheLastCommitIsEmpty() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        partition.insert(new Point2(1, 2));
        space.removePartition(partition);
        space.setTick(1);
        try (var snapshot = partition.acquireLatest()) {
            assertEquals(0, snapshot.size());
        }
    }

    @Test
    void closingAViewTwiceReleasesItAndKeepsItsTick() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        space.setTick(1);
        AcquiredSnapshot2 snapshot = partition.acquireLatest();
        snapshot.close();
        snapshot.close();
        assertThrows(IllegalStateException.class, snapshot::size);
        assertEquals(0, snapshot.tick());
        space.removePartition(partition);
        space.setTick(2);
        space.setTick(3);
        assertThrows(IllegalArgumentException.class, partition::tick);
    }

    @Test
    void generatorsAreSharedAcrossPartitionsAndSpaces() {
        AtomicLong entityIds = new AtomicLong();
        AtomicLong partitionIds = new AtomicLong();
        SpaceConfig config = SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet);
        LocalPartitionNode2 first = new LocalPartitionNode2();
        LocalPartitionNode2 second = new LocalPartitionNode2();
        Partition2 a = new Space2(config).createPartition(first);
        Partition2 b = new Space2(config).createPartition(second);
        assertEquals(1, a.insert(new Point2(1, 2)));
        assertEquals(2, b.insert(new Point2(1, 2)));
        assertEquals(0, first.creationTick(new PartitionId(1)));
        assertEquals(0, second.creationTick(new PartitionId(2)));
    }

    @Test
    void writesAndQueriesStayOnTheChosenPartitionAndNode() {
        LocalPartitionNode2 node = new LocalPartitionNode2();
        Partition2 first = space.createPartition(node);
        Partition2 sameNode = space.createPartition(node);
        Partition2 otherNode = space.createPartition(new LocalPartitionNode2());
        Point2 position = new Point2(1, 2);
        long id = first.insert(position);
        Box2 region = new Box2(position, position);
        assertEquals(List.of(new Entity2(id, position)), first.findInRegion(region));
        assertEquals(List.of(new Entity2(id, position)), first.findNearest(position, 1));
        assertEquals(0, sameNode.size());
        assertEquals(0, otherNode.size());
        assertEquals(Optional.empty(), sameNode.get(id));
        assertEquals(Optional.empty(), otherNode.get(id));
    }

    @Test
    void repeatingTheTickDoesNotPublishAnUnfinishedTick() {
        assertEquals(0, space.tick());
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        space.setTick(4);
        partition.insert(new Point2(1, 2));
        partition.commit();
        space.setTick(4);
        assertEquals(4, space.tick());
        try (var snapshot = partition.acquireLatest()) {
            assertEquals(3, snapshot.tick());
            assertEquals(0, snapshot.size());
        }
        space.setTick(5);
        try (var snapshot = partition.acquireLatest()) {
            assertEquals(4, snapshot.tick());
            assertEquals(1, snapshot.size());
        }
    }

    /** Checks that the acquisition fails as not available, for the given reason. */
    private static void assertReason(Reason reason, Executable acquisition) {
        assertEquals(reason, assertThrows(SnapshotNotAvailableException.class, acquisition).reason());
    }
}
