package badspace.samples.partitionconfiguration;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.RemovalPolicy;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.IndexConfig;
import badspace.service.partition.LocalPartitionNode2;

/**
 * Sample client: partitions with the default configuration and with an explicit one. The
 * configuration is chosen at the creation and does not change: the spatial index, the removal
 * policy and the number of retained ticks. The index also sets the limits of the coordinates
 * that the entities can have.
 */
public final class PartitionConfiguration {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        // A node can host many partitions of the same space, each with a name unique on the node.
        Node2 node = space.createNode(LocalPartitionNode2::new);

        // Without a configuration: linear scan, REQUIRE_EMPTY and one retained tick.
        Partition2 plain = space.createPartition(node, partitionName("plain"));
        // A uniform grid with cells of side 1, and a grid quadtree with a removal policy and a
        // retention window of its own. The samples partition-removal and snapshot-retention
        // show what these two settings do.
        Partition2 grid = space.createPartition(node, partitionName("grid"),
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(1)));
        Partition2 quadtree = space.createPartition(node, partitionName("quadtree"),
                PartitionConfig.defaults()
                        .withIndex(IndexConfig.gridQuadtree(64))
                        .withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES)
                        .withRetainedTicks(5));

        for (Partition2 partition : new Partition2[] {plain, grid, quadtree}) {
            System.out.printf("%s: %s%n  limits %s%n", partition.name().value(), partition.config(),
                    partition.limits());
        }

        // An insert or an update outside the limits fails: here a cell of side 1 limits the grid
        // to 2^30 on each axis, while the linear scan accepts the point.
        System.out.printf("(2e9, 0) is inside the limits of plain: %b, of grid: %b%n",
                plain.limits().contains(point(2e9, 0)), grid.limits().contains(point(2e9, 0)));

        // A partition is created at the tick of its space.
        space.setTick(10);
        Partition2 late = space.createPartition(node, partitionName("late"));
        System.out.printf("%s created at tick %d, %s at tick %d%n", plain.name().value(), plain.creationTick(),
                late.name().value(), late.creationTick());
    }
}
