package badspace.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.RemovalPolicy;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.ConditionalWrite2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataCondition;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.Ownership;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionId;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.persistence.PartitionCopy2;
import badspace.service.persistence.PartitionSave2;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * The API together with the local nodes of the service: spaces, partitions, ticks, commits,
 * acquisitions and removal from the point of view of the software. The semantics in detail is
 * tested in the service; Space2Test in the api module checks the calls of the API with framework mocks.
 */
class Space2EndToEndTest {

    private final AtomicLong entityIds = new AtomicLong();
    private final AtomicLong partitionIds = new AtomicLong();
    private final SpaceConfig config = SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Box2 ALL = new Box2(new Point2(-100, -100), new Point2(100, 100));

    private final Space2 space = new Space2(config);
    // The writer handles of the partitions, made by writer(partition). Used only by the test thread.
    private final Map<Partition2, PartitionWriter2> writers = new IdentityHashMap<>();

    @Test
    void readerSeesACommitOnlyAfterTheMoveOfTheTick() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        long a = writer(partition).insert(1, 2);
        long b = writer(partition).insert(5, 5);
        writer(partition).commit();
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
        long id = writer(partition).insert(0, 0);
        for (int tick = 0; tick < 5; tick++) {
            writer(partition).update(id, new Point2(tick, 0));
            writer(partition).commit();
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
        // The writes not yet published are not in any snapshot, also for the writer.
        writer(partition).update(id, new Point2(9, 9));
        try (AcquiredSnapshot2 latest = partition.acquireLatest()) {
            assertEquals(new Point2(4, 0), latest.get(id).orElseThrow());
        }
    }

    @Test
    void acquiredSnapshotStaysReadableAfterItLeavesTheWindow() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        long id = writer(partition).insert(1, 1);
        writer(partition).commit();
        space.setTick(1);
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            writer(partition).remove(id);
            writer(partition).commit();
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
        writer(partition).insert(1, 1);
        writer(partition).commit();
        space.setTick(1);
        writer(partition).insert(2, 2);
        writer(partition).removePartition();
        assertThrows(IllegalStateException.class, () -> writer(partition).insert(3, 3));
        assertThrows(IllegalStateException.class, writer(partition)::commit);

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
    void saveWritesTheSnapshotOfTheTickAndReturnsItsReference(@TempDir Path directory) throws IOException {
        Partition2 partition = space.createPartition(new LocalPartitionNode2(directory),
                PartitionConfig.defaults().withRetainedTicks(3));
        long a = writer(partition).insert(1, 2);
        writer(partition).commit();
        space.setTick(1);
        writer(partition).insert(3, 4);
        writer(partition).commit();
        space.setTick(2);

        SavedPartition first = partition.save(0);
        SavedPartition latest = partition.saveLatest();
        assertEquals(0, first.tick());
        assertEquals(1, latest.tick());
        assertEquals(first.partitionId(), latest.partitionId());
        // The test knows where the local node writes; the reference does not say it.
        PartitionCopy2 saved = PartitionSave2.read(fileOf(directory, first), first.partitionId());
        assertEquals(1, saved.size());
        assertEquals(a, saved.entityId(0));
        assertEquals(new Point2(1, 2), saved.position(0));
        assertEquals(2, PartitionSave2.read(fileOf(directory, latest), latest.partitionId()).size());
    }

    @Test
    void removedPartitionIsSavedWhileItsTicksAreInTheWindow(@TempDir Path directory) {
        Partition2 partition = space.createPartition(new LocalPartitionNode2(directory),
                PartitionConfig.defaults().withRetainedTicks(2));
        writer(partition).commit();
        space.setTick(1);
        writer(partition).removePartition();
        space.setTick(2);
        assertEquals(1, partition.save(1).tick());
        assertReason(Reason.AFTER_REMOVAL, () -> partition.save(2));
    }

