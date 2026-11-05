package badspace.service.partition;

import badspace.common.partition.Entity2;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.WriteToken;
import badspace.service.partition.snapshot.MonotonicClock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gives the benchmarks the writes of the storage of a 2D local node, one call for each batch,
 * without the commit: the node gets the writes only with its commit, but the benchmarks of
 * the writes measure the slots and the index alone, like before the writes went to the
 * commit. The node comes from {@link #newNode()}, which makes it with the factory of the
 * storages that the tests use too, and keeps the storage of its only partition. The writes
 * stay in the state of the writer of the storage until {@link #commit}, which confirms them
 * with an empty batch. This class is in the package of the service, but in the benchmark
 * module, so the code of the service does not change its visibility.
 */
public final class NodeWrites {

    // The storage of the only partition of each node of newNode(). The nodes are weak keys,
    // so the storages go away with the node of the benchmark.
    private static final Map<PartitionNode2, PartitionStorage2[]> STORAGES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private NodeWrites() {
    }

    /**
     * Returns a new local node, like {@code new LocalPartitionNode2()}, whose storage this class
     * can write. The node can have only one partition.
     */
    public static PartitionNode2 newNode() {
        PartitionStorage2[] created = new PartitionStorage2[1];
        LocalPartitionNode2 node = new LocalPartitionNode2(MonotonicClock.SYSTEM,
                (index, retainedTicks, clock, creationTick) -> {
                    if (created[0] != null) {
                        throw new IllegalStateException("The node of the benchmarks has only one partition");
                    }
                    created[0] = new PartitionStorage2(index, retainedTicks, clock, creationTick);
                    return created[0];
                });
        STORAGES.put(node, created);
        return node;
    }

    /** Adds entities to the partition of the node; the IDs must not be in the partition. */
    public static void insertAll(PartitionNode2 node, WriteToken token, List<Entity2> entities) {
        storage(node).insertAll(token.generation(), entities);
    }

    /** Sets the positions of entities of the partition of the node. */
    public static void setAll(PartitionNode2 node, WriteToken token, List<Entity2> entities) {
        storage(node).setAll(token.generation(), entities);
    }

    /** Removes entities of the partition of the node. */
    public static void removeAll(PartitionNode2 node, WriteToken token, long[] entityIds) {
        storage(node).removeAll(token.generation(), entityIds);
    }

    /** Confirms the writes made through this class. */
    public static void commit(PartitionNode2 node, WriteToken token) {
        node.commit(token, List.of());
    }

    private static PartitionStorage2 storage(PartitionNode2 node) {
        PartitionStorage2[] created = STORAGES.get(node);
        if (created == null || created[0] == null) {
            throw new IllegalArgumentException("Not a node of newNode() with a partition: " + node);
        }
        return created[0];
    }
}
