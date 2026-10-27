package badspace.samples.threedimensionalspace;

import static badspace.common.geometry.Point3.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot3;
import badspace.api.Node3;
import badspace.api.Partition3;
import badspace.api.PartitionWriter3;
import badspace.api.Space3;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box3;
import badspace.service.partition.LocalPartitionNode3;

/**
 * Sample client: a 3D space works like a 2D one, with the types ending in 3 and a third
 * coordinate. The entities differ only in z, so a box query sees the third coordinate.
 */
public final class ThreeDimensionalSpace {

    public static void main(String[] args) {
        Space3 space = new Space3(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node3 node = space.createNode(LocalPartitionNode3::new);
        Partition3 partition = space.createPartition(node, partitionName("sky"));

        try (PartitionWriter3 writer = partition.writer(partition.claim(0).orElseThrow())) {
            writer.insert(10, 10, 0);
            writer.insert(10, 10, 100);
            writer.commit();
        }
        space.setTick(1);

        try (AcquiredSnapshot3 snapshot = partition.acquireLatest()) {
            System.out.printf("Snapshot of tick %d with %d entities%n", snapshot.tick(), snapshot.size());
            System.out.printf("In the box (0,0,0)-(20,20,20): %s%n",
                    snapshot.findInRegion(new Box3(point(0, 0, 0), point(20, 20, 20))));
            System.out.printf("The nearest to (10,10,90): %s%n", snapshot.findNearest(point(10, 10, 90), 1));
        }
    }
}
