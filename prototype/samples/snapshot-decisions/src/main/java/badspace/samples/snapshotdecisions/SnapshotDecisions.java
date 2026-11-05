package badspace.samples.snapshotdecisions;

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
import java.util.HashSet;
import java.util.Set;

/**
 * Sample client: the writer decides its writes on a snapshot, then writes without conditions.
 * The snapshot does not see the writes of the current tick, also after their commit: they are
 * in the snapshot of the tick only when the tick moves forward. So the writer keeps its own
 * decisions of the tick, here the entities it reserved, and checks them together with the
 * snapshot.
 */
public final class SnapshotDecisions {

    private static final String RESERVED_BY = "reservedBy";

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(LocalPartitionNode2::new);
        Partition2 partition = space.createPartition(node, partitionName("world"));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            long ship = writer.insert(1, 1);
            long crate = writer.insert(5, 5);
            writer.commit();
            space.setTick(1);

            // Tick 1: two players ask for the ship and the crate. The writer reads the snapshot
            // of tick 0 and remembers what it reserves in this tick.
            Set<Long> reservedInThisTick = new HashSet<>();
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                for (long player : new long[] {7, 8}) {
                    boolean free = isFree(snapshot, ship) && isFree(snapshot, crate);
                    boolean taken = reservedInThisTick.contains(ship) || reservedInThisTick.contains(crate);
                    System.out.printf("Player %d: free in the snapshot of tick %d: %b, taken in this tick: %b%n",
                            player, snapshot.tick(), free, taken);
                    if (free && !taken) {
                        writer.putMetadata(ship, RESERVED_BY, player);
                        writer.putMetadata(crate, RESERVED_BY, player);
                        writer.set(ship, point(4, 5));
                        reservedInThisTick.add(ship);
                        reservedInThisTick.add(crate);
                    }
                }
            }
            writer.commit();

            // The commit is done, but the latest snapshot is still the one of tick 0.
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("After the commit, tick %d: ship free %b%n", snapshot.tick(), isFree(snapshot, ship));
            }

            space.setTick(2);
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("Tick %d: ship at %s with %s, crate with %s%n", snapshot.tick(),
                        snapshot.get(ship).orElseThrow(), snapshot.getMetadata(ship).orElseThrow(),
                        snapshot.getMetadata(crate).orElseThrow());
            }
        }
    }

    /** Returns whether nobody reserved the entity in the snapshot. */
    private static boolean isFree(AcquiredSnapshot2 snapshot, long entityId) {
        return !snapshot.getMetadata(entityId).orElseThrow().containsKey(RESERVED_BY);
    }
}
