package badspace.samples.writerfailover;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode2;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Sample client: a coordinator moves the tick of a space when the writer of its partition has
 * committed the tick. At one tick the writer gets stuck, like in a long pause, after its writes
 * and before its commit. The coordinator waits for the commit for some time, then takes the
 * ownership away with a claim and gives the token to a new writer. The new writer rebuilds its
 * state from the latest snapshot and commits the tick again. When the old writer wakes up, its
 * commit is rejected: the fencing token keeps it from writing over the new writer.
 */
public final class WriterFailover {

    private static final int ENTITIES = 3;
    private static final double STEP = 1;
    private static final long TICKS = 12;
    private static final long STUCK_TICK = 5;
    private static final long COMMIT_TIMEOUT_MILLIS = 500;

    public static void main(String[] args) throws InterruptedException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        BlockingQueue<Long> commits = new LinkedBlockingQueue<>();
        CountDownLatch wakeUp = new CountDownLatch(1);

        Writer current = new Writer("writer 1", partition, partition.claim(0).orElseThrow(), commits, STUCK_TICK,
                wakeUp);
        Thread stuck = Thread.ofPlatform().start(current);
        Thread next = null;
        for (long tick = 0; tick < TICKS; tick++) {
            current.startTick(tick);
            if (commits.poll(COMMIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) == null) {
                // The coordinator reads the generation and claims with it: the old token is no
                // longer current, and the writes not committed in this tick are discarded.
                WriteToken token = partition.claim(partition.ownership().generation()).orElseThrow();
                System.out.printf("Coordinator: no commit of tick %d after %d ms, the ownership goes to %s%n",
                        tick, COMMIT_TIMEOUT_MILLIS, token);
                current = new Writer("writer 2", partition, token, commits, Writer.NEVER_STUCK, new CountDownLatch(0));
                next = Thread.ofPlatform().start(current);
                current.startTick(tick);
                commits.take();
            }
            space.setTick(tick + 1);
        }
        current.stop();
        wakeUp.countDown();
        stuck.join();
        if (next != null) {
            next.join();
        }

        // Each tick was committed once: every entity moved by STEP at each tick.
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Snapshot of tick %d: %s%n", snapshot.tick(), snapshot.findInRegion(all(partition)));
        }
    }

    /** The region with all the coordinates that the entities of the partition can have. */
    private static Box2 all(Partition2 partition) {
        CoordinateLimits limits = partition.limits();
        return new Box2(new Point2(limits.min(), limits.min()), new Point2(limits.max(), limits.max()));
    }

    /**
     * A writer on its own thread: at each tick that the coordinator gives, it moves its entities,
     * commits and tells the coordinator. At stuckTick it waits for wakeUp before its commit.
     */
    private static final class Writer implements Runnable {

        static final long NEVER_STUCK = -1;
        private static final long STOP = -1;

        private final String name;
        private final Partition2 partition;
        private final WriteToken token;
        private final BlockingQueue<Long> commits;
        private final long stuckTick;
        private final CountDownLatch wakeUp;
        // Written by the coordinator, read by the writer thread.
        private final BlockingQueue<Long> ticks = new LinkedBlockingQueue<>();

        Writer(String name, Partition2 partition, WriteToken token, BlockingQueue<Long> commits, long stuckTick,
                CountDownLatch wakeUp) {
            this.name = name;
            this.partition = partition;
            this.token = token;
            this.commits = commits;
            this.stuckTick = stuckTick;
            this.wakeUp = wakeUp;
        }

        void startTick(long tick) throws InterruptedException {
            ticks.put(tick);
        }

        void stop() throws InterruptedException {
            ticks.put(STOP);
        }

        @Override
        public void run() {
            // close() does nothing when the ownership already went to another writer.
            try (PartitionWriter2 writer = partition.writer(token)) {
                List<Entity2> entities = load(writer);
                for (long tick = ticks.take(); tick != STOP; tick = ticks.take()) {
                    entities.replaceAll(e -> e.moveBy(STEP, 0));
                    writer.updateAll(entities);
                    if (tick == stuckTick) {
                        System.out.printf("%s: stuck before the commit of tick %d%n", name, tick);
                        wakeUp.await();
                    }
                    writer.commit();
                    System.out.printf("%s: committed tick %d%n", name, tick);
                    commits.put(tick);
                }
            } catch (OwnershipChangedException e) {
                System.out.printf("%s: wakes up and its commit is rejected: %s%n", name, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Returns the state of the writer: new entities on a new partition, otherwise the
         * entities of the latest snapshot, the last state that a writer committed.
         */
        private List<Entity2> load(PartitionWriter2 writer) {
            List<Entity2> entities = new ArrayList<>();
            if (partition.tick() == partition.creationTick()) {
                for (int i = 0; i < ENTITIES; i++) {
                    Point2 position = new Point2(0, i);
                    entities.add(new Entity2(writer.insert(position), position));
                }
                return entities;
            }
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                entities.addAll(snapshot.findInRegion(all(partition)));
                System.out.printf("%s: loaded %d entities from the snapshot of tick %d%n", name, entities.size(),
                        snapshot.tick());
            }
            return entities;
        }
    }
}
