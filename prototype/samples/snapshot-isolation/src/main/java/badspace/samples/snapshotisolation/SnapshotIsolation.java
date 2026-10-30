package badspace.samples.snapshotisolation;

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
 * Sample client: a snapshot is the partition at the end of a tick and never changes. A reader
 * that keeps a snapshot does not see the commits that come after it; a new acquisition does.
 */
public final class SnapshotIsolation {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(LocalPartitionNode2::new);
        Partition2 partition = space.createPartition(node, partitionName("world"));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            long ship = writer.insert(1, 1);
            writer.commit();
            space.setTick(1);

            try (AcquiredSnapshot2 old = partition.acquireLatest()) {
                // The writer moves the ship and the tick moves on while the reader keeps its snapshot.
                writer.update(ship, point(2, 2));
                writer.commit();
                space.setTick(2);

                try (AcquiredSnapshot2 latest = partition.acquireLatest()) {
                    System.out.printf("Kept snapshot of tick %d: the ship is at %s%n", old.tick(),
                            old.get(ship).orElseThrow());
                    System.out.printf("New snapshot of tick %d: the ship is at %s%n", latest.tick(),
                            latest.get(ship).orElseThrow());
                }
            }
        }
    }
}
