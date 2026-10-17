package badspace.samples.writesandcommits;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.Entity2.entity;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.IndexConfig;
import badspace.service.partition.LocalPartitionNode2;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Sample client: the writer of a partition claims it and writes through the handle of its
 * token, with single and batch writes. A batch that fails changes nothing. The commits confirm
 * the writes in the current tick, and the move of the tick publishes the snapshot of the tick:
 * only then can readers, and the writer too, read it.
 */
public final class WritesAndCommits {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        // A cell size of 1 gives limits of 2^30 on each axis.
        Partition2 partition = space.createPartition(new LocalPartitionNode2(),
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(1)));

        // A new partition has no owner, at generation 0. Closing the handle releases the ownership.
        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            // Single writes are batches of one entity; batches make one call to the node.
            long a = writer.insert(1, 1);
            long[] ids = writer.insertAll(List.of(point(10, 10), point(20, 20), point(30, 30)));
            writer.update(a, point(2, 2));
            writer.updateAll(List.of(entity(ids[0], 11, 11), entity(ids[1], 21, 21)));
            writer.removeAll(new long[] {ids[2]});
            long[] all = {a, ids[0], ids[1], ids[2]};

            // A batch is all-or-nothing: an unknown ID or a position outside the limits rejects
            // the whole batch, and no entity moves.
            try {
                writer.updateAll(List.of(entity(a, 3, 3), entity(ids[2], 31, 31)));
            } catch (NoSuchElementException e) {
                System.out.printf("Rejected batch, entity %d does not move: %s%n", a, e.getMessage());
            }
            try {
                writer.insertAll(List.of(point(40, 40), point(2e9, 0)));
            } catch (IllegalArgumentException e) {
                System.out.printf("Rejected batch outside the limits %s: %s%n", partition.limits(), e.getMessage());
            }

            // The commit confirms the writes in tick 0, and the move to tick 1 publishes them.
            writer.commit();
            space.setTick(1);
            show(partition, all);

            // In one tick the last commit wins: readers never see the state of the first commit.
            // The writes after the last commit carry over to the next tick.
            writer.update(a, point(5, 5));
            writer.commit();
            writer.update(a, point(6, 6));
            writer.commit();
            long late = writer.insert(50, 50);
            space.setTick(2);
            show(partition, new long[] {a, late});

            // A commit with no writes is valid: it confirms the entity inserted after the last
            // commit of tick 1.
            writer.commit();
            space.setTick(3);
            show(partition, new long[] {a, late});
        }
    }

    private static void show(Partition2 partition, long[] entityIds) {
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Snapshot of tick %d, %d entities: %s%n", snapshot.tick(), snapshot.size(),
                    snapshot.getAll(entityIds));
        }
    }
}
