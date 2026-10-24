package badspace.samples.snapshotreads;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Circle2;
import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.service.partition.LocalPartitionNode2;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample client: reads and queries an acquired snapshot of a partition with a grid of entities.
 * A snapshot is the partition at the end of a tick and never changes: a reader that keeps it
 * does not see the commits that come after it.
 */
public final class SnapshotReads {

    private static final int GRID_SIDE = 5;
    private static final double GRID_STEP = 10;

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"),
                PartitionConfig.defaults().withIndex(IndexConfig.gridQuadtree(16)));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            // A grid of 5 x 5 entities, from (0,0) to (40,40).
            List<Point2> positions = new ArrayList<>();
            for (int i = 0; i < GRID_SIDE; i++) {
                for (int j = 0; j < GRID_SIDE; j++) {
                    positions.add(point(i * GRID_STEP, j * GRID_STEP));
                }
            }
            long[] entityIds = writer.insertAll(positions);
            writer.commit();
            space.setTick(1);

            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("Snapshot of tick %d with %d entities%n", snapshot.tick(), snapshot.size());
                // Reads by ID: getAll skips the IDs that are not in the snapshot.
                System.out.printf("Entity %d is at %s%n", entityIds[0], snapshot.get(entityIds[0]).orElseThrow());
                System.out.printf("Two entities and an unknown ID: %s%n",
                        snapshot.getAll(new long[] {entityIds[1], entityIds[2], -1}));
                // Queries: the border of the region is inside, and the order of the result is not defined.
                System.out.printf("In the box (5,5)-(20,20): %s%n",
                        snapshot.findInRegion(new Box2(point(5, 5), point(20, 20))));
                System.out.printf("In the circle of radius 10 around (40,40): %s%n",
                        snapshot.findInRegion(new Circle2(point(40, 40), 10)));
                // The nearest entities are sorted by distance, then by ID.
                System.out.printf("The 3 nearest to (12,12): %s%n", snapshot.findNearest(point(12, 12), 3));

                // The writer moves all the entities and the tick moves on: the acquired
                // snapshot still has the old positions, a new acquisition has the new ones.
                List<Entity2> moved = new ArrayList<>();
                for (int i = 0; i < entityIds.length; i++) {
                    moved.add(new Entity2(entityIds[i], positions.get(i).add(100, 0)));
                }
                writer.updateAll(moved);
                writer.commit();
                space.setTick(2);
                try (AcquiredSnapshot2 latest = partition.acquireLatest()) {
                    System.out.printf("Entity %d: %s in the snapshot of tick %d, %s in the snapshot of tick %d%n",
                            entityIds[0], snapshot.get(entityIds[0]).orElseThrow(), snapshot.tick(),
                            latest.get(entityIds[0]).orElseThrow(), latest.tick());
                }
            }
        }
    }
}
