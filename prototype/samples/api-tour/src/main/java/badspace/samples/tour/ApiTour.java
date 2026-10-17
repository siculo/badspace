package badspace.samples.tour;

import static badspace.common.geometry.Point2.point;
import static badspace.common.geometry.Point3.point;
import static badspace.common.partition.Entity2.entity;

import badspace.api.AcquiredSnapshot2;
import badspace.api.AcquiredSnapshot3;
import badspace.api.Partition2;
import badspace.api.Partition3;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter;
import badspace.api.PartitionWriter2;
import badspace.api.PartitionWriter3;
import badspace.api.RemovalPolicy;
import badspace.api.id.IdGenerator;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.api.Space2;
import badspace.api.Space3;
import badspace.api.SpaceConfig;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Circle2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.RetentionWindow;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.partition.LocalPartitionNode3;
import java.util.List;

/**
 * Sample client: creates a 2D and a 3D space with partitions on local nodes,
 * sharing the generators of entity IDs and of partition IDs,
 * then claims them and, through the writer handles, inserts, updates, removes and commits
 * entities; reads and queries the acquired snapshots, passes the ownership of a partition to
 * another writer, and at last removes partitions.
 */
public class ApiTour {

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
        Space3 space3 = new Space3(spaceConfig);
        Partition3 p3 = space3.createPartition(new LocalPartitionNode3(),
                new PartitionConfig(IndexConfig.uniformGrid(10), RemovalPolicy.DISCARD_ENTITIES,
                        RetentionWindow.DEFAULT_RETAINED_TICKS));

        // A new partition has no owner, at generation 0: the writer claims it and writes through
        // the handle of its token. Closing the handle releases the ownership.
        try (PartitionWriter2 w2b = p2b.writer(p2b.claim(0).orElseThrow());
                PartitionWriter3 w3 = p3.writer(p3.claim(0).orElseThrow())) {
            // This handle needs no close: its ownership goes to another writer below.
            PartitionWriter2 w2a = p2a.writer(p2a.claim(0).orElseThrow());
            long a = w2a.insert(10.5, 3.0);
            long b = w2b.insert(-7.25, 42.0);
            System.out.printf("2D: entity %d on node A, entity %d on node B%n", a, b);

            // Batch operations: one call to the node for many entities
            long[] ids = w2a.insertAll(List.of(point(0, 0), point(1, 1), point(2, 2)));
            w2a.updateAll(List.of(entity(ids[0], 5, 5), entity(ids[1], 6, 6)));
            w2a.removeAll(new long[] {ids[2]});

            long c = w3.insert(1.0, 2.0, 3.0);
            w3.update(c, point(4.0, 5.0, 6.0));

            // The writers commit in the current tick, here through the interface shared by 2D and
            // 3D writers; the move of the tick publishes the snapshot of the tick to the readers.
            for (PartitionWriter writer : List.<PartitionWriter>of(w2a, w2b, w3)) {
                writer.commit();
            }
            space2.setTick(1);
            space3.setTick(1);

            // Everyone, the writer too, reads an acquired snapshot
            try (AcquiredSnapshot2 snapshot = p2a.acquireLatest()) {
                System.out.printf("2D: after the batch, the snapshot of tick %d of node A has %s%n",
                        snapshot.tick(), snapshot.getAll(new long[] {a, ids[0], ids[1], ids[2]}));
                // Queries: entities in a region and entities nearest to a point
                System.out.printf("2D: in the box (4,4)-(7,7): %s%n",
                        snapshot.findInRegion(new Box2(point(4, 4), point(7, 7))));
                System.out.printf("2D: in the circle of radius 1 around (10,3): %s%n",
                        snapshot.findInRegion(new Circle2(point(10, 3), 1)));
                System.out.printf("2D: the 2 nearest to (0,0): %s%n", snapshot.findNearest(point(0, 0), 2));
            }
            try (AcquiredSnapshot3 snapshot = p3.acquireLatest()) {
                System.out.printf("3D: entity %d is at %s%n", c, snapshot.get(c).orElseThrow());
            }

            // The owner of node A passes the ownership: it claims with the generation of its own
            // token and gives the new token to the next writer. The old handle cannot write any more.
            WriteToken next = p2a.claim(w2a.token().generation()).orElseThrow();
            try (PartitionWriter2 nextWriter = p2a.writer(next)) {
                try {
                    w2a.insert(30, 30);
                } catch (OwnershipChangedException e) {
                    System.out.printf("2D: the old writer of node A is rejected: %s%n", e.getMessage());
                }
                // Ticks can be skipped: here the tick moves from 1 to 3.
                nextWriter.insert(20, 20);
                nextWriter.commit();
                space2.setTick(3);
                try (AcquiredSnapshot2 latest = p2a.acquireLatest()) {
                    System.out.printf("2D: node A is at tick %d with ownership %s, the latest snapshot is of"
                            + " tick %d with %d entities%n", p2a.tick(), p2a.ownership(), latest.tick(), latest.size());
                }
            }

            // Removal is the last operation of the writer and follows the policy chosen at creation:
            // p2b must be empty, p3 discards its entities.
            w2b.remove(b);
            w2b.commit();
            w2b.removePartition();
            w3.removePartition();
            System.out.println("Removed the emptied 2D partition and the 3D partition with its entities");
        }
    }
}
