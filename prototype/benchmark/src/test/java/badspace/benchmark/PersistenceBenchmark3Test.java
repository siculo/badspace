package badspace.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.Entity3;
import badspace.common.partition.MetadataWrite;
import badspace.service.partition.LocalPartitionNode3;
import badspace.service.persistence.PartitionCopy3;
import badspace.service.persistence.PartitionSave3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that the 3D persistence benchmarks copy, write and load the content of the source
 * partition, that they can be called many times without conflicts or files left behind,
 * and that each trial deletes only its own directory.
 */
class PersistenceBenchmark3Test {

    private static final int SIZE = 200;

    private final PersistenceBenchmark3 benchmark = new PersistenceBenchmark3();

    @TempDir
    private Path runDirectory;

    @BeforeEach
    void setRunDirectory() {
        System.setProperty(SaveDestination.PROPERTY, runDirectory.toString());
    }

    @AfterEach
    void clearRunDirectory() {
        System.clearProperty(SaveDestination.PROPERTY);
    }

    private static <S extends PersistenceBenchmark3.SourceState> S setUp(S state, MetadataSet metadata) throws Exception {
        state.size = SIZE;
        state.metadata = metadata;
        state.setUpTrial();
        return state;
    }

    /** The metadata of each entity of the workload, by ID. */
    private static Map<Long, Map<String, Long>> expectedMetadata(MetadataSet metadata) {
        Map<Long, Map<String, Long>> byEntity = new HashMap<>();
        for (int id = 1; id <= SIZE; id++) {
            byEntity.put((long) id, new HashMap<>());
        }
        for (MetadataWrite write : PersistenceWorkload.metadata(metadata, SIZE)) {
            byEntity.get(write.entityId()).put(write.key(), ((MetadataWrite.Put) write).value());
        }
        return byEntity;
    }

    private static void assertSameContent(PartitionCopy3 copy, MetadataSet metadata) {
        Map<Long, Map<String, Long>> expectedMetadata = expectedMetadata(metadata);
        List<Entity3> entities = new ArrayList<>();
        for (int i = 0; i < copy.size(); i++) {
            entities.add(new Entity3(copy.entityId(i), copy.position(i)));
            assertEquals(expectedMetadata.get(copy.entityId(i)), copy.metadata(i));
        }
        entities.sort((a, b) -> Long.compare(a.id(), b.id()));
        assertEquals(PersistenceWorkload.entities3(SIZE), entities);
        assertEquals(PersistenceWorkload.SNAPSHOT_TICK, copy.tick());
    }

    private static List<Path> filesIn(Path directory) throws Exception {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void sourceHasTheEntitiesAndTheMetadataOfTheWorkload() throws Exception {
        PersistenceBenchmark3.CopyState s = setUp(new PersistenceBenchmark3.CopyState(), MetadataSet.FIXED);
        try {
            Acquisition acquisition = s.node.acquire(WorkloadState.PARTITION_ID, PersistenceWorkload.SNAPSHOT_TICK,
                    AcquireOptions.defaultTimeout());
            try {
                assertEquals(SIZE, s.node.size(acquisition));
                ApplicationMetadata first = s.node.getMetadataAll(acquisition, new long[] {1}).getFirst();
                assertEquals(MetadataSet.KEYS, first.values().keySet().stream().sorted().toList());
            } finally {
                s.node.release(acquisition);
            }
        } finally {
            s.deleteDirectory();
        }
    }

    @Test
    void copyHasTheIdsPositionsAndMetadataOfTheSnapshot() throws Exception {
        for (MetadataSet metadata : MetadataSet.values()) {
            PersistenceBenchmark3.CopyState s = setUp(new PersistenceBenchmark3.CopyState(), metadata);
            try {
                assertSameContent(benchmark.copy(s), metadata);
                // The copy releases its acquisition: it can run again on the same snapshot.
                assertSameContent(benchmark.copy(s), metadata);
            } finally {
                s.deleteDirectory();
            }
        }
    }

    @Test
    void eachWriteMakesANewSaveWithTheContent() throws Exception {
        PersistenceBenchmark3.WriteState s = setUp(new PersistenceBenchmark3.WriteState(), MetadataSet.FIXED);
        try {
            assertFalse(Files.exists(s.file));
            for (int i = 0; i < 3; i++) {
                Path file = benchmark.write(s);
                assertEquals(s.file, file);
                assertSameContent(PartitionSave3.read(file, WorkloadState.PARTITION_NAME), MetadataSet.FIXED);
                s.deleteSave();
                assertEquals(List.of(), filesIn(s.directory));
            }
        } finally {
            s.deleteDirectory();
        }
    }

    @Test
    void eachLoadGivesTheContentOfTheSaveWithTheIndex() throws Exception {
        PersistenceBenchmark3.LoadState s = new PersistenceBenchmark3.LoadState();
        s.index = "UNIFORM_GRID_100";
        setUp(s, MetadataSet.FIXED);
        try {
            assertEquals(null, s.node);
            for (int i = 0; i < 3; i++) {
                s.createNode();
                LocalPartitionNode3 node = benchmark.load(s);
                node.setTick(node.tick() + 1);
                Acquisition acquisition = node.acquireLatest(WorkloadState.PARTITION_ID, AcquireOptions.defaultTimeout());
                try {
                    long[] ids = PersistenceWorkload.entities3(SIZE).stream().mapToLong(Entity3::id).toArray();
                    assertEquals(PersistenceWorkload.entities3(SIZE), node.getAll(acquisition, ids));
                    Map<Long, Map<String, Long>> expected = expectedMetadata(MetadataSet.FIXED);
                    for (ApplicationMetadata metadata : node.getMetadataAll(acquisition, ids)) {
                        assertEquals(expected.get(metadata.entityId()), metadata.values());
                    }
                } finally {
                    node.release(acquisition);
                }
                s.dropNode();
            }
            // The loads only read the save.
            assertEquals(1, filesIn(s.directory).size());
        } finally {
            s.deleteDirectory();
        }
    }

    @Test
    void trialDeletesOnlyItsOwnDirectory() throws Exception {
        Path other = Files.writeString(runDirectory.resolve("other.txt"), "not of the trial");
        PersistenceBenchmark3.LoadState s = new PersistenceBenchmark3.LoadState();
        s.index = "LINEAR_SCAN";
        setUp(s, MetadataSet.NONE);
        assertTrue(s.directory.startsWith(runDirectory));
        assertEquals(1, filesIn(s.directory).size());

        s.deleteDirectory();
        assertFalse(Files.exists(s.directory));
        assertEquals(List.of(other), filesIn(runDirectory));
    }

    @Test
    void trialFailsWithoutTheDirectoryOfTheRun() {
        System.clearProperty(SaveDestination.PROPERTY);
        assertThrows(IllegalStateException.class,
                () -> setUp(new PersistenceBenchmark3.CopyState(), MetadataSet.NONE));
    }
}
