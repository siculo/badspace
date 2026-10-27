package badspace.samples.writerfailover;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Point2;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode2;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sample client: the main thread is a coordinator that moves the tick when the writer has
 * committed it. In tick 1 the writer gets stuck before its commit, like in a long pause. After a
 * timeout the coordinator takes the ownership away with a claim and gives the new token to a new
 * writer, which goes on from the latest snapshot and commits tick 1. When the old writer wakes
 * up, its commit is rejected: its token is not the current one.
 */
public final class WriterFailover {

    private static final long COMMIT_TIMEOUT_MILLIS = 500;

    public static void main(String[] args) throws InterruptedException, ExecutionException, TimeoutException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(LocalPartitionNode2::new);
        Partition2 partition = space.createPartition(node, partitionName("world"));

        // Tick 0: the first writer inserts the ship at (0,0).
        PartitionWriter2 first = partition.writer(partition.claim(0).orElseThrow());
        long ship = first.insert(0, 0);
        first.commit();
        space.setTick(1);

        CountDownLatch wakeUp = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            // Tick 1: the first writer moves the ship and gets stuck before the commit.
            Future<?> firstWriting = executor.submit(() -> {
                // close() does nothing when the ownership already went to another writer.
                try (first) {
                    first.update(ship, point(1, 0));
                    System.out.println("Writer 1: stuck before the commit of tick 1");
                    wakeUp.await();
                    first.commit();
                    System.out.println("Writer 1: committed tick 1");
                } catch (OwnershipChangedException e) {
                    System.out.printf("Writer 1: wakes up and its commit is rejected: %s%n", e.getMessage());
                }
                return null;
            });

            try {
                firstWriting.get(COMMIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // The coordinator claims with the generation it reads: the token of the first
                // writer is no longer current, and its writes not committed are discarded.
                WriteToken token = partition.claim(partition.ownership().generation()).orElseThrow();
                System.out.printf("Coordinator: no commit of tick 1 after %d ms, the ownership goes to %s%n",
                        COMMIT_TIMEOUT_MILLIS, token);
                Future<?> secondWriting = executor.submit(() -> {
                    try (PartitionWriter2 second = partition.writer(token)) {
                        // The new writer goes on from the latest snapshot, the last committed state.
                        Point2 position;
                        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                            position = snapshot.get(ship).orElseThrow();
                            System.out.printf("Writer 2: the snapshot of tick %d has the ship at %s%n",
                                    snapshot.tick(), position);
                        }
                        second.update(ship, position.add(1, 0));
                        second.commit();
                        System.out.println("Writer 2: committed tick 1");
                    }
                    return null;
                });
                secondWriting.get(COMMIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } finally {
                // Wakes up the first writer, also if the coordinator fails, so the executor can end.
                wakeUp.countDown();
            }
            firstWriting.get(COMMIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            space.setTick(2);
        }

        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Snapshot of tick %d: the ship is at %s, %s%n", snapshot.tick(),
                    snapshot.get(ship).orElseThrow(), partition.ownership());
        }
    }
}
