package badspace.samples.multipartitionqueries;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.SnapshotSet2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box2;
import badspace.common.partition.IndexConfig;
import badspace.service.partition.LocalPartitionNode2;
import java.util.List;

/**
 * Sample client: queries on more partitions. Buildings that never move are in a partition with
 * a uniform grid, vehicles that move at each tick in a partition with the linear scan. A snapshot
 * set acquires the snapshots of the same tick on both, and its queries join the results. A
 * partition created later has no snapshot of the earlier ticks: the set gives it as a failure,
 * and the caller decides what to do.
 */
public final class MultiPartitionQueries {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(LocalPartitionNode2::new);
        // Two retained ticks, so the snapshot of tick 0 is still there at tick 2.
        PartitionConfig config = PartitionConfig.defaults().withRetainedTicks(2);
        Partition2 buildings = space.createPartition(node, partitionName("buildings"),
                config.withIndex(IndexConfig.uniformGrid(10)));
        Partition2 vehicles = space.createPartition(node, partitionName("vehicles"), config);

        PartitionWriter2 buildingsWriter = buildings.writer(buildings.claim(0).orElseThrow());
        buildingsWriter.insertAll(List.of(point(0, 0), point(20, 0), point(40, 0)));
        buildingsWriter.commit();
        PartitionWriter2 vehiclesWriter = vehicles.writer(vehicles.claim(0).orElseThrow());
        long car = vehiclesWriter.insert(5, 5);
        vehiclesWriter.commit();
        space.setTick(1);

        // At tick 1 the car moves, and a new partition starts.
        vehiclesWriter.update(car, point(18, 2));
        vehiclesWriter.commit();
        Partition2 drones = space.createPartition(node, partitionName("drones"));
        PartitionWriter2 dronesWriter = drones.writer(drones.claim(0).orElseThrow());
        dronesWriter.insert(19, 1);
        dronesWriter.commit();
        space.setTick(2);

        List<Partition2> partitions = List.of(buildings, vehicles, drones);
        Box2 area = new Box2(point(0, -5), point(25, 10));
        for (long tick = 0; tick <= 1; tick++) {
            try (SnapshotSet2 set = SnapshotSet2.acquire(partitions, tick)) {
                System.out.printf("Tick %d: %d snapshots, complete: %b%n", tick, set.snapshots().size(), set.isComplete());
                for (SnapshotSet2.Failure failure : set.failures()) {
                    System.out.printf("  no snapshot of %s: %s%n", failure.partition().name().value(), failure.reason());
                }
                // The entities of all the acquired snapshots, in an order that is not defined.
                System.out.printf("  in the box (0,-5)-(25,10): %s%n", set.findInRegion(area));
                // The nearest of all the partitions, sorted by distance and then by ID.
                System.out.printf("  the 2 nearest to (20,0): %s%n", set.findNearest(point(20, 0), 2));
            }
        }
        buildingsWriter.close();
        vehiclesWriter.close();
        dronesWriter.close();
    }
}
