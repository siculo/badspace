package badspace.samples.commitsandticks;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.service.partition.LocalPartitionNode2;

/**
 * Sample client: the commit confirms the writes in the current tick, and the move of the tick
 * publishes the snapshot of the tick that ends. If a tick has more commits, its snapshot has the
 * last one. The writes after the last commit are not lost: they go to the next tick.
 */
public final class CommitsAndTicks {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(LocalPartitionNode2::new);
        Partition2 partition = space.createPartition(node, partitionName("world"));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            // Tick 0: one commit.
            long ship = writer.insert(1, 0);
            writer.commit();
            space.setTick(1);
            show(partition, ship);

            // Tick 1: two commits; the snapshot of tick 0 stays the latest until the move of the tick.
            writer.update(ship, point(2, 0));
            writer.commit();
            writer.update(ship, point(3, 0));
            writer.commit();
            show(partition, ship);

            // A write after the last commit of tick 1: the snapshot of tick 1 has (3,0).
            writer.update(ship, point(4, 0));
            space.setTick(2);
            show(partition, ship);

            // Tick 2: the commit confirms the write that came from tick 1.
            writer.commit();
            space.setTick(3);
            show(partition, ship);
        }
    }

    private static void show(Partition2 partition, long ship) {
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("At tick %d the latest snapshot is of tick %d: the ship is at %s%n", partition.tick(),
                    snapshot.tick(), snapshot.get(ship).orElseThrow());
        }
    }
}
