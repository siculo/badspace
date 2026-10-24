package badspace.samples.ticksandretention;

import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.partition.LocalPartitionNode2;

/**
 * Sample client: a partition that keeps the snapshots of its last 3 ticks. With the tick T of
 * the partition, readers can acquire the snapshots of the ticks from T - 3 to T - 1: the snapshot
 * of T is not published yet, and older ones are out of the window. A skipped tick has a snapshot
 * too, with the content of the tick before it. An acquired snapshot stays readable also after
 * it leaves the window.
 */
public final class TicksAndRetention {

    private static final int RETAINED_TICKS = 3;

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        space.setTick(2);
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"),
                PartitionConfig.defaults().withRetainedTicks(RETAINED_TICKS));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            // Before the first move of the tick there is nothing to acquire.
            tryAcquireLatest(partition);

            // One entity more at each tick, from tick 2 to tick 4.
            for (long tick = 3; tick <= 5; tick++) {
                writer.insert(tick, tick);
                writer.commit();
                space.setTick(tick);
            }
            showWindow(partition);

            // The reader keeps the snapshot of tick 2, the oldest one in the window.
            try (AcquiredSnapshot2 oldest = partition.acquire(2)) {
                // Ticks can be skipped: from 5 to 8, the snapshots of 5, 6 and 7 have the same
                // content, the last commit before the move.
                writer.insert(5, 5);
                writer.commit();
                space.setTick(8);
                showWindow(partition);
                System.out.printf("The snapshot of tick %d is out of the window and still has %d entities%n",
                        oldest.tick(), oldest.size());
            }
            // Out of the window and released: it cannot be acquired again.
            tryAcquire(partition, 2);
            tryAcquire(partition, 1);
        }
    }

    /** Tries all the ticks from the creation of the partition to its tick. */
    private static void showWindow(Partition2 partition) {
        System.out.printf("%nThe partition is at tick %d%n", partition.tick());
        for (long tick = partition.creationTick(); tick <= partition.tick(); tick++) {
            tryAcquire(partition, tick);
        }
    }

    private static void tryAcquire(Partition2 partition, long tick) {
        try (AcquiredSnapshot2 snapshot = partition.acquire(tick)) {
            System.out.printf("  tick %d: %d entities%n", snapshot.tick(), snapshot.size());
        } catch (SnapshotNotAvailableException e) {
            System.out.printf("  tick %d: not available, %s%n", tick, e.reason());
        }
    }

    private static void tryAcquireLatest(Partition2 partition) {
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("The latest snapshot is of tick %d%n", snapshot.tick());
        } catch (SnapshotNotAvailableException e) {
            System.out.printf("At tick %d there is no snapshot to acquire yet: %s%n", partition.tick(), e.reason());
        }
    }
}
