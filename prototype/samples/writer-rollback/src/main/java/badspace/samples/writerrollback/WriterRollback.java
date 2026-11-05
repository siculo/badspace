package badspace.samples.writerrollback;

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
import badspace.service.partition.LocalPartitionNode2;

/**
 * Sample client: a writer discards its writes not committed with a rollback. The writes not
 * committed carry over the move of the tick, and the rollback discards them too: the state of
 * the writer goes back to the last commit, and the writer keeps the ownership. The health of
 * the partition says whether it needs a recovery: after a failure that the partition cannot
 * undo, the same rollback is the recovery.
 */
public final class WriterRollback {

    /** The ship cannot go farther than this from the origin on the x axis. */
    private static final double MAX_X = 10;

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(LocalPartitionNode2::new);
        Partition2 partition = space.createPartition(node, partitionName("world"));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            // Tick 0: the ship starts at (0,0).
            long ship = writer.insert(0, 0);
            writer.commit();
            System.out.printf("Tick 0: committed the ship at (0,0), last commit in tick %d%n",
                    writer.lastCommitTick());
            space.setTick(1);

            // Tick 1: a move without a commit carries over to tick 2.
            writer.set(ship, point(4, 0));
            space.setTick(2);

            // Tick 2: a second move takes the ship too far, so the writer discards both moves.
            Point2 target = point(14, 0);
            writer.set(ship, target);
            if (target.x() > MAX_X) {
                writer.rollback();
                System.out.printf("Tick 2: %s is too far, rollback to the commit of tick %d%n", target,
                        writer.lastCommitTick());
            }
            System.out.printf("Tick 2: %s, health %s%n", partition.ownership(), partition.health());

            // The writer goes on from the last commit and confirms a valid move.
            writer.set(ship, point(1, 0));
            writer.commit();
            space.setTick(3);

            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("Snapshot of tick %d: the ship is at %s%n", snapshot.tick(),
                        snapshot.get(ship).orElseThrow());
            }
        }
    }
}
