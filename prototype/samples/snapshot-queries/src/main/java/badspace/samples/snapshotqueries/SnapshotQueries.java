package badspace.samples.snapshotqueries;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Circle2;
import badspace.service.partition.LocalPartitionNode2;
import java.util.List;

/**
 * Sample client: the reads of an acquired snapshot. Reads by ID give the position of known
 * entities; queries find the entities in a box, in a circle, or nearest to a point. The
 * border of a region is inside it.
 */
public final class SnapshotQueries {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"));

        long[] ids;
        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            ids = writer.insertAll(List.of(point(0, 0), point(10, 0), point(0, 10), point(10, 10), point(50, 50)));
            writer.commit();
        }
        space.setTick(1);

        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Entity %d is at %s%n", ids[0], snapshot.get(ids[0]).orElseThrow());
            // getAll skips the IDs that are not in the snapshot, here -1.
            System.out.printf("Two entities and an unknown ID: %s%n", snapshot.getAll(new long[] {ids[1], -1, ids[4]}));
            // The order of the entities of a region is not defined.
            System.out.printf("In the box (0,0)-(10,5): %s%n",
                    snapshot.findInRegion(new Box2(point(0, 0), point(10, 5))));
            System.out.printf("In the circle of radius 10 around (0,0): %s%n",
                    snapshot.findInRegion(new Circle2(point(0, 0), 10)));
            // The nearest entities are sorted by distance, then by ID.
            System.out.printf("The 2 nearest to (40,40): %s%n", snapshot.findNearest(point(40, 40), 2));
        }
    }
}
