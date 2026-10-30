package badspace.samples.concurrentreaderwriter;

import static badspace.common.partition.Entity2.entity;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.Entity2;
import badspace.service.partition.LocalPartitionNode2;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Sample client: one writer and one reader on two threads. The writer thread moves two entities
 * by one step at each tick, commits and moves the tick. The main thread is the reader: a few
 * times per second it acquires the latest snapshot, prints it and releases it. The reader never
 * waits for the writer, and the writer never waits for the reader.
 */
public final class ConcurrentReaderWriter {

    private static final int TICKS = 20;
    private static final long MILLIS_PER_TICK = 50;
    private static final long MILLIS_PER_READ = 200;

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(LocalPartitionNode2::new);
        Partition2 partition = space.createPartition(node, partitionName("world"));

        // The main thread inserts the entities and publishes tick 0, so the reader always finds
        // a snapshot. Then the handle and the list go to the writer thread, the only one that
        // uses them; submitting the task makes these writes visible to it.
        PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow());
        long[] ids = {writer.insert(0, 0), writer.insert(0, 100)};
        List<Entity2> entities = new ArrayList<>(List.of(entity(ids[0], 0, 0), entity(ids[1], 0, 100)));
        writer.commit();
        space.setTick(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> writing = executor.submit(() -> {
                // Closing the handle at the end releases the ownership.
                try (writer) {
                    for (long tick = 1; tick <= TICKS; tick++) {
                        // The writer keeps the positions in its own state: it does not read its writes.
                        entities.replaceAll(e -> e.moveBy(1, 0));
                        writer.updateAll(entities);
                        writer.commit();
                        space.setTick(tick + 1);
                        Thread.sleep(MILLIS_PER_TICK);
                    }
                }
                return null;
            });

            while (!writing.isDone()) {
                try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                    System.out.printf("Snapshot of tick %d: %s%n", snapshot.tick(), snapshot.getAll(ids));
                }
                Thread.sleep(MILLIS_PER_READ);
            }
            // Throws if the writer failed.
            writing.get();
        }
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Last snapshot, of tick %d: %s%n", snapshot.tick(), snapshot.getAll(ids));
        }
    }
}
