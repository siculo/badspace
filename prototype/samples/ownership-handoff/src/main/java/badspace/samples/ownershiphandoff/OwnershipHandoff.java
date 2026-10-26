package badspace.samples.ownershiphandoff;

import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode2;

/**
 * Sample client: the owner of a partition passes the ownership to another writer. The claim with
 * the generation of the current token gives a new token, and the handle with the old token cannot
 * write any more. The two writers are on one thread, one after the other.
 */
public final class OwnershipHandoff {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"));

        PartitionWriter2 first = partition.writer(partition.claim(0).orElseThrow());
        first.insert(1, 1);
        first.commit();
        System.out.printf("First writer: %s%n", partition.ownership());

        // The owner claims with the generation of its own token and gives the new token to the
        // next writer, which makes its handle.
        WriteToken token = partition.claim(first.token().generation()).orElseThrow();
        try (PartitionWriter2 second = partition.writer(token)) {
            System.out.printf("Second writer: %s%n", partition.ownership());
            try {
                first.insert(2, 2);
            } catch (OwnershipChangedException e) {
                System.out.printf("The first writer is rejected: %s%n", e.getMessage());
            }
            // close() of the old handle does nothing: the ownership is not its own any more.
            first.close();

            second.insert(3, 3);
            second.commit();
        }
        space.setTick(1);

        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Snapshot of tick %d: %d entities, one from each writer%n", snapshot.tick(),
                    snapshot.size());
        }
    }
}
