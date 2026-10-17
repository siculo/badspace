package badspace.samples.randomupdater;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionNode2;
import badspace.service.partition.LocalPartitionNode2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

/**
 * Sample client that tries the API of the writer and of the reader on two threads.
 * The writer runs for {@link #WRITER_SECONDS} seconds: at each tick it moves the
 * entities at random, commits and moves the space to the next tick, which publishes
 * the snapshot of the tick. While the writer runs, the reader wakes up once every
 * {@link #TICKS_PER_READ} ticks, acquires the latest snapshot, shows it and releases it.
 */
public final class RandomUpdater {

    private static final int ENTITIES = 10;
    private static final int TICKS_PER_SECOND = 10;
    private static final int WRITER_SECONDS = 10;
    private static final int TICKS_PER_READ = 10;
    private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(1) / TICKS_PER_SECOND;
    private static final long READ_PERIOD_MILLIS = TimeUnit.NANOSECONDS.toMillis(TICK_NANOS * TICKS_PER_READ);

    public static void main(String[] args) throws InterruptedException {
        new RandomUpdater().run();
    }

    // Used by the main thread for the first positions, then only by the writer thread.
    private final RandomGenerator rng = RandomGenerator.getDefault();

    public void run() throws InterruptedException {
        SpaceConfig spaceConfig = SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0));
        Space2 space = new Space2(spaceConfig);
        PartitionNode2 node = new LocalPartitionNode2();
        PartitionConfig config = PartitionConfig.defaults()
                .withIndex(IndexConfig.gridQuadtree(128));
        Partition2 partition = space.createPartition(node, config);

        // The main thread claims the new partition and inserts the entities with the handle of
        // the token; the writer thread gets the handle and commits them at its first tick.
        // Starting the threads afterwards makes these writes visible to them.
        PartitionWriter2 partitionWriter = partition.writer(partition.claim(0).orElseThrow());
        List<Point2> positions = randomPoints(ENTITIES, -2000, 2000);
        long[] entityIds = partitionWriter.insertAll(positions);
        List<Entity2> entities = new ArrayList<>(positions.size());
        for (int i = 0; i < entityIds.length; i++) {
            entities.add(new Entity2(entityIds[i], positions.get(i)));
        }

        Thread writer = new Thread(() -> write(space, partitionWriter, entities), "writer");
        Thread reader = new Thread(() -> read(partition, entityIds, writer), "reader");
        writer.start();
        reader.start();
        writer.join();
        reader.join();
    }

    /**
     * The writer: one commit per tick, for WRITER_SECONDS seconds. It also moves the tick of
     * the space, so the commit of a tick is published at the start of the next one.
     */
    private void write(Space2 space, PartitionWriter2 partitionWriter, List<Entity2> entities) {
        long ticks = (long) WRITER_SECONDS * TICKS_PER_SECOND;
        long start = System.nanoTime();
        // At the end the writer closes its handle, which releases the ownership.
        try (partitionWriter) {
            for (long tick = 1; tick <= ticks; tick++) {
                // The writer keeps the positions in its own state: the partition has no reads
                // of the writes not yet published.
                entities.replaceAll(e -> e.moveBy(rng.nextDouble(-10, 10), rng.nextDouble(-10, 10)));
                partitionWriter.updateAll(entities);
                partitionWriter.commit();
                space.setTick(tick);
                // Waits until the start of the next tick, so a slow tick does not move the others.
                long next = start + tick * TICK_NANOS;
                TimeUnit.NANOSECONDS.sleep(next - System.nanoTime());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The reader: while the writer runs, shows the latest snapshot once every
     * TICKS_PER_READ ticks. join() wakes it up also when the writer ends, so the last
     * snapshot it shows has the last commit of the writer.
     */
    private void read(Partition2 partition, long[] entityIds, Thread writer) {
        try {
            while (writer.isAlive()) {
                writer.join(READ_PERIOD_MILLIS);
                try (AcquiredSnapshot2 latest = partition.acquireLatest()) {
                    display(latest.tick(), latest.getAll(entityIds));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Point2> randomPoints(long n, double min, double max) {
        return Stream.generate(() -> new Point2(rng.nextDouble(min, max), rng.nextDouble(min, max)))
                .limit(n).toList();
    }

    private static void display(long tick, List<Entity2> entities) {
        System.out.printf("%nTick: %d%n", tick);
        System.out.printf("%s%n", "=".repeat(20));
        for (Entity2 e : entities) {
            System.out.printf("%f, %f%n", e.position().x(), e.position().y());
        }
    }
}
