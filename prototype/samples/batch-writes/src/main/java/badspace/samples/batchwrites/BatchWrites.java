package badspace.samples.batchwrites;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.Entity2.entity;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.service.partition.LocalPartitionNode2;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Sample client: batch writes insert, update or remove many entities in one call to the node.
 * A batch is all or nothing: if one of its entities is rejected, no entity of the batch changes.
 */
public final class BatchWrites {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            // The IDs are in the same order as the positions.
            long[] ids = writer.insertAll(List.of(point(1, 1), point(2, 2), point(3, 3)));
            writer.updateAll(List.of(entity(ids[0], 10, 10), entity(ids[1], 20, 20)));
            writer.removeAll(new long[] {ids[2]});

            // The third entity is removed, so the whole batch is rejected: the first entity
            // does not move to (99,99).
            try {
                writer.updateAll(List.of(entity(ids[0], 99, 99), entity(ids[2], 30, 30)));
            } catch (NoSuchElementException e) {
                System.out.printf("Rejected batch: %s%n", e.getMessage());
            }

            writer.commit();
            space.setTick(1);
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("Snapshot of tick %d: %s%n", snapshot.tick(), snapshot.getAll(ids));
            }
        }
    }
}
