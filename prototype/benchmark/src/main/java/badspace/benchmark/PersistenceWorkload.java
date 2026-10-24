package badspace.benchmark;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Point3;
import badspace.common.partition.Entity2;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.Ownership;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.partition.LocalPartitionNode3;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The partitions of the persistence benchmarks, the same for all their measures: the IDs go
 * from 1 to the number of entities, the positions are uniform in a square (2D) or a cube (3D)
 * with side {@link Distribution#WORLD_SIZE}, and the metadata are a {@link MetadataSet}. All
 * come from fixed seeds, so two runs save the same bytes. The 2D and 3D positions come from
 * the same sequence: x and y are the same, 3D adds z.
 * <p>
 * The source partition uses the linear scan, the reference index: the copy and the save
 * do not depend on the index.
 */
final class PersistenceWorkload {

    /** The tick of the published snapshot of a source partition. */
    static final long SNAPSHOT_TICK = 0;

    private static final long METADATA_SEED = WorkloadState.SEED + 2;

    private PersistenceWorkload() {
    }

    static List<Entity2> entities2(int size) {
        SplittableRandom random = new SplittableRandom(WorkloadState.SEED);
        List<Entity2> entities = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entities.add(new Entity2(i + 1, new Point2(coordinate(random), coordinate(random))));
        }
        return List.copyOf(entities);
    }

    static List<Entity3> entities3(int size) {
        SplittableRandom random = new SplittableRandom(WorkloadState.SEED);
        List<Entity3> entities = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entities.add(new Entity3(i + 1, new Point3(coordinate(random), coordinate(random), coordinate(random))));
        }
        return List.copyOf(entities);
    }

    /** Returns the writes that give the metadata of the set to the entities with IDs from 1 to size. */
    static List<MetadataWrite> metadata(MetadataSet metadata, int size) {
        SplittableRandom random = new SplittableRandom(METADATA_SEED);
        List<MetadataWrite> writes = new ArrayList<>(size * metadata.keys().size());
        for (int i = 0; i < size; i++) {
            for (String key : metadata.keys()) {
                writes.add(new MetadataWrite.Put(i + 1, key, random.nextLong()));
            }
        }
        return writes;
    }

    /**
     * Returns a node that saves in the directory, with a partition that has the entities and
     * the metadata, and that published their snapshot at {@link #SNAPSHOT_TICK}.
     */
    static LocalPartitionNode2 publishedNode2(Path saveDirectory, int size, MetadataSet metadata) {
        LocalPartitionNode2 node = new LocalPartitionNode2(saveDirectory);
        node.createPartition(WorkloadState.PARTITION_ID, WorkloadState.PARTITION_NAME, IndexConfig.linearScan());
        node.claim(WorkloadState.PARTITION_ID, Ownership.INITIAL.generation()).orElseThrow();
        node.insertAll(WorkloadState.WRITE_TOKEN, entities2(size));
        node.updateMetadataAll(WorkloadState.WRITE_TOKEN, metadata(metadata, size));
        // The commit makes the snapshot of the tick; the move of the tick publishes it.
        node.commit(WorkloadState.WRITE_TOKEN);
        node.setTick(SNAPSHOT_TICK + 1);
        return node;
    }

    /** Returns a 3D node like {@link #publishedNode2}. */
    static LocalPartitionNode3 publishedNode3(Path saveDirectory, int size, MetadataSet metadata) {
        LocalPartitionNode3 node = new LocalPartitionNode3(saveDirectory);
        node.createPartition(WorkloadState.PARTITION_ID, WorkloadState.PARTITION_NAME, IndexConfig.linearScan());
        node.claim(WorkloadState.PARTITION_ID, Ownership.INITIAL.generation()).orElseThrow();
        node.insertAll(WorkloadState.WRITE_TOKEN, entities3(size));
        node.updateMetadataAll(WorkloadState.WRITE_TOKEN, metadata(metadata, size));
        node.commit(WorkloadState.WRITE_TOKEN);
        node.setTick(SNAPSHOT_TICK + 1);
        return node;
    }

    private static double coordinate(SplittableRandom random) {
        return random.nextDouble(Distribution.WORLD_SIZE);
    }
}
