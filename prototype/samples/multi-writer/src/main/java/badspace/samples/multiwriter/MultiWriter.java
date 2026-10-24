package badspace.samples.multiwriter;

import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.service.partition.LocalPartitionNode2;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sample client, like the loop of a game server: many partitions on one node, each one with its
 * writer on its own thread. The writers do not share state and never wait for each other while
 * they write and commit. At the end of each tick they meet at a barrier, and the last one that
 * arrives moves the tick of the space, which publishes the snapshots of all the partitions.
 * Every few ticks the same thread also reads the latest snapshot of each partition.
 */
public final class MultiWriter {

    private static final int PARTITIONS = 4;
    private static final int ENTITIES_PER_PARTITION = 1000;
    private static final double PARTITION_SIDE = 1000;
    private static final double MAX_STEP = 5;
    private static final int TICKS = 50;
    private static final int TICKS_PER_REPORT = 10;

    public static void main(String[] args) throws InterruptedException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        LocalPartitionNode2 node = new LocalPartitionNode2();
        PartitionConfig config = PartitionConfig.defaults().withIndex(IndexConfig.gridQuadtree(64));
        List<Partition2> partitions = new ArrayList<>();
        for (int i = 0; i < PARTITIONS; i++) {
            partitions.add(space.createPartition(node, partitionName("region-" + i), config));
        }

        // The barrier action runs on the thread of the last writer that arrives, when all the
        // writers have committed the tick.
        CyclicBarrier endOfTick = new CyclicBarrier(PARTITIONS, () -> {
            space.setTick(space.tick() + 1);
            if (space.tick() % TICKS_PER_REPORT == 0) {
                report(partitions);
            }
        });
        List<Thread> writers = new ArrayList<>();
        // Each writer copies the IDs of its entities into its array, so the main thread can check them.
        List<long[]> entityIds = new ArrayList<>();
        for (int i = 0; i < PARTITIONS; i++) {
            long[] ids = new long[ENTITIES_PER_PARTITION];
            entityIds.add(ids);
            Partition2 partition = partitions.get(i);
            double minX = i * PARTITION_SIDE;
            writers.add(Thread.ofPlatform().name("writer-" + i).start(() -> write(partition, minX, ids, endOfTick)));
        }
        for (Thread writer : writers) {
            writer.join();
        }

        Set<Long> distinct = new HashSet<>();
        for (long[] ids : entityIds) {
            for (long id : ids) {
                distinct.add(id);
            }
        }
        System.out.printf("%d entities inserted on %d threads, %d distinct IDs%n",
                PARTITIONS * ENTITIES_PER_PARTITION, PARTITIONS, distinct.size());
    }

    /**
     * The writer of one partition: it inserts its entities in a square of side PARTITION_SIDE
     * from (minX, 0), then at each tick moves them at random, commits and waits for the end of
     * the tick. It keeps the positions in its own state.
     */
    private static void write(Partition2 partition, double minX, long[] entityIds, CyclicBarrier endOfTick) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            List<Point2> positions = new ArrayList<>();
            for (int i = 0; i < ENTITIES_PER_PARTITION; i++) {
                positions.add(new Point2(minX + rng.nextDouble(PARTITION_SIDE), rng.nextDouble(PARTITION_SIDE)));
            }
            long[] ids = writer.insertAll(positions);
            System.arraycopy(ids, 0, entityIds, 0, ids.length);
            List<Entity2> entities = new ArrayList<>();
            for (int i = 0; i < ids.length; i++) {
                entities.add(new Entity2(ids[i], positions.get(i)));
            }
            for (int tick = 0; tick < TICKS; tick++) {
                entities.replaceAll(e -> e.moveBy(rng.nextDouble(-MAX_STEP, MAX_STEP),
                        rng.nextDouble(-MAX_STEP, MAX_STEP)));
                writer.updateAll(entities);
                writer.commit();
                endOfTick.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException e) {
            throw new IllegalStateException("Another writer left the barrier", e);
        }
    }

    /** Shows, for each partition, how many entities of its latest snapshot are still in its square. */
    private static void report(List<Partition2> partitions) {
        System.out.printf("%nTick %d%n", partitions.getFirst().tick());
        for (int i = 0; i < partitions.size(); i++) {
            double minX = i * PARTITION_SIDE;
            Box2 square = new Box2(new Point2(minX, 0), new Point2(minX + PARTITION_SIDE, PARTITION_SIDE));
            try (AcquiredSnapshot2 snapshot = partitions.get(i).acquireLatest()) {
                System.out.printf("  partition %d, snapshot of tick %d: %d entities, %d in its square%n", i,
                        snapshot.tick(), snapshot.size(), snapshot.findInRegion(square).size());
            }
        }
    }
}
