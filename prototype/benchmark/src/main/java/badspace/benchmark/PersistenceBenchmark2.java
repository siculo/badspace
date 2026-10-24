package badspace.benchmark;

import badspace.common.partition.IndexConfig;
import badspace.common.partition.RetentionWindow;
import badspace.common.partition.SavedPartition;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.partition.NodeCopies;
import badspace.service.persistence.PartitionCopy2;
import badspace.service.persistence.PartitionSave2;
import badspace.service.persistence.SaveDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Benchmarks of the persistence of a 2D partition with {@code size} entities and the
 * {@code metadata} of a {@link MetadataSet}, see {@link PersistenceWorkload}. Each one
 * measures one phase, never the difference of two benchmarks:
 * <ul>
 *   <li>{@link #copy}: the autonomous copy of the published snapshot, as the node makes it
 *       for a save: acquisition, copy and release;</li>
 *   <li>{@link #write}: the complete write of a copy to a new save file:
 *       {@link PartitionSave2#write}, with the encoding, the CRC, the write of the temporary
 *       file, its sync, the rename and the sync of the directory where the platform has it;</li>
 *   <li>{@link #load}: the complete {@code loadPartition} of a save into a new partition:
 *       the read, the checks, the rebuild of the storage and of the {@code index}, and the
 *       addition of the partition to the node.</li>
 * </ul>
 * The copy and the write use the linear scan, the reference index: they do not depend on it.
 * The preparation is outside the measure: the source partition and its snapshot for all the
 * benchmarks, the copy for the write, and the save file and the empty node for the load.
 * <p>
 * Each trial works in its own directory, in the directory of the run (see
 * {@link SaveDestination}), and deletes it at the end. The files do not grow: the write
 * deletes its file after each call, and the load drops its node, so a partition with the
 * same ID and name can be loaded again.
 * <p>
 * These benchmarks are not in the default run: a run chooses them with its include
 * pattern, for example {@code --include Persistence}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class PersistenceBenchmark2 {

    /** A node with the source partition and its published snapshot, in the directory of the trial. */
    @State(Scope.Thread)
    public abstract static class SourceState {

        @Param({"1000", "100000"})
        public int size;

        @Param({"NONE", "FIXED"})
        public MetadataSet metadata;

        Path directory;
        LocalPartitionNode2 node;

        @Setup(Level.Trial)
        public void setUpTrial() throws IOException {
            directory = SaveDestination.createTrialDirectory();
            try {
                node = PersistenceWorkload.publishedNode2(directory, size, metadata);
                afterSource();
            } catch (IOException | RuntimeException e) {
                SaveDestination.delete(directory);
                throw e;
            }
        }

        /** Called once for each trial, after the source partition is ready. */
        void afterSource() throws IOException {
        }

        @TearDown(Level.Trial)
        public void deleteDirectory() throws IOException {
            node = null;
            SaveDestination.delete(directory);
        }
    }

    @State(Scope.Thread)
    public static class CopyState extends SourceState {
    }

    /** A copy of the snapshot, and the directory of its partition, ready for a new save. */
    @State(Scope.Thread)
    public static class WriteState extends SourceState {

        SaveDirectory saveDirectory;
        PartitionCopy2 copy;
        Path file;

        @Override
        void afterSource() throws IOException {
            saveDirectory = new SaveDirectory(directory);
            copy = NodeCopies.copy(node, WorkloadState.PARTITION_ID, PersistenceWorkload.SNAPSHOT_TICK);
            file = saveDirectory.saveFile(WorkloadState.PARTITION_NAME, copy.tick());
            // The first write creates the directory of the partition, which then stays.
            new PartitionSave2(saveDirectory, WorkloadState.PARTITION_NAME, copy).write();
            deleteSave();
        }

        /** Deletes the file of the last write, so each write makes a new file. */
        @TearDown(Level.Invocation)
        public void deleteSave() throws IOException {
            Files.delete(file);
        }
    }

    /** A save of the source partition, and a node without partitions that can load it. */
    @State(Scope.Thread)
    public static class LoadState extends SourceState {

        /** Name of the index of the loaded partition, see {@link IndexNames}. */
        @Param({"LINEAR_SCAN", "UNIFORM_GRID_100"})
        public String index;

        IndexConfig indexConfig;
        SavedPartition saved;
        LocalPartitionNode2 loadNode;

        @Override
        void afterSource() {
            indexConfig = IndexNames.parse(index);
            saved = node.saveLatest(WorkloadState.PARTITION_ID);
            // The load needs only the file: the source partition would only use memory.
            node = null;
        }

        @Setup(Level.Invocation)
        public void createNode() {
            loadNode = new LocalPartitionNode2(directory);
        }

        /** Drops the node with the loaded partition, so the next load uses the same ID and name. */
        @TearDown(Level.Invocation)
        public void dropNode() {
            loadNode = null;
        }
    }

    @Benchmark
    public PartitionCopy2 copy(CopyState s) {
        return NodeCopies.copy(s.node, WorkloadState.PARTITION_ID, PersistenceWorkload.SNAPSHOT_TICK);
    }

    @Benchmark
    public Path write(WriteState s) throws IOException {
        return new PartitionSave2(s.saveDirectory, WorkloadState.PARTITION_NAME, s.copy).write();
    }

    @Benchmark
    public LocalPartitionNode2 load(LoadState s) {
        s.loadNode.loadPartition(WorkloadState.PARTITION_ID, s.saved, s.indexConfig, RetentionWindow.DEFAULT_RETAINED_TICKS);
        return s.loadNode;
    }
}
