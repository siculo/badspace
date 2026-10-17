package badspace.samples.ownershiphandoff;

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
 * Sample client: a partition has at most one owner, its writer, with the write token of the
 * current generation. The generation grows at each change: a claim, a handoff to another writer,
 * a revocation by a coordinator and a release. A handle with an old token cannot write any more,
 * and closing a handle discards the writes not committed.
 */
public final class OwnershipHandoff {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2());
        Partition2 other = space.createPartition(new LocalPartitionNode2());
        System.out.printf("New partition: %s%n", partition.ownership());

        // A claim succeeds only with the current generation: of two claims with generation 0,
        // only the first one wins.
        WriteToken first = partition.claim(0).orElseThrow();
        System.out.printf("Claimed with token %s; a second claim with generation 0 gives %s%n",
                first, partition.claim(0));
        PartitionWriter2 firstWriter = partition.writer(first);
        firstWriter.insert(1, 1);
        firstWriter.commit();

        // Cooperative handoff: the owner claims with the generation of its own token and sends
        // the new token to the next writer, which makes its handle. The old handle is rejected.
        WriteToken second = partition.claim(firstWriter.token().generation()).orElseThrow();
        PartitionWriter2 secondWriter = partition.writer(second);
        tryInsert("The first writer after the handoff", firstWriter);
        // close() never fails: the ownership already went to the second writer, so it does nothing.
        firstWriter.close();
        secondWriter.insert(2, 2);
        secondWriter.commit();
        System.out.printf("After the handoff: %s%n", partition.ownership());

        // Revocation: a coordinator that thinks the owner is stuck claims with the generation it
        // reads, without asking the owner. Here it keeps the token and releases it at once.
        WriteToken revoked = partition.claim(partition.ownership().generation()).orElseThrow();
        tryInsert("The second writer after the revocation", secondWriter);
        partition.writer(revoked).close();
        System.out.printf("After the revocation and the release: %s%n", partition.ownership());

        // Closing the handle is a rollback: a writer that leaves with an exception before its
        // commit loses its writes.
        WriteToken last = partition.claim(partition.ownership().generation()).orElseThrow();
        try (PartitionWriter2 writer = partition.writer(last)) {
            writer.insert(3, 3);
            throw new IllegalStateException("the writer fails before its commit");
        } catch (IllegalStateException e) {
            System.out.printf("A writer left because %s: %s%n", e.getMessage(), partition.ownership());
        }
        space.setTick(1);
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("The snapshot of tick %d has the %d committed entities%n", snapshot.tick(),
                    snapshot.size());
        }

        // A token is of one partition only.
        try {
            other.writer(second);
        } catch (IllegalArgumentException e) {
            System.out.printf("Rejected handle: %s%n", e.getMessage());
        }
    }

    private static void tryInsert(String who, PartitionWriter2 writer) {
        try {
            writer.insert(9, 9);
        } catch (OwnershipChangedException e) {
            System.out.printf("%s is rejected: %s%n", who, e.getMessage());
        }
    }
}
