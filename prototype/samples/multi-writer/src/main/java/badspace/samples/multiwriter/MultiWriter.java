package badspace.samples.multiwriter;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.service.partition.LocalPartitionNode2;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Sample client, like the loop of a game server: two partitions on one node, each with its writer
 * on its own thread. The writers do not share state and do not wait for each other while they
 * write and commit. At the end of each tick they meet at a barrier: when both have committed, the
 * barrier moves the tick of the space, which publishes the snapshots of both partitions.
 */
public final class MultiWriter {

    private static final int TICKS = 3;
    // A writer that fails never reaches the barrier: the other one stops waiting after this time.
    private static final long BARRIER_TIMEOUT_SECONDS = 5;

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        LocalPartitionNode2 node = new LocalPartitionNode2();
        Partition2 west = space.createPartition(node, partitionName("west"));
        Partition2 east = space.createPartition(node, partitionName("east"));

        // The barrier action runs on the thread of the last writer that arrives, after both commits.
        CyclicBarrier endOfTick = new CyclicBarrier(2, () -> {
            System.out.printf("Both writers committed tick %d: the space moves to tick %d%n", space.tick(),
                    space.tick() + 1);
            space.setTick(space.tick() + 1);
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> westWriting = executor.submit(write(west, 0, endOfTick));
            Future<?> eastWriting = executor.submit(write(east, 1000, endOfTick));
            // Throw if a writer failed.
            westWriting.get();
            eastWriting.get();
        }

        try (AcquiredSnapshot2 westSnapshot = west.acquireLatest();
                AcquiredSnapshot2 eastSnapshot = east.acquireLatest()) {
            System.out.printf("Snapshots of tick %d: west %s, east %s%n", westSnapshot.tick(),
                    westSnapshot.findNearest(point(0, 0), 1), eastSnapshot.findNearest(point(1000, 0), 1));
        }
    }

    /**
     * Returns the work of the writer of one partition: it inserts one entity at x = startX, then
     * at each tick moves it by one step, commits and waits at the barrier for the other writer.
     */
    private static Callable<Void> write(Partition2 partition, double startX, CyclicBarrier endOfTick) {
        return () -> {
            try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
                long entityId = writer.insert(startX, 0);
                for (int step = 1; step <= TICKS; step++) {
                    writer.update(entityId, point(startX + step, 0));
                    writer.commit();
                    System.out.printf("  %s committed tick %d%n", partition.name().value(), partition.tick());
                    endOfTick.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            }
            return null;
        };
    }
}
