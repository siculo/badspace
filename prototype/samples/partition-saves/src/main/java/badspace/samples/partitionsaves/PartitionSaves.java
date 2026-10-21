package badspace.samples.partitionsaves;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.MetadataWrite.put;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.SavedPartition;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.persistence.PartitionCopy2;
import badspace.service.persistence.PartitionSave2;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Sample client: the saves of the partitions of a node. Three partitions, each with its own
 * index, live on one node with a save directory. The writers insert entities with metadata,
 * the software saves the snapshot of each partition, then the writers change the entities in a
 * later tick and the software saves again. A save is a file in the save directory of the node,
 * one per partition and tick, and the call returns its reference: the partition ID and the tick.
 * <p>
 * At the end a new node starts on the same directory, as after a restart: the files are there,
 * but the node has no partitions. A save keeps the content of one partition at one tick, not the
 * state of the node: which partitions it had, their configuration, their tick and their owner.
 * Loading a save into a new partition is not in the prototype yet, so the sample reads the files
 * with the service class that writes them, only to show their content.
 * <p>
 * Run it with a directory as argument to keep the saves there; without an argument it uses a new
 * temporary directory. It does not delete the files at the end, so you can look at them.
 */
public final class PartitionSaves {

    private static final Box2 ALL = new Box2(point(-1000, -1000), point(1000, 1000));

    public static void main(String[] args) throws IOException {
        // The save directory must exist: the node does not create it.
        Path directory = args.length > 0
                ? Files.createDirectories(Paths.get(args[0]))
                : Files.createTempDirectory("badspace-saves");
        System.out.printf("Save directory: %s%n", directory);

        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        LocalPartitionNode2 node = new LocalPartitionNode2(directory);
        // The software gives the partitions its own names: the API does not show the partition IDs.
        Map<String, Partition2> partitions = new LinkedHashMap<>();
        partitions.put("north", space.createPartition(node,
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(10)).withRetainedTicks(3)));
        partitions.put("center", space.createPartition(node,
                PartitionConfig.defaults().withIndex(IndexConfig.gridQuadtree(16)).withRetainedTicks(3)));
        partitions.put("south", space.createPartition(node,
                PartitionConfig.defaults().withIndex(IndexConfig.linearScan()).withRetainedTicks(3)));

        Map<String, PartitionWriter2> writers = new LinkedHashMap<>();
        for (Map.Entry<String, Partition2> named : partitions.entrySet()) {
            Partition2 partition = named.getValue();
            writers.put(named.getKey(), partition.writer(partition.claim(0).orElseThrow()));
        }

        try {
            // Tick 0: each writer inserts three entities with a health value, and commits.
            Map<String, List<Long>> entityIds = new LinkedHashMap<>();
            int base = 0;
            for (Map.Entry<String, PartitionWriter2> named : writers.entrySet()) {
                PartitionWriter2 writer = named.getValue();
                List<Long> ids = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    long id = writer.insert(base + i * 10, base);
                    writer.putMetadata(id, "health", 100);
                    ids.add(id);
                }
                writer.commit();
                entityIds.put(named.getKey(), ids);
                base += 100;
            }
            space.setTick(1);
            System.out.println();
            System.out.println("Tick 0 committed, the space is at tick 1.");
            Map<String, SavedPartition> firstSaves = saveAll(partitions);

            // Tick 1: each writer moves its first entity, damages the second and removes the
            // third; the north writer also inserts a new entity.
            for (Map.Entry<String, PartitionWriter2> named : writers.entrySet()) {
                PartitionWriter2 writer = named.getValue();
                List<Long> ids = entityIds.get(named.getKey());
                writer.update(ids.get(0), point(-5, -5));
                writer.updateMetadataAll(List.of(put(ids.get(1), "health", 40)));
                writer.remove(ids.get(2));
            }
            long newcomer = writers.get("north").insert(7, 7);
            writers.get("north").putMetadata(newcomer, "health", 100);
            for (PartitionWriter2 writer : writers.values()) {
                writer.commit();
            }
            space.setTick(2);
            System.out.println();
            System.out.println("Tick 1 committed, the space is at tick 2.");
            Map<String, SavedPartition> secondSaves = saveAll(partitions);

            // The snapshot of tick 0 is still in the window: a save of a past tick writes the
            // same file again, with the same bytes.
            System.out.println();
            System.out.printf("Save of tick 0 of north again: %s%n", partitions.get("north").save(0));

            System.out.println();
            System.out.println("Files in the save directory:");
            showFiles(directory);

            System.out.println();
            System.out.println("Content of the saves, read from the files:");
            for (String name : partitions.keySet()) {
                showSave(directory, name, firstSaves.get(name));
                showSave(directory, name, secondSaves.get(name));
            }

            System.out.println();
            System.out.println("The live snapshot of tick 1 of north, for comparison:");
            try (AcquiredSnapshot2 snapshot = partitions.get("north").acquire(1)) {
                for (Entity2 entity : snapshot.findInRegion(ALL)) {
                    System.out.printf("  %s %s%n", entity, snapshot.getMetadata(entity.id()).orElseThrow());
                }
            }
        } finally {
            for (PartitionWriter2 writer : writers.values()) {
                writer.close();
            }
        }

        // A restart: a new space and a new node on the same directory. The node finds the
        // directory ready, but it has no partitions: a save is the content of a partition, not
        // the state of the node. The software must know which saves to use, for example from
        // the references it kept, and load them into new partitions, with the index and the
        // window it chooses (not in the prototype yet).
        Space2 restarted = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(1), new SnowflakeIdGenerator(1)));
        LocalPartitionNode2 newNode = new LocalPartitionNode2(directory);
        Partition2 fresh = restarted.createPartition(newNode);
        System.out.println();
        System.out.println("After a restart, with a new node on the same directory:");
        System.out.printf("  a new partition starts empty at tick %d; the files are still there:%n",
                fresh.creationTick());
        showFiles(directory);
        System.out.println("  Not in the saves: which partitions the node had, their index and window,");
        System.out.println("  their tick and their owner. The software keeps them, if it needs them.");
    }

    /** Saves the latest snapshot of every partition and prints the references. */
    private static Map<String, SavedPartition> saveAll(Map<String, Partition2> partitions) {
        Map<String, SavedPartition> saves = new LinkedHashMap<>();
        for (Map.Entry<String, Partition2> named : partitions.entrySet()) {
            // Synchronous: it returns when the file is written, while the writer could continue.
            SavedPartition saved = named.getValue().saveLatest();
            saves.put(named.getKey(), saved);
            System.out.printf("  saved %-6s -> %s%n", named.getKey(), saved);
        }
        return saves;
    }

    /** Prints the files under the save directory, with their size. */
    private static void showFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                System.out.printf("  %s (%d bytes)%n", directory.relativize(file), Files.size(file));
            }
        }
    }

    /**
     * Reads a save and prints its entities. The sample knows where the local node writes the
     * file of a save; the reference does not say it, and the API will read the saves only to
     * load them into a partition.
     */
    private static void showSave(Path directory, String name, SavedPartition saved) throws IOException {
        Path file = directory.resolve("partitions").resolve(Long.toString(saved.partitionId().value()))
                .resolve(saved.tick() + ".bsave");
        PartitionCopy2 copy = PartitionSave2.read(file, saved.partitionId());
        System.out.printf("  %s at tick %d, %d entities:%n", name, copy.tick(), copy.size());
        for (int i = 0; i < copy.size(); i++) {
            System.out.printf("    %d at %s %s%n", copy.entityId(i), copy.position(i), copy.metadata(i));
        }
    }
}
