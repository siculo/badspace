package badspace.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.api.AcquiredSnapshot3;
import badspace.api.Partition3;
import badspace.api.PartitionConfig;
import badspace.api.RemovalPolicy;
import badspace.api.Space3;
import badspace.api.SpaceConfig;
import badspace.common.geometry.Box3;
import badspace.common.geometry.Point3;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.service.partition.LocalPartitionNode3;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The API together with the local nodes of the service: spaces, partitions, ticks, commits,
 * acquisitions and removal from the point of view of the software. The semantics in detail is
 * tested in the service; Space3Test in the api module checks the calls of the API with framework mocks.
 */
class Space3EndToEndTest {

    private final AtomicLong entityIds = new AtomicLong();
    private final AtomicLong partitionIds = new AtomicLong();
    private final SpaceConfig config = SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Box3 ALL = new Box3(new Point3(-100, -100, -100), new Point3(100, 100, 100));

    private final Space3 space = new Space3(config);

    @Test
    void readerSeesACommitOnlyAfterTheMoveOfTheTick() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3());
        long a = partition.insert(1, 2, 0);
        long b = partition.insert(5, 5, 0);
        partition.commit();
        assertReason(Reason.NOT_YET_PUBLISHED, partition::acquireLatest);

        space.setTick(1);
        try (AcquiredSnapshot3 snapshot = partition.acquireLatest()) {
            assertEquals(0, snapshot.tick());
            assertEquals(2, snapshot.size());
            assertEquals(List.of(new Entity3(a, new Point3(1, 2, 0))), snapshot.findNearest(new Point3(0, 0, 0), 1));
            assertEquals(2, snapshot.findInRegion(ALL).size());
            assertEquals(new Point3(5, 5, 0), snapshot.get(b).orElseThrow());
        }
    }

    @Test
    void snapshotsOfTheWindowKeepTheStateOfTheirTick() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(),
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(4)).withRetainedTicks(3));
        long id = partition.insert(0, 0, 0);
        for (int tick = 0; tick < 5; tick++) {
            partition.update(id, new Point3(tick, 0, 0));
            partition.commit();
            space.setTick(tick + 1);
        }
        // At tick 5 with k = 3: the snapshots of the ticks 2, 3 and 4.
        for (long tick = 2; tick < 5; tick++) {
            try (AcquiredSnapshot3 snapshot = partition.acquire(tick)) {
                assertEquals(new Point3(tick, 0, 0), snapshot.get(id).orElseThrow());
            }
        }
        assertReason(Reason.OUT_OF_RETENTION, () -> partition.acquire(1));
        assertReason(Reason.NOT_YET_PUBLISHED, () -> partition.acquire(5));
        // The writes not yet published are not in any snapshot, also for the writer.
        partition.update(id, new Point3(9, 9, 0));
        try (AcquiredSnapshot3 latest = partition.acquireLatest()) {
            assertEquals(new Point3(4, 0, 0), latest.get(id).orElseThrow());
        }
    }

    @Test
    void acquiredSnapshotStaysReadableAfterItLeavesTheWindow() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3());
        long id = partition.insert(1, 1, 0);
        partition.commit();
        space.setTick(1);
        try (AcquiredSnapshot3 snapshot = partition.acquireLatest()) {
            partition.remove(id);
            partition.commit();
            space.setTick(5);
            assertReason(Reason.OUT_OF_RETENTION, () -> partition.acquire(0));
            assertEquals(1, snapshot.size());
            try (AcquiredSnapshot3 latest = partition.acquireLatest()) {
                assertEquals(0, latest.size());
            }
        }
    }

    @Test
    void partitionsOnDifferentNodesMoveWithTheSpace() {
        LocalPartitionNode3 first = new LocalPartitionNode3();
        Partition3 a = space.createPartition(first);
        Partition3 b = space.createPartition(new LocalPartitionNode3());
        space.setTick(5);
        assertEquals(5, a.tick());
        assertEquals(5, b.tick());
        assertEquals(5, first.tick());

        // A new node gets the tick of the space before the creation.
        LocalPartitionNode3 later = new LocalPartitionNode3();
        Partition3 c = space.createPartition(later);
        assertEquals(5, later.tick());
        assertEquals(5, c.creationTick());
        assertEquals(0, a.creationTick());
        space.setTick(6);
        assertEquals(6, c.tick());
    }

    @Test
    void removedPartitionGivesItsTicksUntilItIsDestroyed() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(),
                PartitionConfig.defaults().withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES).withRetainedTicks(2));
        partition.insert(1, 1, 0);
        partition.commit();
        space.setTick(1);
        partition.insert(2, 2, 0);
        space.removePartition(partition);
        assertThrows(IllegalStateException.class, () -> partition.insert(3, 3, 0));
        assertThrows(IllegalStateException.class, partition::commit);

        // The removal is effective at tick 2: the writes not committed were discarded.
        space.setTick(2);
        AcquiredSnapshot3 kept = partition.acquire(1);
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
        Partition3 partition = space.createPartition(new LocalPartitionNode3());
        long id = partition.insert(1, 1, 0);
        partition.commit();
        assertThrows(IllegalStateException.class, () -> space.removePartition(partition));
        partition.remove(id);
        assertThrows(IllegalStateException.class, () -> space.removePartition(partition),
                "the removal of the entity is not committed");
        partition.commit();
        space.removePartition(partition);
        space.setTick(1);
        try (AcquiredSnapshot3 last = partition.acquireLatest()) {
            assertEquals(0, last.size());
        }
    }

    @Test
    void getUpdateAndRemoveUseTheEntityId() {
        Partition3 p = space.createPartition(new LocalPartitionNode3());
        long[] ids = p.insertAll(List.of(new Point3(1, 1, 1), new Point3(2, 2, 2)));
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(Optional.of(new Point3(1, 1, 1)), snapshot.get(ids[0]));
        }

        p.update(ids[0], new Point3(5, 5, 5));
        p.updateAll(List.of(new Entity3(ids[1], new Point3(6, 6, 6))));
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(List.of(new Entity3(ids[1], new Point3(6, 6, 6)), new Entity3(ids[0], new Point3(5, 5, 5))),
                    snapshot.getAll(new long[] {ids[1], ids[0]}));
        }

        p.remove(ids[0]);
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(Optional.empty(), snapshot.get(ids[0]));
        }
        p.removeAll(new long[] {ids[1]});
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(0, snapshot.size());
        }
    }

    @Test
    void removalDiscardsUncommittedInsertionsWhenTheLastCommitIsEmpty() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3());
        partition.insert(new Point3(1, 2, 3));
        space.removePartition(partition);
        space.setTick(1);
        try (var snapshot = partition.acquireLatest()) {
            assertEquals(0, snapshot.size());
        }
    }

    @Test
    void closingAViewTwiceReleasesItAndKeepsItsTick() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3());
        space.setTick(1);
        AcquiredSnapshot3 snapshot = partition.acquireLatest();
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
        LocalPartitionNode3 first = new LocalPartitionNode3();
        LocalPartitionNode3 second = new LocalPartitionNode3();
        Partition3 a = new Space3(config).createPartition(first);
        Partition3 b = new Space3(config).createPartition(second);
        assertEquals(1, a.insert(new Point3(1, 2, 3)));
        assertEquals(2, b.insert(new Point3(1, 2, 3)));
        assertEquals(0, first.creationTick(new PartitionId(1)));
        assertEquals(0, second.creationTick(new PartitionId(2)));
    }

    @Test
    void writesAndQueriesStayOnTheChosenPartitionAndNode() {
        LocalPartitionNode3 node = new LocalPartitionNode3();
        Partition3 first = space.createPartition(node);
        Partition3 sameNode = space.createPartition(node);
        Partition3 otherNode = space.createPartition(new LocalPartitionNode3());
        Point3 position = new Point3(1, 2, 3);
        long id = first.insert(position);
        Box3 region = new Box3(position, position);
        for (Partition3 partition : List.of(first, sameNode, otherNode)) {
            partition.commit();
        }
        space.setTick(1);
        try (AcquiredSnapshot3 snapshot = first.acquireLatest()) {
            assertEquals(List.of(new Entity3(id, position)), snapshot.findInRegion(region));
            assertEquals(List.of(new Entity3(id, position)), snapshot.findNearest(position, 1));
        }
        for (Partition3 partition : List.of(sameNode, otherNode)) {
            try (AcquiredSnapshot3 snapshot = partition.acquireLatest()) {
                assertEquals(0, snapshot.size());
                assertEquals(Optional.empty(), snapshot.get(id));
            }
        }
    }

    @Test
    void repeatingTheTickDoesNotPublishAnUnfinishedTick() {
        assertEquals(0, space.tick());
        Partition3 partition = space.createPartition(new LocalPartitionNode3());
        space.setTick(4);
        partition.insert(new Point3(1, 2, 3));
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

    /**
     * Two writers insert into two partitions of the same node, each on its own thread, and
     * share the generator of the entity IDs. Each partition gets all the entities of its
     * writer, and the IDs are all different.
     */
    @Test
    void writersOfPartitionsOnTheSameNodeWorkOnTheirOwnThreads() throws Exception {
        LocalPartitionNode3 node = new LocalPartitionNode3();
        List<Partition3> partitions = List.of(space.createPartition(node), space.createPartition(node));
        int batches = 50;
        int batchSize = 20;
        CyclicBarrier start = new CyclicBarrier(partitions.size());
        List<long[]> written = new ArrayList<>();
        ExecutorService writers = Executors.newFixedThreadPool(partitions.size());
        try {
            List<Future<long[]>> results = new ArrayList<>();
            for (int w = 0; w < partitions.size(); w++) {
                Partition3 partition = partitions.get(w);
                int writer = w;
                results.add(writers.submit(() -> {
                    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    long[] ids = new long[batches * batchSize];
                    for (int b = 0; b < batches; b++) {
                        List<Point3> positions = new ArrayList<>();
                        for (int i = 0; i < batchSize; i++) {
                            positions.add(new Point3(writer, i, 0));
                        }
                        System.arraycopy(partition.insertAll(positions), 0, ids, b * batchSize, batchSize);
                        partition.commit();
                    }
                    return ids;
                }));
            }
            for (Future<long[]> result : results) {
                written.add(result.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
        } finally {
            // Stops a writer that is still running, also when the test fails.
            writers.shutdownNow();
            assertTrue(writers.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "a writer did not finish");
        }
        space.setTick(1);
        Set<Long> allIds = new HashSet<>();
        for (int w = 0; w < partitions.size(); w++) {
            long[] ids = written.get(w);
            for (long id : ids) {
                allIds.add(id);
            }
            try (AcquiredSnapshot3 snapshot = partitions.get(w).acquireLatest()) {
                assertEquals(ids.length, snapshot.size());
                assertEquals(ids.length, snapshot.getAll(ids).size());
            }
        }
        assertEquals(partitions.size() * batches * batchSize, allIds.size());
    }

    /** Checks that the acquisition fails as not available, for the given reason. */
    private static void assertReason(Reason reason, Executable acquisition) {
        assertEquals(reason, assertThrows(SnapshotNotAvailableException.class, acquisition).reason());
    }

    /** Commits the writes of a partition, moves the tick by one and acquires the snapshot just published. */
    private AcquiredSnapshot3 publish(Partition3 partition) {
        partition.commit();
        space.setTick(space.tick() + 1);
        return partition.acquireLatest();
    }
}
