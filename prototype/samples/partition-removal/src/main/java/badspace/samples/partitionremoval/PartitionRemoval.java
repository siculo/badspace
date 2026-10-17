package badspace.samples.partitionremoval;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.RemovalPolicy;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.partition.LocalPartitionNode2;

/**
 * Sample client: the removal of a partition is the last operation of its writer and follows the
 * policy chosen at creation. A REQUIRE_EMPTY partition is removed only when its last commit has
 * no entities; a DISCARD_ENTITIES partition is removed with its entities. The removal becomes
 * effective at the next move of the tick, and readers still read the snapshots of the ticks
 * before it: those they acquired, and those in the retention window. The node destroys the
 * partition when no snapshot is left.
 */
public final class PartitionRemoval {

    private static final int RETAINED_TICKS = 2;

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        LocalPartitionNode2 node = new LocalPartitionNode2();
        Partition2 empty = space.createPartition(node);
        Partition2 discarded = space.createPartition(node, PartitionConfig.defaults()
                .withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES)
                .withRetainedTicks(RETAINED_TICKS));

        // REQUIRE_EMPTY looks at the last commit: the writer removes the entities and commits first.
        try (PartitionWriter2 writer = empty.writer(empty.claim(0).orElseThrow())) {
            long entityId = writer.insert(1, 1);
            writer.commit();
            try {
                writer.removePartition();
            } catch (IllegalStateException e) {
                System.out.printf("Rejected removal: %s%n", e.getMessage());
            }
            writer.remove(entityId);
            writer.commit();
            writer.removePartition();
            System.out.println("The empty partition is removed at the next move of the tick");
        }

        try (PartitionWriter2 writer = discarded.writer(discarded.claim(0).orElseThrow())) {
            writer.insert(2, 2);
            writer.insert(3, 3);
            writer.commit();
            space.setTick(1);
            AcquiredSnapshot2 kept = discarded.acquireLatest();

            // DISCARD_ENTITIES removes the partition with its entities. After the removal the
            // writer cannot write, commit or remove again, and nobody can claim the partition.
            writer.removePartition();
            try {
                writer.insert(4, 4);
            } catch (IllegalStateException e) {
                System.out.printf("Rejected write: %s%n", e.getMessage());
            }
            try {
                discarded.claim(discarded.ownership().generation());
            } catch (IllegalStateException e) {
                System.out.printf("Rejected claim: %s%n", e.getMessage());
            }

            // The removal becomes effective at tick 2: snapshots of the ticks before it can still
            // be acquired while they are in the window, the snapshot of tick 2 never exists.
            space.setTick(2);
            tryAcquire(discarded, 1);
            tryAcquire(discarded, 2);
            space.setTick(3);
            tryAcquire(discarded, 1);

            // At tick 4 no tick before the removal is in the window, but the reader still keeps
            // its snapshot: the node keeps the partition until the release.
            space.setTick(4);
            tryAcquire(discarded, 1);
            System.out.printf("The kept snapshot of tick %d still has %d entities%n", kept.tick(), kept.size());
            kept.close();
            try {
                discarded.tick();
            } catch (IllegalArgumentException e) {
                System.out.printf("After the release the partition is destroyed: %s%n", e.getMessage());
            }
        }
    }

    private static void tryAcquire(Partition2 partition, long tick) {
        try (AcquiredSnapshot2 snapshot = partition.acquire(tick)) {
            System.out.printf("At tick %d the snapshot of tick %d has %d entities%n", partition.tick(),
                    snapshot.tick(), snapshot.size());
        } catch (SnapshotNotAvailableException e) {
            System.out.printf("At tick %d the snapshot of tick %d is not available: %s%n", partition.tick(), tick,
                    e.reason());
        }
    }
}
