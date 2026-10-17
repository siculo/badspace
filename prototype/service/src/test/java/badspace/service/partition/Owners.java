package badspace.service.partition;

import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.WriteToken;

/**
 * Gives the tests that are not about the ownership the token of the owner of a partition.
 * If the partition has no owner, the test takes the ownership with the current generation,
 * which discards the writes not committed: the tests call it before their first write.
 */
final class Owners {

    private Owners() {
    }

    /**
     * Returns the token of the owner of the partition, after taking the ownership if the
     * partition has none. For a partition that the node does not know, it returns a token
     * of generation 1, so the operation of the test fails like for an unknown partition.
     */
    static WriteToken owner(PartitionNode2 node, PartitionId partitionId) {
        Ownership ownership;
        try {
            ownership = node.ownership(partitionId);
        } catch (IllegalArgumentException unknown) {
            return new WriteToken(partitionId, 1);
        }
        if (ownership.owned()) {
            return new WriteToken(partitionId, ownership.generation());
        }
        return node.claim(partitionId, ownership.generation()).orElseThrow();
    }

    /** Like {@link #owner(PartitionNode2, PartitionId)}, for a 3D node. */
    static WriteToken owner(PartitionNode3 node, PartitionId partitionId) {
        Ownership ownership;
        try {
            ownership = node.ownership(partitionId);
        } catch (IllegalArgumentException unknown) {
            return new WriteToken(partitionId, 1);
        }
        if (ownership.owned()) {
            return new WriteToken(partitionId, ownership.generation());
        }
        return node.claim(partitionId, ownership.generation()).orElseThrow();
    }

    /** Returns the generation of the owner of the storage, after taking the ownership if it has none. */
    static long owner(PartitionStorage2 storage) {
        Ownership ownership = storage.ownership();
        if (ownership.owned()) {
            return ownership.generation();
        }
        return storage.claimOwnership(ownership.generation());
    }

    /** Like {@link #owner(PartitionStorage2)}, for a 3D storage. */
    static long owner(PartitionStorage3 storage) {
        Ownership ownership = storage.ownership();
        if (ownership.owned()) {
            return ownership.generation();
        }
        return storage.claimOwnership(ownership.generation());
    }
}
