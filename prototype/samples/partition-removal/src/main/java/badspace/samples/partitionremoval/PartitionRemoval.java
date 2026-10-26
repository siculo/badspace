package badspace.samples.partitionremoval;

import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.RemovalPolicy;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.service.partition.LocalPartitionNode2;

/**
 * Sample client: the writer removes its partition as its last operation, with the policy chosen
 * at the creation. A REQUIRE_EMPTY partition is removed only if its last commit has no entities;
 * a DISCARD_ENTITIES partition is removed with its entities. The removal becomes effective at the
 * next move of the tick: the partition still exists for the ticks before it, so readers can
 * acquire their snapshots while they are in the retention window. Then the node destroys the
 * partition.
 */
public final class PartitionRemoval {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        LocalPartitionNode2 node = new LocalPartitionNode2();
        Partition2 empty = space.createPartition(node, partitionName("empty"));
        Partition2 discarded = space.createPartition(node, partitionName("discarded"),
                PartitionConfig.defaults().withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES));

        // REQUIRE_EMPTY, the default, looks at the last commit: the writer removes the entities
        // and commits before the removal.
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
        }

        // DISCARD_ENTITIES removes the partition with the entities of its last commit.
        try (PartitionWriter2 writer = discarded.writer(discarded.claim(0).orElseThrow())) {
            writer.insert(2, 2);
            writer.insert(3, 3);
            writer.commit();
            writer.removePartition();
        }

        // The removals become effective at tick 1. With one retained tick, the snapshot of tick 0
        // is still in the window.
        space.setTick(1);
        try (AcquiredSnapshot2 snapshot = discarded.acquire(0)) {
            System.out.printf("At tick 1 the snapshot of tick %d of %s has %d entities%n", snapshot.tick(),
                    discarded.name().value(), snapshot.size());
        }

        // At tick 2 no snapshot is left, and the node destroys the two partitions: their names
        // are free again.
        space.setTick(2);
        Partition2 again = space.createPartition(node, partitionName("discarded"));
        System.out.printf("At tick 2 a new partition %s is created%n", again.name().value());
    }
}
