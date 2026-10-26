package badspace.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.api.AcquiredSnapshot3;
import badspace.api.Partition3;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter3;
import badspace.api.RemovalPolicy;
import badspace.api.SnapshotSet3;
import badspace.api.Space3;
import badspace.api.SpaceConfig;
import badspace.common.geometry.Box3;
import badspace.common.geometry.Sphere3;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionName;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.UnknownPartitionException;
import badspace.service.partition.LocalPartitionNode3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Snapshot sets on the local nodes of the service: queries on partitions with different indexes
 * and the failures of the partitions without a snapshot of the tick. SnapshotSet3Test in the api
 * module checks the calls and the aggregation with framework mocks.
 */
class SnapshotSet3EndToEndTest {

    private static final int ENTITIES = 300;
    private static final double SIDE = 50;

    private final AtomicLong entityIds = new AtomicLong();
    private final AtomicLong partitionIds = new AtomicLong();
    private final Space3 space = new Space3(SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet));
    // Numbers the names of the partitions made by nextName(), so they are unique on every node.
    private final AtomicLong names = new AtomicLong();

    @Test
    void partitionsWithDifferentIndexesGiveTheResultsOfASinglePartition() {
        // The same entities in one partition and split by x among three partitions, each with
        // a different index and on its own node.
        Partition3 single = space.createPartition(new LocalPartitionNode3(), nextName());
        Partition3 left = space.createPartition(new LocalPartitionNode3(), nextName(),
                PartitionConfig.defaults().withIndex(IndexConfig.linearScan()));
        Partition3 middle = space.createPartition(new LocalPartitionNode3(), nextName(),
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(4)));
        Partition3 right = space.createPartition(new LocalPartitionNode3(), nextName(),
                PartitionConfig.defaults().withIndex(IndexConfig.gridQuadtree(8, 4)));
        PartitionWriter3 singleWriter = writer(single);
        PartitionWriter3 leftWriter = writer(left);
        PartitionWriter3 middleWriter = writer(middle);
        PartitionWriter3 rightWriter = writer(right);
        Random random = new Random(7);
        for (int i = 0; i < ENTITIES; i++) {
            Point3 position = new Point3(coordinate(random), coordinate(random), coordinate(random));
            singleWriter.insert(position);
            PartitionWriter3 split = position.x() < -SIDE / 3 ? leftWriter : position.x() < SIDE / 3 ? middleWriter : rightWriter;
            split.insert(position);
        }
        for (PartitionWriter3 writer : List.of(singleWriter, leftWriter, middleWriter, rightWriter)) {
            writer.commit();
        }
        space.setTick(1);

        // The entities have different IDs in the two layouts: the results are compared by position.
        List<Region3> regions = List.of(new Box3(new Point3(-30, -10, -10), new Point3(25, 12, 12)),
                new Sphere3(new Point3(15, -20, -20), 18), new Box3(new Point3(-SIDE, -SIDE, -SIDE), new Point3(SIDE, SIDE, SIDE)));
        List<Point3> points = List.of(new Point3(0, 0, 0), new Point3(-17, 3, 3), new Point3(16.5, 40, 40), new Point3(90, -90, -90));
        try (AcquiredSnapshot3 reference = single.acquire(0);
                SnapshotSet3 set = SnapshotSet3.acquire(List.of(left, middle, right), 0)) {
            assertTrue(set.isComplete());
            for (Region3 region : regions) {
                assertEquals(positionSet(reference.findInRegion(region)), positionSet(set.findInRegion(region)));
            }
            for (Point3 point : points) {
                for (int count : new int[] {0, 1, 10, ENTITIES + 1}) {
                    assertEquals(positions(reference.findNearest(point, count)), positions(set.findNearest(point, count)));
                }
            }
        }
    }

    @Test
    void failuresTellWhichPartitionsHaveNoSnapshotOfTheTick() {
        LocalPartitionNode3 node = new LocalPartitionNode3();
        Partition3 kept = space.createPartition(node, nextName(), PartitionConfig.defaults().withRetainedTicks(3));
        Partition3 shortWindow = space.createPartition(node, nextName());
        Partition3 removed = space.createPartition(node, nextName(),
                PartitionConfig.defaults().withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES));
        PartitionWriter3 keptWriter = writer(kept);
        long entityId = keptWriter.insert(1, 1, 1);
        keptWriter.commit();
        writer(removed).removePartition();
        space.setTick(1);
        Partition3 late = space.createPartition(node, nextName());
        space.setTick(3);

        // At tick 3 the window of shortWindow has only tick 2, and late was created at tick 1.
        // The removal of removed is effective at tick 1, and with k = 1 it is then destroyed.
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(kept, shortWindow, removed, late), 0)) {
            assertEquals(1, set.snapshots().size());
            assertEquals(List.of(shortWindow, removed, late), set.failures().stream().map(SnapshotSet3.Failure::partition).toList());
            assertEquals(Optional.of(Reason.OUT_OF_RETENTION), set.failures().get(0).reason());
            assertInstanceOf(UnknownPartitionException.class, set.failures().get(1).error());
            assertEquals(Optional.empty(), set.failures().get(1).reason());
            assertEquals(Optional.of(Reason.BEFORE_CREATION), set.failures().get(2).reason());
            assertEquals(List.of(new Entity3(entityId, new Point3(1, 1, 1))), set.findNearest(new Point3(0, 0, 0), 5));
        }
    }

    @Test
    void tickNotYetPublishedIsAFailure() {
        Partition3 partition = space.createPartition(new LocalPartitionNode3(), nextName());
        space.setTick(1);
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(partition), 1)) {
            assertEquals(Optional.of(Reason.NOT_YET_PUBLISHED), set.failures().getFirst().reason());
        }
    }

    private static double coordinate(Random random) {
        return (random.nextDouble() * 2 - 1) * SIDE;
    }

    private static List<Point3> positions(List<Entity3> entities) {
        List<Point3> result = new ArrayList<>(entities.size());
        for (Entity3 entity : entities) {
            result.add(entity.position());
        }
        return result;
    }

    private static Set<Point3> positionSet(List<Entity3> entities) {
        Set<Point3> result = new HashSet<>(positions(entities));
        assertEquals(entities.size(), result.size());
        return result;
    }

    /** Claims the new partition and returns the writer handle of its token. */
    private static PartitionWriter3 writer(Partition3 partition) {
        return partition.writer(partition.claim(0).orElseThrow());
    }

    /** Returns a new partition name: "p" and a number, unique in the test. */
    private PartitionName nextName() {
        return new PartitionName("p" + names.incrementAndGet());
    }
}
