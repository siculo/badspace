package badspace.benchmark;

import badspace.common.partition.Entity2;
import badspace.common.partition.EntityWrite2;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.WriteMode;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.partition.PartitionNodeConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives the benchmarks the writes of a 2D local node without the commit, the
 * {@linkplain PartitionNode2#write write} of the direct mode, so the benchmarks of the writes
 * measure the slots and the index alone. The writes stay in the state of the writer of the
 * partition until {@link #commit}, which confirms them with an empty batch. The benchmarks
 * prepare the batches before the measure.
 */
final class NodeWrites {

    private NodeWrites() {
    }

    /** Returns a new local node in the direct mode, like
     * {@code new LocalPartitionNode2(PartitionNodeConfig.of())}. */
    static PartitionNode2 newNode() {
        return newNode(WriteMode.DIRECT);
    }

    /** Returns a new local node whose writers send their writes in the given mode. */
    static PartitionNode2 newNode(WriteMode writeMode) {
        return new LocalPartitionNode2(PartitionNodeConfig.of().withWriteMode(writeMode));
    }

    /** Applies a batch of changes to the state of the writer of the partition, without the commit. */
    static void apply(PartitionNode2 node, WriteToken token, List<EntityWrite2> changes) {
        node.write(token, changes);
    }

    /** Returns the changes that set each entity at its position, to prepare before the measure. */
    static List<EntityWrite2> sets(List<Entity2> entities) {
        List<EntityWrite2> changes = new ArrayList<>(entities.size());
        for (Entity2 entity : entities) {
            changes.add(EntityWrite2.set(entity));
        }
        return List.copyOf(changes);
    }

    /** Returns the changes that remove each entity, to prepare before the measure. */
    static List<EntityWrite2> removals(long[] entityIds) {
        List<EntityWrite2> changes = new ArrayList<>(entityIds.length);
        for (long entityId : entityIds) {
            changes.add(EntityWrite2.removal(entityId));
        }
        return List.copyOf(changes);
    }

    /** Confirms the writes made through this class. */
    static void commit(PartitionNode2 node, WriteToken token) {
        node.commit(token, List.of());
    }
}
