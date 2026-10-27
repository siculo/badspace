package badspace.samples.savemanagement;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.SavedPartition;
import badspace.service.partition.LocalPartitionNode2;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Sample client: the software saves the snapshots of past ticks while they are in the retention
 * window, lists the saves and deletes the older ones. The node has no rule on which saves to
 * keep: the software chooses. All the calls are synchronous.
 * <p>
 * Run it with a directory as argument to keep the saves there; without an argument it uses a new
 * temporary directory.
 */
public final class SaveManagement {

    private static final int KEPT_SAVES = 1;

    public static void main(String[] args) throws IOException {
        // The save directory must exist: the node does not create it.
        Path directory = args.length > 0
                ? Files.createDirectories(Paths.get(args[0]))
                : Files.createTempDirectory("badspace-saves");
        System.out.printf("Save directory: %s%n", directory);

        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(() -> new LocalPartitionNode2(directory));
        Partition2 partition = space.createPartition(node, partitionName("archive"),
                PartitionConfig.defaults().withRetainedTicks(3));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            long ship = writer.insert(0, 0);
            for (long tick = 0; tick <= 3; tick++) {
                writer.update(ship, point(tick, 0));
                writer.commit();
                space.setTick(tick + 1);
            }
        }

        // At tick 4 the window has the ticks from 1 to 3: each of them can be saved.
        for (long tick = 1; tick <= 3; tick++) {
            partition.save(tick);
        }
        List<SavedPartition> saves = partition.saves();
        System.out.printf("Saves: %s%n", saves);

        // The saves are sorted by tick: the software keeps the latest ones and deletes the others.
        space.deleteSaves(node, saves.subList(0, saves.size() - KEPT_SAVES));
        System.out.printf("Saves after the deletion: %s%n", partition.saves());
    }
}
