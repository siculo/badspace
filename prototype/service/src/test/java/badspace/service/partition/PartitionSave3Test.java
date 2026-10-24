package badspace.service.partition;

import static badspace.common.partition.Entity3.entity;
import static badspace.common.partition.MetadataWrite.put;
import static badspace.service.partition.Names.nameOf;
import static badspace.service.partition.Owners.owner;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Point3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.persistence.PartitionCopy3;
import badspace.service.persistence.PartitionSave3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the saves of 3D snapshots that the node writes in its save directory: one file per
 * tick in the directory of the partition, with the IDs, the positions and the metadata of the
 * snapshot, written through a temporary file, the same bytes for the same tick, and a CRC32
 * that finds a corrupted file.
 */
class PartitionSave3Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);

    @TempDir
    private Path directory;

    private LocalPartitionNode3 node;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode3(directory);
        node.createPartition(P1, nameOf(P1), IndexConfig.uniformGrid(4));
    }

    @Test
    void saveReturnsTheReferenceOfThePartitionAndTheTick() {
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 5);
        node.setTick(1);
        node.setTick(2);
        assertEquals(new SavedPartition(nameOf(P2), 1), node.saveLatest(P2));
        assertEquals(new SavedPartition(nameOf(P2), 0), node.save(P2, 0));
    }

    @Test
    void saveWritesTheFileOfTheTickInTheDirectoryOfThePartition() throws IOException {
        node.setTick(1);
        node.saveLatest(P1);
        assertEquals(List.of(directory.resolve("partitions").resolve("p1").resolve("0.bsave")), filesOf(P1));
    }

    @Test
    void saveHasTheIdsPositionsAndMetadataOfTheSnapshot() throws IOException {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 2, 3), entity(2, 4, 5, 6)));
        node.updateMetadataAll(owner(node, P1), List.of(put(1, "version", 7), put(1, "owner", -3)));
        node.commit(owner(node, P1));
        node.setTick(1);
        PartitionCopy3 saved = PartitionSave3.read(fileOf(node.saveLatest(P1)), nameOf(P1));
        assertEquals(0, saved.tick());
        assertEquals(2, saved.size());
        assertEquals(Map.of(1L, new Point3(1, 2, 3), 2L, new Point3(4, 5, 6)), positionsById(saved));
        assertEquals(Map.of(1L, Map.of("version", 7L, "owner", -3L), 2L, Map.of()), metadataById(saved));
    }

    @Test
    void saveOfAnEmptySnapshotHasNoEntities() throws IOException {
        node.setTick(1);
        PartitionCopy3 saved = PartitionSave3.read(fileOf(node.saveLatest(P1)), nameOf(P1));
        assertEquals(0, saved.tick());
        assertEquals(0, saved.size());
    }

    @Test
    void saveAtATickChoosesThatSnapshot() throws IOException {
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 5);
        node.insertAll(owner(node, P2), List.of(entity(1, 1, 1, 1)));
        node.commit(owner(node, P2));
        node.setTick(1);
        node.insertAll(owner(node, P2), List.of(entity(2, 2, 2, 2)));
        node.commit(owner(node, P2));
        node.setTick(2);
        assertEquals(1, PartitionSave3.read(fileOf(node.save(P2, 0)), nameOf(P2)).size());
        assertEquals(2, PartitionSave3.read(fileOf(node.save(P2, 1)), nameOf(P2)).size());
        assertEquals(2, filesOf(P2).size());
    }

    @Test
    void savesOfTheSameTickWriteTheSameBytes() throws IOException {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 2, 3), entity(2, 4, 5, 6)));
        node.updateMetadataAll(owner(node, P1),
                List.of(put(1, "b", 1), put(1, "a", 2), put(2, "c", 3), put(2, "a", 4)));
        node.commit(owner(node, P1));
        node.setTick(1);
        byte[] first = Files.readAllBytes(fileOf(node.save(P1, 0)));
        byte[] second = Files.readAllBytes(fileOf(node.save(P1, 0)));
        assertArrayEquals(first, second);
        assertEquals(1, filesOf(P1).size());
    }

    @Test
    void savesOfTheSameTickAtTheSameTimeLeaveOneValidSave() throws Exception {
        for (int i = 0; i < 1000; i++) {
            node.insertAll(owner(node, P1), List.of(entity(i, i % 4, i / 4 % 4, i / 16 % 4)));
        }
        node.commit(owner(node, P1));
        node.setTick(1);
        int saves = 8;
        List<Callable<SavedPartition>> tasks = new ArrayList<>();
        for (int i = 0; i < saves; i++) {
            tasks.add(() -> node.save(P1, 0));
        }
        ExecutorService executor = Executors.newFixedThreadPool(saves);
        try {
            for (Future<SavedPartition> result : executor.invokeAll(tasks)) {
                result.get();
            }
        } finally {
            executor.shutdown();
        }
        List<Path> files = filesOf(P1);
        assertEquals(1, files.size());
        assertEquals(1000, PartitionSave3.read(files.get(0), nameOf(P1)).size());
    }

    @Test
    void saveFailsLikeAnAcquisitionWhenTheSnapshotIsNotAvailable() throws IOException {
        assertThrows(SnapshotNotAvailableException.class, () -> node.saveLatest(P1));
        assertThrows(SnapshotNotAvailableException.class, () -> node.save(P1, 0));
        assertEquals(List.of(), filesOf(P1));
    }

    @Test
    void saveOfAnUnknownPartitionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> node.saveLatest(new PartitionId(99)));
        assertThrows(IllegalArgumentException.class, () -> node.save(new PartitionId(99), 0));
    }

    @Test
    void savesListsTheSavesOfAllThePartitionsByNameAndThenByTick() {
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 5);
        for (long tick = 1; tick <= 11; tick++) {
            node.setTick(tick);
        }
        node.save(P2, 10);
        node.save(P2, 9);
        node.saveLatest(P1);
        assertEquals(List.of(new SavedPartition(nameOf(P1), 10), new SavedPartition(nameOf(P2), 9),
                new SavedPartition(nameOf(P2), 10)), node.saves());
    }

    @Test
    void savesOfANameAreSortedByTick() {
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 20);
        for (long tick = 1; tick <= 12; tick++) {
            node.setTick(tick);
        }
        // 10 comes before 9 in the order of the file names, not in the order of the ticks.
        node.save(P2, 10);
        node.save(P2, 9);
        node.save(P2, 11);
        assertEquals(List.of(new SavedPartition(nameOf(P2), 9), new SavedPartition(nameOf(P2), 10),
                new SavedPartition(nameOf(P2), 11)), node.saves(nameOf(P2)));
        assertEquals(List.of(), node.saves(nameOf(P1)));
    }

    @Test
    void savesStayForANewNodeOnTheSameDirectory() {
        node.setTick(1);
        SavedPartition saved = node.saveLatest(P1);
        LocalPartitionNode3 restarted = new LocalPartitionNode3(directory);
        assertEquals(List.of(saved), restarted.saves());
        assertEquals(List.of(saved), restarted.saves(nameOf(P1)));
    }

    @Test
    void savesSkipTheFilesAndDirectoriesThatAreNotSaves() throws IOException {
        node.setTick(1);
        SavedPartition saved = node.saveLatest(P1);
        Path partitionDirectory = fileOf(saved).getParent();
        for (String fileName : new String[] {"1.123.tmp", "007.bsave", "-1.bsave", "x.bsave", ".bsave",
                "99999999999999999999.bsave", "notes.txt"}) {
            Files.createFile(partitionDirectory.resolve(fileName));
        }
        Files.createDirectories(directory.resolve("partitions").resolve("Upper"));
        Files.createFile(directory.resolve("partitions").resolve("Upper").resolve("0.bsave"));
        assertEquals(List.of(saved), node.saves());
        assertEquals(List.of(saved), node.saves(nameOf(P1)));
    }

    @Test
    void deleteSavesRemovesTheFilesAndSkipsTheSavesThatAreNotThere() throws IOException {
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 5);
        node.setTick(1);
        SavedPartition first = node.saveLatest(P1);
        node.setTick(2);
        SavedPartition second = node.saveLatest(P1);
        SavedPartition other = node.save(P2, 1);
        node.deleteSaves(List.of(first, other, new SavedPartition(nameOf(P2), 7), first));
        assertEquals(List.of(second), node.saves());
        assertEquals(List.of(fileOf(second)), filesOf(P1));
        assertEquals(List.of(), filesOf(P2));
    }

    @Test
    void savesOfADestroyedPartitionStayAndCanBeDeleted() {
        node.setTick(1);
        SavedPartition saved = node.saveLatest(P1);
        node.dropPartition(owner(node, P1));
        // The removal is effective at tick 2, and the partition is destroyed when its last
        // snapshot, of tick 1, leaves the window.
        node.setTick(2);
        node.setTick(3);
        assertThrows(IllegalArgumentException.class, () -> node.saveLatest(P1));
        assertEquals(List.of(saved), node.saves());
        node.deleteSaves(List.of(saved));
        assertEquals(List.of(), node.saves());
    }

    @Test
    void nodeWithoutASaveDirectoryCannotSave() {
        LocalPartitionNode3 other = new LocalPartitionNode3();
        other.createPartition(P1, nameOf(P1), IndexConfig.linearScan());
        other.setTick(1);
        assertThrows(IllegalStateException.class, () -> other.saveLatest(P1));
        assertThrows(IllegalStateException.class, other::removeTemporaryFiles);
        assertThrows(IllegalStateException.class, other::saves);
        assertThrows(IllegalStateException.class, () -> other.saves(nameOf(P1)));
        assertThrows(IllegalStateException.class, () -> other.deleteSaves(List.of()));
    }

    @Test
    void nodeRejectsASaveDirectoryThatDoesNotExist() {
        assertThrows(IllegalArgumentException.class, () -> new LocalPartitionNode3(directory.resolve("missing")));
    }

    @Test
    void removeTemporaryFilesKeepsTheSaves() throws IOException {
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan());
        node.setTick(1);
        Path save1 = fileOf(node.saveLatest(P1));
        Path save2 = fileOf(node.saveLatest(P2));
        // Temporary files left by interrupted saves, as a crash would leave them.
        Path temporary1 = Files.createFile(save1.resolveSibling("1.123.tmp"));
        Path temporary2 = Files.createFile(save2.resolveSibling("0.456.tmp"));
        node.removeTemporaryFiles();
        assertFalse(Files.exists(temporary1));
        assertFalse(Files.exists(temporary2));
        assertEquals(List.of(save1), filesOf(P1));
        assertEquals(List.of(save2), filesOf(P2));
    }

    @Test
    void readRejectsACorruptedSave() throws IOException {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 2, 3)));
        node.commit(owner(node, P1));
        node.setTick(1);
        Path file = fileOf(node.saveLatest(P1));
        byte[] bytes = Files.readAllBytes(file);
        byte[] changed = bytes.clone();
        // A byte of the position of the entity: before it there are the key table (an int),
        // the pairs of the entity (an int) and the CRC32 (an int).
        changed[changed.length - 20] ^= 1;
        Files.write(file, changed);
        assertThrows(IOException.class, () -> PartitionSave3.read(file, nameOf(P1)));
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PartitionSave3.read(file, nameOf(P1)));
    }

    @Test
    void readRejectsTheSaveOfAnotherPartition() throws IOException {
        node.setTick(1);
        Path file = fileOf(node.saveLatest(P1));
        assertThrows(IOException.class, () -> PartitionSave3.read(file, nameOf(P2)));
    }

    @Test
    void namesThatLookAlikeOnAFileSystemHaveTheirOwnSaves() throws IOException {
        // Names that a file system could confuse: each one must keep its own file.
        List<PartitionName> names = List.of(new PartitionName("arena"), new PartitionName(".arena"),
                new PartitionName("a.rena"), new PartitionName("arena-"), new PartitionName("arena.save"),
                new PartitionName("arena..save"));
        for (int i = 0; i < names.size(); i++) {
            node.createPartition(new PartitionId(10 + i), names.get(i), IndexConfig.linearScan(), 5);
        }
        node.setTick(1);
        for (int i = 0; i < names.size(); i++) {
            node.saveLatest(new PartitionId(10 + i));
        }
        for (PartitionName name : names) {
            assertEquals(0, PartitionSave3.read(fileOf(new SavedPartition(name, 0)), name).size());
        }
        assertEquals(Set.copyOf(names), node.saves().stream().map(SavedPartition::name).collect(Collectors.toSet()));
    }

    /** Returns the file of a save in the save directory of the node. */
    private Path fileOf(SavedPartition saved) {
        return directory.resolve("partitions").resolve(saved.name().value()).resolve(saved.tick() + ".bsave");
    }

    /** Lists the files in the directory of a partition, in name order; empty if there is none. */
    private List<Path> filesOf(PartitionId partitionId) throws IOException {
        Path partitionDirectory = directory.resolve("partitions").resolve(nameOf(partitionId).value());
        if (!Files.isDirectory(partitionDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(partitionDirectory)) {
            return files.sorted().toList();
        }
    }

    /** Collects the positions of a copy by entity ID. */
    private static Map<Long, Point3> positionsById(PartitionCopy3 copy) {
        Map<Long, Point3> positions = new HashMap<>();
        for (int i = 0; i < copy.size(); i++) {
            positions.put(copy.entityId(i), copy.position(i));
        }
        return positions;
    }

    /** Collects the metadata of a copy by entity ID. */
    private static Map<Long, Map<String, Long>> metadataById(PartitionCopy3 copy) {
        Map<Long, Map<String, Long>> metadata = new HashMap<>();
        for (int i = 0; i < copy.size(); i++) {
            metadata.put(copy.entityId(i), copy.metadata(i));
        }
        return metadata;
    }
}
