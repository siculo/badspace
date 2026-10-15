package badspace.client;

import static badspace.common.geometry.Point2.point;
import static badspace.common.geometry.Point3.point;
import static badspace.common.partition.Entity2.entity;

import badspace.api.Partition;
import badspace.api.Partition2;
import badspace.api.Partition3;
import badspace.api.PartitionConfig;
import badspace.api.RemovalPolicy;
import badspace.api.id.IdGenerator;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.api.Space2;
import badspace.api.Space3;
import badspace.api.SpaceConfig;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Circle2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.RetentionWindow;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.partition.LocalPartitionNode3;
import java.util.List;

/**
 * Test client: creates a 2D and a 3D space with partitions on local nodes,
 * sharing the generators of entity IDs and of partition IDs,
 * then inserts, reads, updates, queries, commits and removes entities, and at last removes partitions.
 */
public class Main {

    public static void main(String[] args) {
        // One generator for each kind of ID for the whole process. The generator ID comes from
        // the configuration; the two kinds of ID can share it.
        long generatorId = Long.getLong("badspace.generatorId", 0);
        IdGenerator entityIds = new SnowflakeIdGenerator(generatorId);
        IdGenerator partitionIds = new SnowflakeIdGenerator(generatorId);
        SpaceConfig spaceConfig = SpaceConfig.of(entityIds, partitionIds);

        Space2 space2 = new Space2(spaceConfig);
        Partition2 p2a = space2.createPartition(new LocalPartitionNode2());
        Partition2 p2b = space2.createPartition(new LocalPartitionNode2());
        long a = p2a.insert(10.5, 3.0);
        long b = p2b.insert(-7.25, 42.0);
        System.out.printf("2D: entity %d on node A, entity %d on node B%n", a, b);

        // Batch operations: one call to the node for many entities
        long[] ids = p2a.insertAll(List.of(point(0, 0), point(1, 1), point(2, 2)));
        p2a.updateAll(List.of(entity(ids[0], 5, 5), entity(ids[1], 6, 6)));
        p2a.removeAll(new long[] {ids[2]});
        System.out.printf("2D: after the batch, node A has %s%n", p2a.getAll(new long[] {a, ids[0], ids[1], ids[2]}));

        // Queries: entities in a region and entities nearest to a point
        System.out.printf("2D: in the box (4,4)-(7,7): %s%n",
                p2a.findInRegion(new Box2(point(4, 4), point(7, 7))));
        System.out.printf("2D: in the circle of radius 1 around (10,3): %s%n",
                p2a.findInRegion(new Circle2(point(10, 3), 1)));
        System.out.printf("2D: the 2 nearest to (0,0): %s%n", p2a.findNearest(point(0, 0), 2));

        // The software chooses the commit number, for example the tick, and can skip numbers
        p2a.commit(1);
        p2a.commit(3);
        System.out.printf("2D: last commit of node A is %d%n", p2a.lastCommit());

        Space3 space3 = new Space3(spaceConfig);
        Partition3 p3 = space3.createPartition(new LocalPartitionNode3(),
                new PartitionConfig(IndexConfig.uniformGrid(10), RemovalPolicy.DISCARD_ENTITIES,
                        RetentionWindow.DEFAULT_RETAINED_PAST_VERSIONS));
        long c = p3.insert(1.0, 2.0, 3.0);
        p3.update(c, point(4.0, 5.0, 6.0));
        System.out.printf("3D: entity %d is at %s%n", c, p3.get(c).orElseThrow());

        // 2D and 3D partitions through the common interface
        List<Partition> partitions = List.of(p2a, p2b, p3);
        int total = 0;
        for (Partition p : partitions) {
            total += p.size();
        }
        System.out.printf("%d partitions, %d entities%n", partitions.size(), total);

        // Removal follows the policy chosen at creation: p2b must be empty, p3 discards its entities
        p2b.remove(b);
        space2.removePartition(p2b);
        space3.removePartition(p3);
        System.out.println("Removed the emptied 2D partition and the 3D partition with its entities");
    }
}
