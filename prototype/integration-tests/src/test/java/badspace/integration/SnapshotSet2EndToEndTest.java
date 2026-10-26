package badspace.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.api.AcquiredSnapshot2;
import badspace.api.FailureReason;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.RemovalPolicy;
import badspace.api.SnapshotSet2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Circle2;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionName;
import badspace.common.partition.UnknownPartitionException;
import badspace.service.partition.LocalPartitionNode2;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Snapshot sets on the local nodes of the service: queries on partitions with different indexes
 * and the failures of the partitions without a snapshot of the tick. SnapshotSet2Test in the api
 * module checks the calls and the aggregation with framework mocks.
 */
class SnapshotSet2EndToEndTest {

    private static final int ENTITIES = 300;
    private static final double SIDE = 50;

    private final AtomicLong entityIds = new AtomicLong();
    private final AtomicLong partitionIds = new AtomicLong();
    private final Space2 space = new Space2(SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet));
    // Numbers the names of the partitions made by nextName(), so they are unique on every node.
    private final AtomicLong names = new AtomicLong();

    @Test
    void partitionsWithDifferentIndexesGiveTheResultsOfASinglePartition() {
        // The same entities in one partition and split by x among three partitions, each with
        // a different index and on its own node.
        Partition2 single = space.createPartition(new LocalPartitionNode2(), nextName());
        Partition2 left = space.createPartition(new LocalPartitionNode2(), nextName(),
                PartitionConfig.defaults().withIndex(IndexConfig.linearScan()));
        Partition2 middle = space.createPartition(new LocalPartitionNode2(), nextName(),
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(4)));
        Partition2 right = space.createPartition(new LocalPartitionNode2(), nextName(),
                PartitionConfig.defaults().withIndex(IndexConfig.gridQuadtree(8, 4)));
        PartitionWriter2 singleWriter = writer(single);
        PartitionWriter2 leftWriter = writer(left);
        PartitionWriter2 middleWriter = writer(middle);
        PartitionWriter2 rightWriter = writer(right);
        Random random = new Random(7);
        for (int i = 0; i < ENTITIES; i++) {
            Point2 position = new Point2(coordinate(random), coordinate(random));
            singleWriter.insert(position);
            PartitionWriter2 split = position.x() < -SIDE / 3 ? leftWriter : position.x() < SIDE / 3 ? middleWriter : rightWriter;
            split.insert(position);
        }
        for (PartitionWriter2 writer : List.of(singleWriter, leftWriter, middleWriter, rightWriter)) {
            writer.commit();
        }
        space.setTick(1);

        // The entities have different IDs in the two layouts: the results are compared by position.
        List<Region2> regions = List.of(new Box2(new Point2(-30, -10), new Point2(25, 12)),
                new Circle2(new Point2(15, -20), 18), new Box2(new Point2(-SIDE, -SIDE), new Point2(SIDE, SIDE)));
        List<Point2> points = List.of(new Point2(0, 0), new Point2(-17, 3), new Point2(16.5, 40), new Point2(90, -90));
        try (AcquiredSnapshot2 reference = single.acquire(0);
                SnapshotSet2 set = SnapshotSet2.acquire(List.of(left, middle, right), 0)) {
            assertTrue(set.isComplete());
            for (Region2 region : regions) {
                assertEquals(positionSet(reference.findInRegion(region)), positionSet(set.findInRegion(region)));
            }
            for (Point2 point : points) {
                for (int count : new int[] {0, 1, 10, ENTITIES + 1}) {
                    assertEquals(positions(reference.findNearest(point, count)), positions(set.findNearest(point, count)));
                }
            }
        }
    }

    @Test
    void failuresTellWhichPartitionsHaveNoSnapshotOfTheTick() {
        LocalPartitionNode2 node = new LocalPartitionNode2();
        Partition2 kept = space.createPartition(node, nextName(), PartitionConfig.defaults().withRetainedTicks(3));
        Partition2 shortWindow = space.createPartition(node, nextName());
        Partition2 removed = space.createPartition(node, nextName(),
                PartitionConfig.defaults().withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES));
        PartitionWriter2 keptWriter = writer(kept);
        long entityId = keptWriter.insert(1, 1);
        keptWriter.commit();
        writer(removed).removePartition();
        space.setTick(1);
        Partition2 late = space.createPartition(node, nextName());
        space.setTick(3);

        // At tick 3 the window of shortWindow has only tick 2, and late was created at tick 1.
        // The removal of removed is effective at tick 1, and with k = 1 it is then destroyed.
        try (SnapshotSet2 set = SnapshotSet2.acquire(List.of(kept, shortWindow, removed, late), 0)) {
            assertEquals(1, set.snapshots().size());
            assertEquals(List.of(shortWindow, removed, late), set.failures().stream().map(SnapshotSet2.Failure::partition).toList());
            assertEquals(FailureReason.OUT_OF_RETENTION, set.failures().get(0).reason());
            assertInstanceOf(UnknownPartitionException.class, set.failures().get(1).error());
            assertEquals(FailureReason.UNKNOWN_PARTITION, set.failures().get(1).reason());
            assertEquals(FailureReason.BEFORE_CREATION, set.failures().get(2).reason());
            assertEquals(List.of(new Entity2(entityId, new Point2(1, 1))), set.findNearest(new Point2(0, 0), 5));
        }
    }

    @Test
    void retiredPartitionHasNoSnapshotFromTheRemovalOn() {
        Partition2 retired = space.createPartition(new LocalPartitionNode2(), nextName(),
                PartitionConfig.defaults().withRetainedTicks(3).withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES));
        writer(retired).removePartition();
        space.setTick(1);
        space.setTick(2);

        // The removal is effective at tick 1. At tick 2 the snapshot of tick 0 is still in the
        // window, so the partition is retired and not destroyed.
        try (SnapshotSet2 set = SnapshotSet2.acquire(List.of(retired), 1)) {
            assertEquals(FailureReason.AFTER_REMOVAL, set.failures().getFirst().reason());
        }
        try (SnapshotSet2 set = SnapshotSet2.acquire(List.of(retired), 0)) {
            assertTrue(set.isComplete());
        }
    }

    @Test
    void tickNotYetPublishedIsAFailure() {
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), nextName());
        space.setTick(1);
        try (SnapshotSet2 set = SnapshotSet2.acquire(List.of(partition), 1)) {
            assertEquals(FailureReason.NOT_YET_PUBLISHED, set.failures().getFirst().reason());
        }
    }

    private static double coordinate(Random random) {
        return (random.nextDouble() * 2 - 1) * SIDE;
    }

    private static List<Point2> positions(List<Entity2> entities) {
        List<Point2> result = new ArrayList<>(entities.size());
        for (Entity2 entity : entities) {
            result.add(entity.position());
        }
        return result;
    }

    private static Set<Point2> positionSet(List<Entity2> entities) {
        Set<Point2> result = new HashSet<>(positions(entities));
        assertEquals(entities.size(), result.size());
        return result;
    }

    /** Claims the new partition and returns the writer handle of its token. */
    private static PartitionWriter2 writer(Partition2 partition) {
        return partition.writer(partition.claim(0).orElseThrow());
    }

    /** Returns a new partition name: "p" and a number, unique in the test. */
    private PartitionName nextName() {
        return new PartitionName("p" + names.incrementAndGet());
    }
}