    @Test
    void saveNeedsANodeWithASaveDestination() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        space.setTick(1);
        assertThrows(IllegalStateException.class, partition::saveLatest);
    }

    @Test
    void removalThatRequiresEmptyLooksAtTheLastCommit() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        long id = writer(partition).insert(1, 1);
        writer(partition).commit();
        assertThrows(IllegalStateException.class, () -> writer(partition).removePartition());
        writer(partition).remove(id);
        assertThrows(IllegalStateException.class, () -> writer(partition).removePartition(),
                "the removal of the entity is not committed");
        writer(partition).commit();
        writer(partition).removePartition();
        space.setTick(1);
        try (AcquiredSnapshot2 last = partition.acquireLatest()) {
            assertEquals(0, last.size());
        }
    }

    @Test
    void getUpdateAndRemoveUseTheEntityId() {
        Partition2 p = space.createPartition(new LocalPartitionNode2());
        long[] ids = writer(p).insertAll(List.of(new Point2(1, 1), new Point2(2, 2)));
        try (AcquiredSnapshot2 snapshot = publish(p)) {
            assertEquals(Optional.of(new Point2(1, 1)), snapshot.get(ids[0]));
        }

        writer(p).update(ids[0], new Point2(5, 5));
        writer(p).updateAll(List.of(new Entity2(ids[1], new Point2(6, 6))));
        try (AcquiredSnapshot2 snapshot = publish(p)) {
            assertEquals(List.of(new Entity2(ids[1], new Point2(6, 6)), new Entity2(ids[0], new Point2(5, 5))),
                    snapshot.getAll(new long[] {ids[1], ids[0]}));
        }

        writer(p).remove(ids[0]);
        try (AcquiredSnapshot2 snapshot = publish(p)) {
            assertEquals(Optional.empty(), snapshot.get(ids[0]));
        }
        writer(p).removeAll(new long[] {ids[1]});
        try (AcquiredSnapshot2 snapshot = publish(p)) {
            assertEquals(0, snapshot.size());
        }
    }

    @Test
    void metadataAreReadFromTheSnapshotOfTheCommitThatWroteThem() {
        Partition2 p = space.createPartition(new LocalPartitionNode2());
        long[] ids = writer(p).insertAll(List.of(new Point2(1, 1), new Point2(2, 2)));
        writer(p).putMetadata(ids[0], "version", 1);
        writer(p).putMetadata(ids[0], "owner", 7);
        try (AcquiredSnapshot2 first = publish(p)) {
            writer(p).updateMetadataAll(List.of(MetadataWrite.put(ids[0], "version", 2),
                    MetadataWrite.put(ids[1], "version", 1)));
            writer(p).deleteMetadata(ids[0], "owner");
            try (AcquiredSnapshot2 second = publish(p)) {
                assertEquals(Optional.of(Map.of("version", 1L, "owner", 7L)), first.getMetadata(ids[0]));
                assertEquals(Optional.of(Map.of()), first.getMetadata(ids[1]));
                assertEquals(List.of(new ApplicationMetadata(ids[1], Map.of("version", 1L)),
                        new ApplicationMetadata(ids[0], Map.of("version", 2L))),
                        second.getMetadataAll(new long[] {ids[1], -1, ids[0]}));
            }
        }

        writer(p).remove(ids[0]);
        try (AcquiredSnapshot2 snapshot = publish(p)) {
            assertEquals(Optional.empty(), snapshot.getMetadata(ids[0]));
        }
    }

    @Test
    void metadataWriteOnAMissingEntityChangesNothing() {
        Partition2 p = space.createPartition(new LocalPartitionNode2());
        long id = writer(p).insert(new Point2(1, 1));
        assertThrows(NoSuchElementException.class, () -> writer(p).updateMetadataAll(
                List.of(MetadataWrite.put(id, "version", 1), MetadataWrite.put(id + 1000, "version", 1))));
        try (AcquiredSnapshot2 snapshot = publish(p)) {
            assertEquals(Optional.of(Map.of()), snapshot.getMetadata(id));
        }
    }

    @Test
    void conditionalWritesApplyOnlyWhenTheirConditionsAreTrue() {
        Partition2 p = space.createPartition(new LocalPartitionNode2());
        long[] ids = writer(p).insertAll(List.of(new Point2(1, 1), new Point2(2, 2)));
        writer(p).putMetadata(ids[0], "version", 1);
        assertTrue(writer(p).compareAndSetMetadata(ids[0], "version", 1, 2));
        assertFalse(writer(p).compareAndSetMetadata(ids[0], "version", 1, 3));

        // A reservation of two entities: both must be free, or nothing changes.
        MetadataCondition firstFree = MetadataCondition.absent(ids[0], "reservedBy");
        MetadataCondition secondFree = MetadataCondition.absent(ids[1], "reservedBy");
        ConditionalWrite2 reserve = new ConditionalWrite2(List.of(firstFree, secondFree),
                List.of(new Entity2(ids[0], new Point2(5, 5))),
                List.of(MetadataWrite.put(ids[0], "reservedBy", 9), MetadataWrite.put(ids[1], "reservedBy", 9)));
        assertEquals(List.of(), writer(p).updateIf(reserve));
        assertEquals(List.of(firstFree, secondFree), writer(p).updateIf(reserve));

        try (AcquiredSnapshot2 snapshot = publish(p)) {
            assertEquals(Optional.of(new Point2(5, 5)), snapshot.get(ids[0]));
            assertEquals(List.of(new ApplicationMetadata(ids[0], Map.of("version", 2L, "reservedBy", 9L)),
                    new ApplicationMetadata(ids[1], Map.of("reservedBy", 9L))),
                    snapshot.getMetadataAll(ids));
        }
    }

    @Test
    void removalDiscardsUncommittedInsertionsWhenTheLastCommitIsEmpty() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        writer(partition).insert(new Point2(1, 2));
        writer(partition).removePartition();
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
        writer(partition).removePartition();
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
        assertEquals(1, writer(a).insert(new Point2(1, 2)));
        assertEquals(2, writer(b).insert(new Point2(1, 2)));
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
        long id = writer(first).insert(position);
        Box2 region = new Box2(position, position);
        for (Partition2 partition : List.of(first, sameNode, otherNode)) {
            writer(partition).commit();
        }
        space.setTick(1);
        try (AcquiredSnapshot2 snapshot = first.acquireLatest()) {
            assertEquals(List.of(new Entity2(id, position)), snapshot.findInRegion(region));
            assertEquals(List.of(new Entity2(id, position)), snapshot.findNearest(position, 1));
        }
        for (Partition2 partition : List.of(sameNode, otherNode)) {
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                assertEquals(0, snapshot.size());
                assertEquals(Optional.empty(), snapshot.get(id));
            }
        }
    }

    @Test
    void repeatingTheTickDoesNotPublishAnUnfinishedTick() {
        assertEquals(0, space.tick());
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        space.setTick(4);
        writer(partition).insert(new Point2(1, 2));
        writer(partition).commit();
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
        LocalPartitionNode2 node = new LocalPartitionNode2();
        List<Partition2> partitions = List.of(space.createPartition(node), space.createPartition(node));
        int batches = 50;
        int batchSize = 20;
        CyclicBarrier start = new CyclicBarrier(partitions.size());
        List<long[]> written = new ArrayList<>();
        ExecutorService writers = Executors.newFixedThreadPool(partitions.size());
        try {
            List<Future<long[]>> results = new ArrayList<>();
            for (int w = 0; w < partitions.size(); w++) {
                PartitionWriter2 partitionWriter = writer(partitions.get(w));
                int writer = w;
                results.add(writers.submit(() -> {
                    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    long[] ids = new long[batches * batchSize];
                    for (int b = 0; b < batches; b++) {
                        List<Point2> positions = new ArrayList<>();
                        for (int i = 0; i < batchSize; i++) {
                            positions.add(new Point2(writer, i));
                        }
                        System.arraycopy(partitionWriter.insertAll(positions), 0, ids, b * batchSize, batchSize);
                        partitionWriter.commit();
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
            try (AcquiredSnapshot2 snapshot = partitions.get(w).acquireLatest()) {
                assertEquals(ids.length, snapshot.size());
                assertEquals(ids.length, snapshot.getAll(ids).size());
            }
        }
        assertEquals(partitions.size() * batches * batchSize, allIds.size());
    }

    @Test
    void newPartitionHasNoOwnerUntilAWriterClaimsIt() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        assertEquals(new Ownership(0, false), partition.ownership());
        WriteToken token = partition.claim(0).orElseThrow();
        assertEquals(new Ownership(1, true), partition.ownership());
        assertEquals(Optional.empty(), partition.claim(0), "the generation is not 0 any more");
        try (PartitionWriter2 writer = partition.writer(token)) {
            writer.insert(new Point2(1, 1));
            writer.commit();
        }
        assertEquals(new Ownership(2, false), partition.ownership());
    }

    @Test
    void ownerPassesTheOwnershipWithATokenThatTheNextWriterUses() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        PartitionWriter2 first = partition.writer(partition.claim(0).orElseThrow());
        long a = first.insert(new Point2(1, 1));
        first.commit();
        // The owner claims with the generation of its own token and sends the new token on.
        WriteToken sent = partition.claim(first.token().generation()).orElseThrow();
        try (PartitionWriter2 next = partition.writer(sent)) {
            assertThrows(OwnershipChangedException.class, () -> first.insert(new Point2(2, 2)));
            assertThrows(OwnershipChangedException.class, first::commit);
            long b = next.insert(new Point2(3, 3));
            try (AcquiredSnapshot2 snapshot = publish(partition, next)) {
                assertEquals(Optional.of(new Point2(1, 1)), snapshot.get(a));
                assertEquals(Optional.of(new Point2(3, 3)), snapshot.get(b));
                assertEquals(2, snapshot.size());
            }
        }
        // The old handle names a token that is not current: its close does nothing.
        first.close();
        assertEquals(new Ownership(3, false), partition.ownership());
    }

    @Test
    void coordinatorTakesTheOwnershipAwayAndTheWritesNotCommittedAreLost() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        PartitionWriter2 slow = partition.writer(partition.claim(0).orElseThrow());
        long kept = slow.insert(new Point2(1, 1));
        slow.commit();
        long lost = slow.insert(new Point2(2, 2));
        // The coordinator does not have the token of the slow writer: it reads the generation.
        WriteToken taken = partition.claim(partition.ownership().generation()).orElseThrow();
        assertThrows(OwnershipChangedException.class, slow::commit);
        try (PartitionWriter2 next = partition.writer(taken);
                AcquiredSnapshot2 snapshot = publish(partition, next)) {
            assertEquals(Optional.of(new Point2(1, 1)), snapshot.get(kept));
            assertEquals(Optional.empty(), snapshot.get(lost));
        }
    }

    @Test
    void closeAfterAnExceptionDiscardsTheWritesNotCommitted() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        long[] ids = new long[2];
        assertThrows(IllegalStateException.class, () -> {
            try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
                ids[0] = writer.insert(new Point2(1, 1));
                writer.commit();
                ids[1] = writer.insert(new Point2(2, 2));
                throw new IllegalStateException("the writer fails before its commit");
            }
        });
        assertEquals(new Ownership(2, false), partition.ownership());
        try (PartitionWriter2 writer = partition.writer(partition.claim(2).orElseThrow());
                AcquiredSnapshot2 snapshot = publish(partition, writer)) {
            assertEquals(Optional.of(new Point2(1, 1)), snapshot.get(ids[0]));
            assertEquals(Optional.empty(), snapshot.get(ids[1]));
        }
    }

    @Test
    void closeAfterTheDestructionOfThePartitionDoesNothing() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow());
        writer.removePartition();
        writer.close();
        space.setTick(1);
        space.setTick(2);
        assertThrows(IllegalArgumentException.class, partition::ownership);
        writer.close();
        writer.close();
    }

    /** Checks that the acquisition fails as not available, for the given reason. */
    private static void assertReason(Reason reason, Executable acquisition) {
        assertEquals(reason, assertThrows(SnapshotNotAvailableException.class, acquisition).reason());
    }

    /** Commits the writes of a partition, moves the tick by one and acquires the snapshot just published. */
    private AcquiredSnapshot2 publish(Partition2 partition) {
        return publish(partition, writer(partition));
    }

    /**
     * Commits the writes of the handle of a partition, moves the tick by one and acquires the
     * snapshot just published.
     */
    private AcquiredSnapshot2 publish(Partition2 partition, PartitionWriter2 writer) {
        writer.commit();
        space.setTick(space.tick() + 1);
        return partition.acquireLatest();
    }

    /**
     * Returns the writer handle of a partition for the tests that are not about the ownership:
     * the first call claims the new partition, the next calls give the same handle.
     */
    /** Returns the file of a save of the local node, in its save directory. */
    private static Path fileOf(Path directory, SavedPartition saved) {
        return directory.resolve("partitions").resolve(Long.toString(saved.partitionId().value()))
                .resolve(saved.tick() + ".bsave");
    }

    private PartitionWriter2 writer(Partition2 partition) {
        return writers.computeIfAbsent(partition, created -> created.writer(created.claim(0).orElseThrow()));
    }
}
