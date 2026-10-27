package badspace.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.api.AcquiredSnapshot3;
import badspace.api.Node3;
import badspace.api.Partition3;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter3;
import badspace.api.RemovalPolicy;
import badspace.api.Space3;
import badspace.api.SpaceConfig;
import badspace.common.geometry.Point3;
import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.UnknownPartitionException;
import badspace.service.partition.LocalPartitionNode3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The relation between spaces and nodes with the local nodes of the service: each space creates
 * its own nodes, moves only them, and accepts only its own nodes and partitions. Space3Test in
 * the api module checks the same calls with framework mocks.
 */
class SpaceNodes3EndToEndTest {

    private static final PartitionName WORLD = new PartitionName("world");
    private static final Point3 POSITION = new Point3(1, 1, 1);

    private final AtomicLong entityIds = new AtomicLong();
    private final AtomicLong partitionIds = new AtomicLong();
    // The spaces of a process share the generators.
    private final SpaceConfig config = SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet);
    private final Space3 space = new Space3(config);

    @Test
    void spacesWithTheirOwnNodesMoveIndependently() {
        Space3 other = new Space3(config);
        Partition3 moving = space.createPartition(space.createNode(LocalPartitionNode3::new), WORLD);
        Partition3 still = other.createPartition(other.createNode(LocalPartitionNode3::new), WORLD,
                PartitionConfig.defaults().withRetainedTicks(2).withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES));
        PartitionWriter3 writer = writer(still);
        long entityId = writer.insert(POSITION);
        writer.commit();
        other.setTick(1);
        // The removal is effective at the next move of the tick of its own space.
        writer.removePartition();

        space.setTick(10);
        assertEquals(10, moving.tick());
        assertEquals(1, other.tick());
        assertEquals(1, still.tick());
        // Publication, retention and removal of the other space did not move: tick 0 is in the
        // window, tick 1 is not published yet and the partition is not retired.
        try (AcquiredSnapshot3 snapshot = still.acquire(0)) {
            assertEquals(Optional.of(POSITION), snapshot.get(entityId));
        }
        assertReason(Reason.NOT_YET_PUBLISHED, () -> still.acquire(1));

        // The moves of its own space publish tick 1, the last one before the removal, and then
        // apply the removal.
        other.setTick(2);
        try (AcquiredSnapshot3 snapshot = still.acquire(1)) {
            assertEquals(1, snapshot.tick());
        }
        other.setTick(3);
        assertReason(Reason.AFTER_REMOVAL, () -> still.acquire(2));
        assertEquals(10, moving.tick());
    }

    @Test
    void nodeCreatedAfterSomeMovesFollowsTheSpaceAlsoWithoutPartitions() {
        space.setTick(3);
        LocalPartitionNode3 local = new LocalPartitionNode3();
        Node3 node = space.createNode(() -> local);
        assertEquals(3, local.tick());
        space.setTick(4);
        space.setTick(7);
        assertEquals(7, local.tick());

        Partition3 partition = space.createPartition(node, WORLD);
        assertEquals(7, partition.creationTick());
        space.setTick(8);
        try (AcquiredSnapshot3 snapshot = partition.acquireLatest()) {
            assertEquals(7, snapshot.tick());
        }
    }

    @Test
    void handleOfAnotherSpaceChangesNoPartitionOrFile(@TempDir Path directory) {
        Space3 other = new Space3(config);
        Node3 foreign = other.createNode(() -> new LocalPartitionNode3(directory));
        Partition3 partition = other.createPartition(foreign, WORLD);
        other.setTick(1);
        SavedPartition saved = partition.saveLatest();
        long partitionId = partitionIds.get();

        assertThrows(IllegalArgumentException.class, () -> space.createPartition(foreign, new PartitionName("north")));
        assertThrows(IllegalArgumentException.class, () -> space.loadPartition(foreign, PartitionConfig.defaults(), saved));
        assertThrows(IllegalArgumentException.class, () -> space.saves(foreign));
        assertThrows(IllegalArgumentException.class, () -> space.saves(foreign, WORLD));
        assertThrows(IllegalArgumentException.class, () -> space.deleteSaves(foreign, List.of(saved)));

        // No partition was created or loaded, and the save is still there.
        assertEquals(partitionId, partitionIds.get());
        assertTrue(Files.exists(fileOf(directory, saved)));
        assertEquals(List.of(saved), other.saves(foreign));
        other.createPartition(foreign, new PartitionName("north"));
    }

    @Test
    void snapshotSetWithAPartitionOfAnotherSpaceAcquiresNothing() {
        Space3 other = new Space3(config);
        Partition3 foreign = other.createPartition(other.createNode(LocalPartitionNode3::new), WORLD);
        Partition3 own = space.createPartition(space.createNode(LocalPartitionNode3::new), WORLD);
        space.setTick(1);
        other.setTick(1);

        assertThrows(IllegalArgumentException.class, () -> space.acquire(List.of(own, foreign), 0));
        assertThrows(IllegalArgumentException.class, () -> space.acquire(List.of(foreign, own), 0));

        // An acquisition would keep the removed partition alive: without one, the partition is
        // destroyed as soon as its last tick leaves the window.
        writer(own).removePartition();
        space.setTick(2);
        space.setTick(3);
        assertThrows(UnknownPartitionException.class, own::tick);
    }

    @Test
    void nodesWithTheirOwnDirectoriesKeepTheSavesOfTheSameNameApart(@TempDir Path eastDirectory,
            @TempDir Path westDirectory) {
        Node3 east = space.createNode(() -> new LocalPartitionNode3(eastDirectory));
        Node3 west = space.createNode(() -> new LocalPartitionNode3(westDirectory));
        // A new node gives its saves also before its first partition.
        assertEquals(List.of(), space.saves(east));

        PartitionConfig config = PartitionConfig.defaults().withRetainedTicks(3);
        Partition3 eastWorld = space.createPartition(east, WORLD, config);
        Partition3 westWorld = space.createPartition(west, WORLD, config);
        space.setTick(1);
        space.setTick(2);
        SavedPartition east0 = eastWorld.save(0);
        SavedPartition east1 = eastWorld.save(1);
        SavedPartition west1 = westWorld.save(1);
        assertEquals(List.of(east0, east1), space.saves(east));
        assertEquals(List.of(west1), space.saves(west, WORLD));

        // The same name and tick on the other node: the deletion stays on its node.
        space.deleteSaves(east, List.of(east1));
        assertEquals(List.of(east0), space.saves(east));
        assertEquals(List.of(west1), space.saves(west));
        assertFalse(Files.exists(fileOf(eastDirectory, east1)));
        assertTrue(Files.exists(fileOf(westDirectory, west1)));
    }

    private static void assertReason(Reason reason, Executable acquisition) {
        assertEquals(reason, assertThrows(SnapshotNotAvailableException.class, acquisition).reason());
    }

    private static Path fileOf(Path directory, SavedPartition saved) {
        return directory.resolve("partitions").resolve(saved.name().value()).resolve(saved.tick() + ".bsave");
    }

    private static PartitionWriter3 writer(Partition3 partition) {
        return partition.writer(partition.claim(0).orElseThrow());
    }
}
