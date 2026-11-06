package badspace.samples.writerpertick;

import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Point2;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.partition.PartitionNodeConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sample client: the main thread is a coordinator that gives the partition to a new writer in
 * each tick. The writer reads the latest snapshot, writes, commits as its last operation of the
 * tick and releases the ownership. In tick 2 the writer gets stuck after its commit, before the
 * release: the coordinator takes the ownership with a claim and gives it to a new writer, which
 * reads the tick of the last commit and sees that tick 2 is already committed, so it writes
 * nothing. Without that check it would move the ship twice in the same tick.
 */
public final class WriterPerTick {

    private static final long WRITER_TIMEOUT_MILLIS = 500;
    private static final long STUCK_TICK = 2;

    public static void main(String[] args) throws InterruptedException, ExecutionException, TimeoutException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(() -> new LocalPartitionNode2(PartitionNodeConfig.of()));
        Partition2 partition = space.createPartition(node, partitionName("world"));

        // Tick 0: the ship starts at (0,0).
        long ship;
        try (PartitionWriter2 first = partition.writer(partition.claim(0).orElseThrow())) {
            ship = first.insert(0, 0);
            first.commit();
        }
        space.setTick(1);

        CountDownLatch wakeUp = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newCachedThreadPool()) {
            Future<?> stuck = null;
            try {
                for (long tick = 1; tick <= 3; tick++) {
                    // The partition is free: the coordinator claims it for the writer of the tick.
                    WriteToken token = partition.claim(partition.ownership().generation()).orElseThrow();
                    CountDownLatch stuckAfterCommit = tick == STUCK_TICK ? wakeUp : null;
                    String name = "Writer of tick " + tick;
                    Future<?> writing = executor.submit(() -> writeTick(name, partition, token, ship, stuckAfterCommit));
                    try {
                        writing.get(WRITER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        // The writer does not end: the coordinator takes the partition away from
                        // it and gives it to a new writer.
                        stuck = writing;
                        WriteToken next = partition.claim(partition.ownership().generation()).orElseThrow();
                        System.out.printf("Coordinator: the writer of tick %d does not end, the ownership goes to %s%n",
                                tick, next);
                        executor.submit(() -> writeTick("New writer", partition, next, ship, null))
                                .get(WRITER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                    }
                    space.setTick(tick + 1);
                }
            } finally {
                // Wakes up the stuck writer, also if the coordinator fails, so the executor can end.
                wakeUp.countDown();
            }
            if (stuck != null) {
                stuck.get(WRITER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }
        }

        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Snapshot of tick %d: the ship is at %s, %s%n", snapshot.tick(),
                    snapshot.get(ship).orElseThrow(), partition.ownership());
        }
    }

    /**
     * The work of a writer in a tick. If the tick has no commit yet, the writer reads the latest
     * snapshot, moves the ship by one on the x axis and commits; then it releases the ownership.
     * A writer with a latch gets stuck after its commit until the latch opens: its release then
     * does nothing, because the ownership already went to another writer.
     */
    private static Void writeTick(String name, Partition2 partition, WriteToken token, long ship,
            CountDownLatch stuckAfterCommit) throws InterruptedException {
        try (PartitionWriter2 writer = partition.writer(token)) {
            long tick = partition.tick();
            // The token makes the value stable: only the commits of this writer can change it.
            if (writer.lastCommitTick() == tick) {
                System.out.printf("%s: tick %d already has a commit, nothing to write%n", name, tick);
                return null;
            }
            Point2 position;
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                position = snapshot.get(ship).orElseThrow();
            }
            writer.set(ship, position.add(1, 0));
            writer.commit();
            System.out.printf("%s: moved the ship from %s and committed tick %d%n", name, position, tick);
            if (stuckAfterCommit != null) {
                System.out.printf("%s: stuck after the commit%n", name);
                stuckAfterCommit.await();
            }
        }
        return null;
    }
}
