package badspace.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.api.AcquiredSnapshot3;
import badspace.api.Partition3;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter3;
import badspace.api.RemovalPolicy;
import badspace.api.Space3;
import badspace.api.SpaceConfig;
import badspace.common.geometry.Box3;
import badspace.common.geometry.Point3;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.ConditionalWrite3;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataCondition;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.Ownership;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode3;
import badspace.service.persistence.PartitionCopy3;
import badspace.service.persistence.PartitionSave3;
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
 * tested in the service; Space3Test in the api module checks the calls of the API with framework mocks.
 */
class Space3EndToEndTest {

    private final AtomicLong entityIds = new AtomicLong();
    private final AtomicLong partitionIds = new AtomicLong();
    private final SpaceConfig config = SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Box3 ALL = new Box3(new Point3(-100, -100, -100), new Point3(100, 100, 100));

    private final Space3 space = new Space3(config);
    // Numbers the names of the partitions made by nextName(), so they are unique on every node.
    private final AtomicLong names = new AtomicLong();
    // The writer handles of the partitions, made by writer(partition). Used only by the test thread.
    private final Map<Partition3, PartitionWriter3> writers = new IdentityHashMap<>();

    @Test
    void readerSeesACommitOnlyAfterTheMoveOfTheTick() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        long a = writer(partition).insert(1, 2, 0);
        long b = writer(partition).insert(5, 5, 0);
        writer(partition).commit();
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
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName(),
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(4)).withRetainedTicks(3));
        long id = writer(partition).insert(0, 0, 0);
        for (int tick = 0; tick < 5; tick++) {
            writer(partition).update(id, new Point3(tick, 0, 0));
            writer(partition).commit();
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
        writer(partition).update(id, new Point3(9, 9, 0));
        try (AcquiredSnapshot3 latest = partition.acquireLatest()) {
            assertEquals(new Point3(4, 0, 0), latest.get(id).orElseThrow());
        }
    }

    @Test
    void acquiredSnapshotStaysReadableAfterItLeavesTheWindow() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        long id = writer(partition).insert(1, 1, 0);
        writer(partition).commit();
        space.setTick(1);
        try (AcquiredSnapshot3 snapshot = partition.acquireLatest()) {
            writer(partition).remove(id);
            writer(partition).commit();
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
        Partition3 a = space.createPartition(first, nextName());
        Partition3 b = space.createPartition(new LocalPartitionNode3(), nextName());
        space.setTick(5);
        assertEquals(5, a.tick());
        assertEquals(5, b.tick());
        assertEquals(5, first.tick());

        // A new node gets the tick of the space before the creation.
        LocalPartitionNode3 later = new LocalPartitionNode3();
        Partition3 c = space.createPartition(later, nextName());
        assertEquals(5, later.tick());
        assertEquals(5, c.creationTick());
        assertEquals(0, a.creationTick());
        space.setTick(6);
        assertEquals(6, c.tick());
    }

    @Test
    void removedPartitionGivesItsTicksUntilItIsDestroyed() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName(),
                PartitionConfig.defaults().withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES).withRetainedTicks(2));
        writer(partition).insert(1, 1, 0);
        writer(partition).commit();
        space.setTick(1);
        writer(partition).insert(2, 2, 0);
        writer(partition).removePartition();
        assertThrows(IllegalStateException.class, () -> writer(partition).insert(3, 3, 0));
        assertThrows(IllegalStateException.class, writer(partition)::commit);

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
    void saveWritesTheSnapshotOfTheTickAndReturnsItsReference(@TempDir Path directory) throws IOException {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(directory), nextName(),
                PartitionConfig.defaults().withRetainedTicks(3));
        long a = writer(partition).insert(1, 2, 0);
        writer(partition).commit();
        space.setTick(1);
        writer(partition).insert(3, 4, 0);
        writer(partition).commit();
        space.setTick(2);

        SavedPartition first = partition.save(0);
        SavedPartition latest = partition.saveLatest();
        assertEquals(0, first.tick());
        assertEquals(1, latest.tick());
        assertEquals(partition.name(), first.name());
        assertEquals(partition.name(), latest.name());
        // The test knows where the local node writes; the reference does not say it.
        PartitionCopy3 saved = PartitionSave3.read(fileOf(directory, first), first.name());
        assertEquals(1, saved.size());
        assertEquals(a, saved.entityId(0));
        assertEquals(new Point3(1, 2, 0), saved.position(0));
        assertEquals(2, PartitionSave3.read(fileOf(directory, latest), latest.name()).size());
    }

    @Test
    void removedPartitionIsSavedWhileItsTicksAreInTheWindow(@TempDir Path directory) {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(directory), nextName(),
                PartitionConfig.defaults().withRetainedTicks(2));
        writer(partition).commit();
        space.setTick(1);
        writer(partition).removePartition();
        space.setTick(2);
        assertEquals(1, partition.save(1).tick());
        assertReason(Reason.AFTER_REMOVAL, () -> partition.save(2));
    }

    @Test
    void nameOfAPartitionIsUniqueOnItsNode() {
        LocalPartitionNode3 node = new LocalPartitionNode3();
        Partition3 north = space.createPartition(node, new PartitionName("north"));
        assertEquals(new PartitionName("north"), north.name());
        assertThrows(IllegalArgumentException.class, () -> space.createPartition(node, new PartitionName("north")));
        // Names are checked only on the same node: the software keeps them unique across nodes.
        space.createPartition(new LocalPartitionNode3(), new PartitionName("north"));
    }

    @Test
    void saveNeedsANodeWithASaveDestination() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        space.setTick(1);
        assertThrows(IllegalStateException.class, partition::saveLatest);
    }

    @Test
    void removalThatRequiresEmptyLooksAtTheLastCommit() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        long id = writer(partition).insert(1, 1, 0);
        writer(partition).commit();
        assertThrows(IllegalStateException.class, () -> writer(partition).removePartition());
        writer(partition).remove(id);
        assertThrows(IllegalStateException.class, () -> writer(partition).removePartition(),
                "the removal of the entity is not committed");
        writer(partition).commit();
        writer(partition).removePartition();
        space.setTick(1);
        try (AcquiredSnapshot3 last = partition.acquireLatest()) {
            assertEquals(0, last.size());
        }
    }

    @Test
    void getUpdateAndRemoveUseTheEntityId() {
        Partition3 p = space.createPartition(new LocalPartitionNode3(), nextName());
        long[] ids = writer(p).insertAll(List.of(new Point3(1, 1, 1), new Point3(2, 2, 2)));
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(Optional.of(new Point3(1, 1, 1)), snapshot.get(ids[0]));
        }

        writer(p).update(ids[0], new Point3(5, 5, 5));
        writer(p).updateAll(List.of(new Entity3(ids[1], new Point3(6, 6, 6))));
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(List.of(new Entity3(ids[1], new Point3(6, 6, 6)), new Entity3(ids[0], new Point3(5, 5, 5))),
                    snapshot.getAll(new long[] {ids[1], ids[0]}));
        }

        writer(p).remove(ids[0]);
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(Optional.empty(), snapshot.get(ids[0]));
        }
        writer(p).removeAll(new long[] {ids[1]});
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(0, snapshot.size());
        }
    }

    @Test
    void metadataAreReadFromTheSnapshotOfTheCommitThatWroteThem() {
        Partition3 p = space.createPartition(new LocalPartitionNode3(), nextName());
        long[] ids = writer(p).insertAll(List.of(new Point3(1, 1, 1), new Point3(2, 2, 2)));
        writer(p).putMetadata(ids[0], "version", 1);
        writer(p).putMetadata(ids[0], "owner", 7);
        try (AcquiredSnapshot3 first = publish(p)) {
            writer(p).updateMetadataAll(List.of(MetadataWrite.put(ids[0], "version", 2),
                    MetadataWrite.put(ids[1], "version", 1)));
            writer(p).deleteMetadata(ids[0], "owner");
            try (AcquiredSnapshot3 second = publish(p)) {
                assertEquals(Optional.of(Map.of("version", 1L, "owner", 7L)), first.getMetadata(ids[0]));
                assertEquals(Optional.of(Map.of()), first.getMetadata(ids[1]));
                assertEquals(List.of(new ApplicationMetadata(ids[1], Map.of("version", 1L)),
                        new ApplicationMetadata(ids[0], Map.of("version", 2L))),
                        second.getMetadataAll(new long[] {ids[1], -1, ids[0]}));
            }
        }

        writer(p).remove(ids[0]);
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(Optional.empty(), snapshot.getMetadata(ids[0]));
        }
    }

    @Test
    void metadataWriteOnAMissingEntityChangesNothing() {
        Partition3 p = space.createPartition(new LocalPartitionNode3(), nextName());
        long id = writer(p).insert(new Point3(1, 1, 1));
        assertThrows(NoSuchElementException.class, () -> writer(p).updateMetadataAll(
                List.of(MetadataWrite.put(id, "version", 1), MetadataWrite.put(id + 1000, "version", 1))));
        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(Optional.of(Map.of()), snapshot.getMetadata(id));
        }
    }

    @Test
    void conditionalWritesApplyOnlyWhenTheirConditionsAreTrue() {
        Partition3 p = space.createPartition(new LocalPartitionNode3(), nextName());
        long[] ids = writer(p).insertAll(List.of(new Point3(1, 1, 1), new Point3(2, 2, 2)));
        writer(p).putMetadata(ids[0], "version", 1);
        assertTrue(writer(p).compareAndSetMetadata(ids[0], "version", 1, 2));
        assertFalse(writer(p).compareAndSetMetadata(ids[0], "version", 1, 3));

        // A reservation of two entities: both must be free, or nothing changes.
        MetadataCondition firstFree = MetadataCondition.absent(ids[0], "reservedBy");
        MetadataCondition secondFree = MetadataCondition.absent(ids[1], "reservedBy");
        ConditionalWrite3 reserve = new ConditionalWrite3(List.of(firstFree, secondFree),
                List.of(new Entity3(ids[0], new Point3(5, 5, 5))),
                List.of(MetadataWrite.put(ids[0], "reservedBy", 9), MetadataWrite.put(ids[1], "reservedBy", 9)));
        assertEquals(List.of(), writer(p).updateIf(reserve));
        assertEquals(List.of(firstFree, secondFree), writer(p).updateIf(reserve));

        try (AcquiredSnapshot3 snapshot = publish(p)) {
            assertEquals(Optional.of(new Point3(5, 5, 5)), snapshot.get(ids[0]));
            assertEquals(List.of(new ApplicationMetadata(ids[0], Map.of("version", 2L, "reservedBy", 9L)),
                    new ApplicationMetadata(ids[1], Map.of("reservedBy", 9L))),
                    snapshot.getMetadataAll(ids));
        }
    }

    @Test
    void removalDiscardsUncommittedInsertionsWhenTheLastCommitIsEmpty() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        writer(partition).insert(new Point3(1, 2, 3));
        writer(partition).removePartition();
        space.setTick(1);
        try (var snapshot = partition.acquireLatest()) {
            assertEquals(0, snapshot.size());
        }
    }

    @Test
    void closingAViewTwiceReleasesItAndKeepsItsTick() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        space.setTick(1);
        AcquiredSnapshot3 snapshot = partition.acquireLatest();
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
        LocalPartitionNode3 first = new LocalPartitionNode3();
        LocalPartitionNode3 second = new LocalPartitionNode3();
        Partition3 a = new Space3(config).createPartition(first, nextName());
        Partition3 b = new Space3(config).createPartition(second, nextName());
        assertEquals(1, writer(a).insert(new Point3(1, 2, 3)));
        assertEquals(2, writer(b).insert(new Point3(1, 2, 3)));
        assertEquals(0, first.creationTick(new PartitionId(1)));
        assertEquals(0, second.creationTick(new PartitionId(2)));
    }

    @Test
    void writesAndQueriesStayOnTheChosenPartitionAndNode() {
        LocalPartitionNode3 node = new LocalPartitionNode3();
        Partition3 first = space.createPartition(node, nextName());
        Partition3 sameNode = space.createPartition(node, nextName());
        Partition3 otherNode = space.createPartition(new LocalPartitionNode3(), nextName());
        Point3 position = new Point3(1, 2, 3);
        long id = writer(first).insert(position);
        Box3 region = new Box3(position, position);
        for (Partition3 partition : List.of(first, sameNode, otherNode)) {
            writer(partition).commit();
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
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        space.setTick(4);
        writer(partition).insert(new Point3(1, 2, 3));
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
        LocalPartitionNode3 node = new LocalPartitionNode3();
        List<Partition3> partitions = List.of(space.createPartition(node, nextName()), space.createPartition(node, nextName()));
        int batches = 50;
        int batchSize = 20;
        CyclicBarrier start = new CyclicBarrier(partitions.size());
        List<long[]> written = new ArrayList<>();
        ExecutorService writers = Executors.newFixedThreadPool(partitions.size());
        try {
            List<Future<long[]>> results = new ArrayList<>();
            for (int w = 0; w < partitions.size(); w++) {
                PartitionWriter3 partitionWriter = writer(partitions.get(w));
                int writer = w;
                results.add(writers.submit(() -> {
                    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    long[] ids = new long[batches * batchSize];
                    for (int b = 0; b < batches; b++) {
                        List<Point3> positions = new ArrayList<>();
                        for (int i = 0; i < batchSize; i++) {
                            positions.add(new Point3(writer, i, 0));
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
            try (AcquiredSnapshot3 snapshot = partitions.get(w).acquireLatest()) {
                assertEquals(ids.length, snapshot.size());
                assertEquals(ids.length, snapshot.getAll(ids).size());
            }
        }
        assertEquals(partitions.size() * batches * batchSize, allIds.size());
    }

    @Test
    void newPartitionHasNoOwnerUntilAWriterClaimsIt() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        assertEquals(new Ownership(0, false), partition.ownership());
        WriteToken token = partition.claim(0).orElseThrow();
        assertEquals(new Ownership(1, true), partition.ownership());
        assertEquals(Optional.empty(), partition.claim(0), "the generation is not 0 any more");
        try (PartitionWriter3 writer = partition.writer(token)) {
            writer.insert(new Point3(1, 1, 1));
            writer.commit();
        }
        assertEquals(new Ownership(2, false), partition.ownership());
    }

    @Test
    void ownerPassesTheOwnershipWithATokenThatTheNextWriterUses() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        PartitionWriter3 first = partition.writer(partition.claim(0).orElseThrow());
        long a = first.insert(new Point3(1, 1, 1));
        first.commit();
        // The owner claims with the generation of its own token and sends the new token on.
        WriteToken sent = partition.claim(first.token().generation()).orElseThrow();
        try (PartitionWriter3 next = partition.writer(sent)) {
            assertThrows(OwnershipChangedException.class, () -> first.insert(new Point3(2, 2, 2)));
            assertThrows(OwnershipChangedException.class, first::commit);
            long b = next.insert(new Point3(3, 3, 3));
            try (AcquiredSnapshot3 snapshot = publish(partition, next)) {
                assertEquals(Optional.of(new Point3(1, 1, 1)), snapshot.get(a));
                assertEquals(Optional.of(new Point3(3, 3, 3)), snapshot.get(b));
                assertEquals(2, snapshot.size());
            }
        }
        // The old handle names a token that is not current: its close does nothing.
        first.close();
        assertEquals(new Ownership(3, false), partition.ownership());
    }

    @Test
    void coordinatorTakesTheOwnershipAwayAndTheWritesNotCommittedAreLost() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        PartitionWriter3 slow = partition.writer(partition.claim(0).orElseThrow());
        long kept = slow.insert(new Point3(1, 1, 1));
        slow.commit();
        long lost = slow.insert(new Point3(2, 2, 2));
        // The coordinator does not have the token of the slow writer: it reads the generation.
        WriteToken taken = partition.claim(partition.ownership().generation()).orElseThrow();
        assertThrows(OwnershipChangedException.class, slow::commit);
        try (PartitionWriter3 next = partition.writer(taken);
                AcquiredSnapshot3 snapshot = publish(partition, next)) {
            assertEquals(Optional.of(new Point3(1, 1, 1)), snapshot.get(kept));
            assertEquals(Optional.empty(), snapshot.get(lost));
        }
    }

    @Test
    void closeAfterAnExceptionDiscardsTheWritesNotCommitted() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        long[] ids = new long[2];
        assertThrows(IllegalStateException.class, () -> {
            try (PartitionWriter3 writer = partition.writer(partition.claim(0).orElseThrow())) {
                ids[0] = writer.insert(new Point3(1, 1, 1));
                writer.commit();
                ids[1] = writer.insert(new Point3(2, 2, 2));
                throw new IllegalStateException("the writer fails before its commit");
            }
        });
        assertEquals(new Ownership(2, false), partition.ownership());
        try (PartitionWriter3 writer = partition.writer(partition.claim(2).orElseThrow());
                AcquiredSnapshot3 snapshot = publish(partition, writer)) {
            assertEquals(Optional.of(new Point3(1, 1, 1)), snapshot.get(ids[0]));
            assertEquals(Optional.empty(), snapshot.get(ids[1]));
        }
    }

    @Test
    void closeAfterTheDestructionOfThePartitionDoesNothing() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        PartitionWriter3 writer = partition.writer(partition.claim(0).orElseThrow());
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
    private AcquiredSnapshot3 publish(Partition3 partition) {
        return publish(partition, writer(partition));
    }

    /**
     * Commits the writes of the handle of a partition, moves the tick by one and acquires the
     * snapshot just published.
     */
    private AcquiredSnapshot3 publish(Partition3 partition, PartitionWriter3 writer) {
        writer.commit();
        space.setTick(space.tick() + 1);
        return partition.acquireLatest();
    }

    /**
     * Returns the writer handle of a partition for the tests that are not about the ownership:
     * the first call claims the new partition, the next calls give the same handle.
     */
    /** Returns a new partition name: "p" and a number, unique in the test. */
    private PartitionName nextName() {
        return new PartitionName("p" + names.incrementAndGet());
    }

    /** Returns the file of a save of the local node, in its save directory. */
    private static Path fileOf(Path directory, SavedPartition saved) {
        return directory.resolve("partitions").resolve(saved.name().value()).resolve(saved.tick() + ".bsave");
    }

    private PartitionWriter3 writer(Partition3 partition) {
        return writers.computeIfAbsent(partition, created -> created.writer(created.claim(0).orElseThrow()));
    }
}
