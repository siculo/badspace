package badspace.client;

import badspace.api.Partition;
import badspace.api.Partition2;
import badspace.api.Partition3;
import badspace.api.SnowflakeIdGenerator;
import badspace.api.Space2;
import badspace.api.Space3;
import badspace.common.Entity2;
import badspace.common.Point2;
import badspace.common.Point3;
import badspace.service.LocalPartitionNode2;
import badspace.service.LocalPartitionNode3;
import java.util.List;

/**
 * Test client: creates a 2D and a 3D space with partitions on local nodes,
 * sharing one entity ID generator,
 * then inserts, reads, updates and removes entities.
 */
public class Main {

    public static void main(String[] args) {
        // One generator for the whole process. The generator ID comes from the configuration.
        long generatorId = Long.getLong("badspace.generatorId", 0);
        SnowflakeIdGenerator entityIds = new SnowflakeIdGenerator(generatorId);

        Space2 space2 = new Space2(entityIds);
        Partition2 p2a = space2.createPartition(new LocalPartitionNode2());
        Partition2 p2b = space2.createPartition(new LocalPartitionNode2());
        long a = p2a.insert(10.5, 3.0);
        long b = p2b.insert(-7.25, 42.0);
        System.out.printf("2D: entity %d on node A, entity %d on node B%n", a, b);

        // Batch operations: one call to the node for many entities
        long[] ids = p2a.insertAll(List.of(new Point2(0, 0), new Point2(1, 1), new Point2(2, 2)));
        p2a.updateAll(List.of(new Entity2(ids[0], new Point2(5, 5)), new Entity2(ids[1], new Point2(6, 6))));
        p2a.removeAll(new long[] {ids[2]});
        System.out.printf("2D: after the batch, node A has %s%n", p2a.getAll(new long[] {a, ids[0], ids[1], ids[2]}));

        Space3 space3 = new Space3(entityIds);
        Partition3 p3 = space3.createPartition(new LocalPartitionNode3());
        long c = p3.insert(1.0, 2.0, 3.0);
        p3.update(c, new Point3(4.0, 5.0, 6.0));
        System.out.printf("3D: entity %d is at %s%n", c, p3.get(c).orElseThrow());

        // 2D and 3D partitions through the common interface
        List<Partition> partitions = List.of(p2a, p2b, p3);
        int total = 0;
        for (Partition p : partitions) {
            total += p.size();
        }
        System.out.printf("%d partitions, %d entities%n", partitions.size(), total);
    }
}
