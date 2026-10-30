package badspace.samples.saveandload;

import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import badspace.service.partition.LocalPartitionNode2;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Sample client: the software saves a partition, and a later run loads the save into a new
 * partition. A new node on the same save directory finds the saves by the name of the partition,
 * but it does not load them by itself: the software chooses which save to load. The loaded
 * partition has the same entity IDs, positions and metadata.
 * <p>
 * Run it with a directory as argument to keep the saves there; without an argument it uses a new
 * temporary directory. It does not delete the saves at the end.
 */
public final class SaveAndLoad {

    private static final PartitionName WORLD = partitionName("world");

    public static void main(String[] args) throws IOException {
        // The save directory must exist: the node does not create it.
        Path directory = args.length > 0
                ? Files.createDirectories(Paths.get(args[0]))
                : Files.createTempDirectory("badspace-saves");
        System.out.printf("Save directory: %s%n", directory);

        // First run: a writer inserts a ship with its health, and the software saves the snapshot.
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(() -> new LocalPartitionNode2(directory));
        Partition2 partition = space.createPartition(node, WORLD);
        long ship;
        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            ship = writer.insert(10, 20);
            writer.putMetadata(ship, "health", 75);
            writer.commit();
        }
        space.setTick(1);
        // The save is synchronous: it returns when the save is complete.
        SavedPartition saved = partition.saveLatest();
        System.out.printf("Saved %s at tick %d%n", saved.name().value(), saved.tick());

        // A new run, here in the same process: a new space at tick 0 creates a new node on the
        // same directory. The node has no partitions; the software finds the save among the saves
        // of the name. The directory can have other saves of the name, for example of earlier runs.
        Space2 restarted = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(1), new SnowflakeIdGenerator(1)));
        Node2 restartedNode = restarted.createNode(() -> new LocalPartitionNode2(directory));
        SavedPartition found = restarted.saves(restartedNode, WORLD).stream()
                .filter(saved::equals)
                .findFirst()
                .orElseThrow();
        // The index and the retention window come from the software, not from the save.
        Partition2 loaded = restarted.loadPartition(restartedNode, PartitionConfig.defaults(), found);
        System.out.printf("Loaded the save of tick %d into a new partition, created at tick %d of the new run%n",
                found.tick(), loaded.creationTick());

        // The load commits the content at the creation tick: the next move of the tick publishes it.
        restarted.setTick(loaded.creationTick() + 1);
        try (AcquiredSnapshot2 snapshot = loaded.acquireLatest()) {
            System.out.printf("Snapshot of tick %d: the ship is at %s with %s%n", snapshot.tick(),
                    snapshot.get(ship).orElseThrow(), snapshot.getMetadata(ship).orElseThrow());
        }
    }
}
