package badspace.samples.gettingstarted;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box2;
import badspace.service.partition.LocalPartitionNode2;

/**
 * Sample client: the shortest complete path through the API. It creates a 2D space and a
 * partition on a local node, claims the partition and writes through the writer handle, commits
 * the writes, moves the tick to publish them, and reads the published snapshot.
 */
public final class GettingStarted {

    public static void main(String[] args) {
        // The space gives the IDs of the entities and of the partitions: one generator for each
        // kind of ID, shared by all the spaces of the process.
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        // A local node runs in this process. The software chooses the name of the partition.
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"));

        // A new partition has no owner, at generation 0: the claim returns the write token, and
        // the writer writes through a handle with it. Closing the handle releases the ownership.
        long ship;
        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            ship = writer.insert(10, 10);
            writer.insert(50, 50);
            // The commit confirms the writes in the current tick, tick 0.
            writer.commit();
        }

        // Moving the space to tick 1 publishes the snapshot of tick 0.
        space.setTick(1);

        // Readers read an acquired snapshot. Closing it releases the acquisition.
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Snapshot of tick %d with %d entities%n", snapshot.tick(), snapshot.size());
            System.out.printf("The ship is at %s%n", snapshot.get(ship).orElseThrow());
            System.out.printf("In the box (0,0)-(20,20): %s%n",
                    snapshot.findInRegion(new Box2(point(0, 0), point(20, 20))));
        }
    }
}
