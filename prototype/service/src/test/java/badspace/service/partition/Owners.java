package badspace.service.partition;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.PendingWrites2;
import badspace.common.partition.PendingWrites3;
import badspace.common.partition.WriteToken;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Gives the tests that are not about the ownership the token of the owner of a partition.
 * If the partition has no owner, the test takes the ownership with the current generation,
 * which discards the writes not committed: the tests call it before their first write. It
 * also gives the tests the {@link PendingWrites2} of a token, the same at each call, so a
 * test can write and commit with the token that it finds each time.
 */
final class Owners {

    // The pending writes of the tests, one for each node and token. The nodes are weak keys,
    // so the writes go away with the node of the test.
    private static final Map<PartitionNode2, Map<WriteToken, PendingWrites2>> WRITES_2 =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<PartitionNode3, Map<WriteToken, PendingWrites3>> WRITES_3 =
            Collections.synchronizedMap(new WeakHashMap<>());

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

    /** Returns the pending writes of the owner of the partition, after taking the ownership if it has none. */
    static PendingWrites2 writes(PartitionNode2 node, PartitionId partitionId) {
        return writes(node, owner(node, partitionId));
    }

    /**
     * Returns the pending writes of the token, the same at each call. For a partition that the
     * node does not know, the writes check the largest limits, so the commit fails like for an
     * unknown partition.
     */
    static PendingWrites2 writes(PartitionNode2 node, WriteToken token) {
        return WRITES_2.computeIfAbsent(node, n -> new ConcurrentHashMap<>())
                .computeIfAbsent(token, t -> new PendingWrites2(node, t, limits(() -> node.limits(t.partitionId()))));
    }

    /** Like {@link #writes(PartitionNode2, PartitionId)}, for a 3D node. */
    static PendingWrites3 writes(PartitionNode3 node, PartitionId partitionId) {
        return writes(node, owner(node, partitionId));
    }

    /** Like {@link #writes(PartitionNode2, WriteToken)}, for a 3D node. */
    static PendingWrites3 writes(PartitionNode3 node, WriteToken token) {
        return WRITES_3.computeIfAbsent(node, n -> new ConcurrentHashMap<>())
                .computeIfAbsent(token, t -> new PendingWrites3(node, t, limits(() -> node.limits(t.partitionId()))));
    }

    private static CoordinateLimits limits(Supplier<CoordinateLimits> ofTheNode) {
        try {
            return ofTheNode.get();
        } catch (IllegalArgumentException unknown) {
            return IndexConfig.linearScan().limits();
        }
    }
}
