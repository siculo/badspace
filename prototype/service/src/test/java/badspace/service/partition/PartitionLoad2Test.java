package badspace.service.partition;

import static badspace.common.partition.Entity2.entity;
import static badspace.common.partition.MetadataWrite.put;
import static badspace.service.partition.Names.nameOf;
import static badspace.service.partition.Owners.owner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.WriteToken;
import badspace.service.index.SpatialIndexes;
import badspace.service.partition.snapshot.MonotonicClock;
import badspace.service.persistence.NodeSaves;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the load of a 2D save into a new partition: the content of the save with the same
 * IDs, positions and metadata, confirmed at the creation tick, without an owner, with the
 * index and the window of the load; and the loads that fail and create no partition.
 */
class PartitionLoad2Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);
    private static final PartitionId LOADED = new PartitionId(10);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    private Path directory;

    private LocalPartitionNode2 node;
    // A node on the same save directory, as after a restart: the name of P1 is free on it.
    private LocalPartitionNode2 restarted;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode2(directory);
        node.createPartition(P1, nameOf(P1), IndexConfig.linearScan());
        restarted = new LocalPartitionNode2(directory);
    }

    @Test
    void loadedPartitionHasTheEntitiesOfTheSave() {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2), entity(2, -3, 4)));
        restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5);
        restarted.setTick(1);
        Acquisition acquisition = restarted.acquireLatest(LOADED, AcquireOptions.defaultTimeout());
        assertEquals(List.of(entity(1, 1, 2), entity(2, -3, 4)), restarted.getAll(acquisition, new long[] {1, 2}));
        assertEquals(List.of(new ApplicationMetadata(1, Map.of("version", 7L, "owner", -3L)),
                new ApplicationMetadata(2, Map.of())),
                restarted.getMetadataAll(acquisition, new long[] {1, 2}));
    }

    @Test
    void loadedPartitionHasTheNameOfTheSave() throws IOException {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2)));
        restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5);
        restarted.setTick(1);
        assertEquals(new SavedPartition(nameOf(P1), 0), restarted.saveLatest(LOADED));
    }

    @Test
    void contentIsConfirmedAtTheCreationTickOfTheLoadedPartition() {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2)));
        restarted.setTick(7);
        restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5);
        assertEquals(7, restarted.creationTick(LOADED));
        assertReason(Reason.NOT_YET_PUBLISHED,
                () -> restarted.acquireLatest(LOADED, AcquireOptions.defaultTimeout()));
        restarted.setTick(8);
        assertEquals(1, restarted.size(restarted.acquire(LOADED, 7, AcquireOptions.defaultTimeout())));
        assertReason(Reason.BEFORE_CREATION, () -> restarted.acquire(LOADED, 6, AcquireOptions.defaultTimeout()));
    }

    @Test
    void loadedPartitionHasNoOwnerAndTheWriterClaimsIt() {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2), entity(2, -3, 4)));
        restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5);
        assertEquals(Ownership.INITIAL, restarted.ownership(LOADED));
        WriteToken token = restarted.claim(LOADED, 0).orElseThrow();
        restarted.updateAll(token, List.of(entity(1, 5, 5)));
        restarted.removeAll(token, new long[] {2});
        restarted.commit(token);
        restarted.setTick(1);
        Acquisition acquisition = restarted.acquireLatest(LOADED, AcquireOptions.defaultTimeout());
        assertEquals(List.of(entity(1, 5, 5)), restarted.getAll(acquisition, new long[] {1}));
        assertEquals(1, restarted.size(acquisition));
        assertEquals(List.of(new ApplicationMetadata(1, Map.of("version", 7L, "owner", -3L))),
                restarted.getMetadataAll(acquisition, new long[] {1}));
    }

    @Test
    void indexOfTheLoadIsRebuiltFromTheEntities() {
        List<Entity2> entities = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            entities.add(entity(i, i % 20, i / 20));
        }
        SavedPartition saved = saveOf(entities);
        restarted.loadPartition(LOADED, saved, IndexConfig.gridQuadtree(2, 4), 5);
        restarted.setTick(1);
        Acquisition acquisition = restarted.acquireLatest(LOADED, AcquireOptions.defaultTimeout());
        Box2 box = new Box2(new Point2(2, 3), new Point2(5, 6));
        HashSet<Entity2> expected = new HashSet<>();
        for (Entity2 e : entities) {
            if (e.position().x() >= 2 && e.position().x() <= 5 && e.position().y() >= 3 && e.position().y() <= 6) {
                expected.add(e);
            }
        }
        assertEquals(16, expected.size());
        assertEquals(expected, new HashSet<>(restarted.findInRegion(acquisition, box)));
        assertEquals(List.of(entity(0, 0, 0), entity(1, 1, 0), entity(20, 0, 1)),
                restarted.findNearest(acquisition, new Point2(0, 0), 3));
    }

    @Test
    void retentionWindowComesFromTheLoad() {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2)));
        restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 2);
        restarted.setTick(1);
        restarted.setTick(2);
        assertEquals(1, restarted.size(restarted.acquire(LOADED, 0, AcquireOptions.defaultTimeout())));
        restarted.setTick(3);
        assertReason(Reason.OUT_OF_RETENTION, () -> restarted.acquire(LOADED, 0, AcquireOptions.defaultTimeout()));
    }

    @Test
    void loadOfAnEmptySaveMakesAnEmptyPartition() {
        SavedPartition saved = saveOf(List.of());
        restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5);
        restarted.setTick(1);
        assertEquals(0, restarted.size(restarted.acquireLatest(LOADED, AcquireOptions.defaultTimeout())));
    }

    @Test
    void positionOutsideTheLimitsOfTheIndexFailsAndCreatesNoPartition() {
        SavedPartition saved = saveOf(List.of(entity(1, 0.5, 0), entity(2, 100, 0)));
        // A cell of 2^-30 gives limits of 1 on each side of the origin.
        assertThrows(IllegalArgumentException.class,
                () -> restarted.loadPartition(LOADED, saved, IndexConfig.uniformGrid(0x1p-30), 5));
        assertThrows(IllegalArgumentException.class, () -> restarted.ownership(LOADED));
        // The name and the ID are still free.
        restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5);
    }

    @Test
    void loadOfASaveThatIsNotThereFailsAndCreatesNoPartition() {
        assertThrows(UncheckedIOException.class,
                () -> restarted.loadPartition(LOADED, new SavedPartition(nameOf(P1), 3), IndexConfig.linearScan(), 5));
        assertThrows(IllegalArgumentException.class, () -> restarted.ownership(LOADED));
    }

    @Test
    void loadOfACorruptedSaveFailsAndCreatesNoPartition() throws IOException {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2)));
        Path file = directory.resolve("partitions").resolve(saved.name().value()).resolve(saved.tick() + ".bsave");
        byte[] bytes = Files.readAllBytes(file);
        // A byte before the CRC32, which then does not match.
        bytes[bytes.length - 20] ^= 1;
        Files.write(file, bytes);
        assertThrows(UncheckedIOException.class,
                () -> restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5));
        assertThrows(IllegalArgumentException.class, () -> restarted.ownership(LOADED));
    }

    @Test
    void loadOfA3DSaveFailsOnA2DNode() {
        LocalPartitionNode3 node3 = new LocalPartitionNode3(directory);
        node3.createPartition(P2, nameOf(P2), IndexConfig.linearScan());
        node3.setTick(1);
        SavedPartition saved = node3.saveLatest(P2);
        assertThrows(UncheckedIOException.class,
                () -> restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5));
    }

    @Test
    void loadRejectsANameOrAnIdInUse() {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2)));
        // P1 still has the name of the save on its node.
        assertThrows(IllegalArgumentException.class, () -> node.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5));
        restarted.createPartition(LOADED, nameOf(LOADED));
        assertThrows(IllegalArgumentException.class,
                () -> restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5));
    }

    @Test
    void loadRejectsLessThanOneRetainedTick() {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> restarted.loadPartition(LOADED, saved, IndexConfig.linearScan(), 0));
    }

    @Test
    void nodeWithoutASaveDirectoryCannotLoad() {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2)));
        LocalPartitionNode2 other = new LocalPartitionNode2();
        assertThrows(IllegalStateException.class, () -> other.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5));
    }

    @Test
    void moveOfTheTickDoesNotWaitForALoadAndTheLoadCreatesThePartitionAtTheNewTick() throws Exception {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2)));
        Gate gate = new Gate();
        LocalPartitionNode2 gated = nodeWithGatedCommit(gate);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> load = executor.submit(() -> gated.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5));
            // The load stops in the commit of its content, after the read and the rebuild of the index.
            gate.awaitEntered();
            gated.setTick(3);
            gate.open();
            load.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(3, gated.creationTick(LOADED));
        gated.setTick(4);
        assertEquals(1, gated.size(gated.acquire(LOADED, 3, AcquireOptions.defaultTimeout())));
    }

    @Test
    void loadFailsIfTheNameIsTakenDuringTheLoad() throws Exception {
        SavedPartition saved = saveOf(List.of(entity(1, 1, 2)));
        Gate gate = new Gate();
        LocalPartitionNode2 gated = nodeWithGatedCommit(gate);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> load = executor.submit(() -> gated.loadPartition(LOADED, saved, IndexConfig.linearScan(), 5));
            gate.awaitEntered();
            gated.createPartition(P2, saved.name());
            gate.open();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> load.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        } finally {
            executor.shutdownNow();
        }
        assertThrows(IllegalArgumentException.class, () -> gated.ownership(LOADED));
        assertEquals(Ownership.INITIAL, gated.ownership(P2));
    }

    @Test
    void creationTickCannotChangeAfterAMoveOfTheTick() {
        PartitionStorage2 storage = new PartitionStorage2();
        storage.setCreationTick(5);
        assertEquals(5, storage.creationTick());
        storage.setTick(6);
        assertThrows(IllegalStateException.class, () -> storage.setCreationTick(7));
    }

    /**
     * Writes the entities into P1, with metadata for entity 1 if it is there, commits them at
     * tick 0 and saves the snapshot of tick 0.
     */
    private SavedPartition saveOf(List<Entity2> entities) {
        if (!entities.isEmpty()) {
            node.insertAll(owner(node, P1), entities);
        }
        if (entities.stream().anyMatch(e -> e.id() == 1)) {
            node.updateMetadataAll(owner(node, P1), List.of(put(1, "version", 7), put(1, "owner", -3)));
        }
        node.commit(owner(node, P1));
        node.setTick(1);
        return node.saveLatest(P1);
    }

    /**
     * Returns a node on the save directory whose indices stop their commits at the gate: a load
     * stops after the read of the save and the rebuild of the index, before it adds the partition.
     */
    private LocalPartitionNode2 nodeWithGatedCommit(Gate gate) {
        return new LocalPartitionNode2(MonotonicClock.SYSTEM, (index, retainedTicks, clock, tick) ->
                new PartitionStorage2(index.limits(), slots -> {
                    GatedIndex2 gatedIndex = new GatedIndex2(SpatialIndexes.create(index, slots));
                    gatedIndex.commitGate = gate;
                    return gatedIndex;
                }, retainedTicks, clock, tick), new NodeSaves(directory));
    }

    private static void assertReason(Reason reason, Executable executable) {
        assertEquals(reason, assertThrows(SnapshotNotAvailableException.class, executable).reason());
    }
}
