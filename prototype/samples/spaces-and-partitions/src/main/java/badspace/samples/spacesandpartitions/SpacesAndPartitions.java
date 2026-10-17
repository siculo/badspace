package badspace.samples.spacesandpartitions;

import static badspace.common.geometry.Point3.point;

import badspace.api.AcquiredSnapshot3;
import badspace.api.Partition;
import badspace.api.Partition2;
import badspace.api.Partition3;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter3;
import badspace.api.RemovalPolicy;
import badspace.api.Space;
import badspace.api.Space2;
import badspace.api.Space3;
import badspace.api.SpaceConfig;
import badspace.api.id.IdGenerator;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box3;
import badspace.common.partition.IndexConfig;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.partition.LocalPartitionNode3;
import java.util.List;

/**
 * Sample client: creates a 2D and a 3D space that share the generators of the IDs, and
 * partitions on local nodes with different configurations: the index, the removal policy and
 * the number of retained ticks. It shows what a partition tells about itself, the operations
 * shared by 2D and 3D, and that a 3D partition works like a 2D one with one more coordinate.
 */
public final class SpacesAndPartitions {

    public static void main(String[] args) {
        // One generator for each kind of ID for the whole process, shared by all the spaces.
        // The generator ID comes from the configuration; the two kinds of ID can share it.
        long generatorId = Long.getLong("badspace.generatorId", 0);
        IdGenerator entityIds = new SnowflakeIdGenerator(generatorId);
        IdGenerator partitionIds = new SnowflakeIdGenerator(generatorId);
        SpaceConfig spaceConfig = SpaceConfig.of(entityIds, partitionIds);
        Space2 space2 = new Space2(spaceConfig);
        Space3 space3 = new Space3(spaceConfig);

        // A node can host many partitions of the same space. Without a configuration a
        // partition has the defaults: linear scan, REQUIRE_EMPTY and one retained tick.
        LocalPartitionNode2 node2 = new LocalPartitionNode2();
        Partition2 scan = space2.createPartition(node2);
        Partition2 grid = space2.createPartition(node2,
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(10)));
        Partition2 quadtree = space2.createPartition(node2, PartitionConfig.defaults()
                .withIndex(IndexConfig.gridQuadtree(64))
                .withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES)
                .withRetainedTicks(5));

        // The configuration is checked when it is made, before any call to a node.
        try {
            PartitionConfig.defaults().withRetainedTicks(0);
        } catch (IllegalArgumentException e) {
            System.out.printf("Rejected configuration: %s%n", e.getMessage());
        }
        try {
            IndexConfig.gridQuadtree(10);
        } catch (IllegalArgumentException e) {
            System.out.printf("Rejected index: %s%n", e.getMessage());
        }

        // The spaces share the operations on the tick. A partition is created at the tick of
        // its space, and the space moves a new node to its tick before the creation.
        for (Space<?, ?> space : List.<Space<?, ?>>of(space2, space3)) {
            space.setTick(10);
        }
        Partition3 octree = space3.createPartition(new LocalPartitionNode3(),
                PartitionConfig.defaults().withIndex(IndexConfig.gridQuadtree(16)));

        // The partitions share the operations that tell about them; the limits of the
        // coordinates come from the index.
        describe("2D linear scan", scan);
        describe("2D uniform grid", grid);
        describe("2D grid of quadtrees", quadtree);
        describe("3D grid of octrees", octree);

        // The 3D partition has the same writes and reads, with a third coordinate.
        try (PartitionWriter3 writer = octree.writer(octree.claim(0).orElseThrow())) {
            long entityId = writer.insert(1, 2, 3);
            writer.insert(100, 100, 100);
            writer.commit();
            space3.setTick(11);
            try (AcquiredSnapshot3 snapshot = octree.acquireLatest()) {
                System.out.printf("3D: entity %d is at %s; in the box (0,0,0)-(10,10,10): %s%n", entityId,
                        snapshot.get(entityId).orElseThrow(),
                        snapshot.findInRegion(new Box3(point(0, 0, 0), point(10, 10, 10))));
            }
        }
    }

    private static void describe(String name, Partition partition) {
        System.out.printf("%s: %s%n  limits %s, created at tick %d, at tick %d, ownership %s%n", name,
                partition.config(), partition.limits(), partition.creationTick(), partition.tick(),
                partition.ownership());
    }
}
