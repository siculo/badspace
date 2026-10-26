package badspace.samples.periodicsaves;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.partition.LocalPartitionNode2;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sample client, like a simulation that saves its world while it runs. The main thread is the
 * writer: at each tick it moves an entity, commits and moves the tick. Every few ticks it asks a
 * second thread, the saver, to save the snapshot of the tick that just ended, and goes on without
 * waiting. The save is synchronous for the saver only.
 * <p>
 * A save needs the snapshot of its tick in the retention window: the window gives the saver some
 * ticks of time. A saver later than that cannot save the tick any more.
 * <p>
 * Run it with a directory as argument to keep the saves there; without an argument it uses a new
 * temporary directory.
 */
public final class PeriodicSaves {

    private static final int TICKS = 20;
    private static final int TICKS_PER_SAVE = 5;
    private static final int RETAINED_TICKS = 5;
    private static final long MILLIS_PER_TICK = 20;
    // The request that stops the saver: ticks are never negative.
    private static final long NO_MORE_SAVES = -1;

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
        // The save directory must exist: the node does not create it.
        Path directory = args.length > 0
                ? Files.createDirectories(Paths.get(args[0]))
                : Files.createTempDirectory("badspace-saves");
        System.out.printf("Save directory: %s%n", directory);

        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(directory), partitionName("simulation"),
                PartitionConfig.defaults().withRetainedTicks(RETAINED_TICKS));

        // The ticks to save, from the writer to the saver.
        BlockingQueue<Long> saveRequests = new LinkedBlockingQueue<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> saving = executor.submit(() -> {
                for (long tick = saveRequests.take(); tick != NO_MORE_SAVES; tick = saveRequests.take()) {
                    try {
                        SavedPartition saved = partition.save(tick);
                        System.out.printf("  saver: saved tick %d while the space is at tick %d%n", saved.tick(),
                                space.tick());
                    } catch (SnapshotNotAvailableException e) {
                        System.out.printf("  saver: tick %d skipped, %s%n", tick, e.reason());
                    }
                }
                return null;
            });

            try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
                long ship = writer.insert(0, 0);
                for (long tick = 0; tick < TICKS; tick++) {
                    writer.update(ship, point(tick, 0));
                    writer.commit();
                    space.setTick(tick + 1);
                    // The snapshot of tick is published now: the saver can save it.
                    if ((tick + 1) % TICKS_PER_SAVE == 0) {
                        saveRequests.put(tick);
                        System.out.printf("writer: asked for the save of tick %d%n", tick);
                    }
                    Thread.sleep(MILLIS_PER_TICK);
                }
            } finally {
                // Stops the saver also if the writer fails, so the executor can end.
                saveRequests.put(NO_MORE_SAVES);
            }
            // Throws if the saver failed.
            saving.get();
        }
        System.out.printf("Saves: %s%n", partition.saves());
    }
}
