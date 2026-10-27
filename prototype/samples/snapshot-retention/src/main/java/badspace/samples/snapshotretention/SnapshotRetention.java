package badspace.samples.snapshotretention;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.partition.LocalPartitionNode2;

/**
 * Sample client: a partition that keeps the snapshots of its last 3 ticks. At tick T readers can
 * acquire the snapshots of the ticks from T - 3 to T - 1; each move of the tick moves the window.
 * A snapshot acquired before it leaves the window stays readable, but cannot be acquired again.
 */
public final class SnapshotRetention {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(LocalPartitionNode2::new);
        Partition2 partition = space.createPartition(node, partitionName("world"),
                PartitionConfig.defaults().withRetainedTicks(3));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            // At each tick from 0 to 3 the ship is at x = tick.
            long ship = writer.insert(0, 0);
            for (long tick = 0; tick <= 3; tick++) {
                writer.update(ship, point(tick, 0));
                writer.commit();
                space.setTick(tick + 1);
            }

            // At tick 4 the window has the ticks from 1 to 3.
            try (AcquiredSnapshot2 past = partition.acquire(1)) {
                System.out.printf("At tick %d the snapshot of tick %d has the ship at %s%n", partition.tick(),
                        past.tick(), past.get(ship).orElseThrow());
                tryAcquire(partition, 0);

                // At tick 5 tick 1 leaves the window, but the acquired snapshot stays readable.
                space.setTick(5);
                System.out.printf("At tick %d the kept snapshot of tick %d still has the ship at %s%n",
                        partition.tick(), past.tick(), past.get(ship).orElseThrow());
            }
            tryAcquire(partition, 1);
        }
    }

    private static void tryAcquire(Partition2 partition, long tick) {
        try (AcquiredSnapshot2 snapshot = partition.acquire(tick)) {
            System.out.printf("At tick %d the snapshot of tick %d is acquired%n", partition.tick(), snapshot.tick());
        } catch (SnapshotNotAvailableException e) {
            System.out.printf("At tick %d the snapshot of tick %d is not available: %s%n", partition.tick(), tick,
                    e.reason());
        }
    }
}
