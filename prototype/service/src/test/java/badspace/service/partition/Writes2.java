package badspace.service.partition;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.WriteBuffer2;
import badspace.common.partition.WriteToken;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The writes of the tests of a 2D node, kept like the writer handle of the API keeps them: a
 * buffer for each token, with the positions checked against the limits at once, and one call
 * to the node at the commit. A rollback discards the buffer of the token before it goes to
 * the node. Each token is used by one thread at a time; different threads can use different
 * tokens.
 */
final class Writes2 {

    // One instance for each node: the tests of more nodes can have the same token on each.
    private static final Map<PartitionNode2, Writes2> BY_NODE = new ConcurrentHashMap<>();

    private final PartitionNode2 node;
    private final Map<WriteToken, WriteBuffer2> buffers = new ConcurrentHashMap<>();

    private Writes2(PartitionNode2 node) {
        this.node = node;
    }

    /** Returns the writes of the node. */
    static Writes2 of(PartitionNode2 node) {
        return BY_NODE.computeIfAbsent(node, Writes2::new);
    }

    void insertAll(WriteToken token, List<Entity2> entities) {
        setAll(token, entities);
    }

    void setAll(WriteToken token, List<Entity2> entities) {
        CoordinateLimits limits = node.limits(token.partitionId());
        for (Entity2 entity : entities) {
            if (!limits.contains(entity.position())) {
                throw new IllegalArgumentException("Position outside the limits: " + entity);
            }
        }
        for (Entity2 entity : entities) {
            buffer(token).set(entity);
        }
    }

    void removeAll(WriteToken token, long[] entityIds) {
        for (long entityId : entityIds) {
            buffer(token).remove(entityId);
        }
    }

    void updateMetadataAll(WriteToken token, List<MetadataWrite> writes) {
        for (MetadataWrite write : writes) {
            buffer(token).write(write);
        }
    }

    /** Sends the writes of the token to the node; if the commit fails, the writes stay. */
    void commit(WriteToken token) {
        WriteBuffer2 buffer = buffer(token);
        node.commit(token, buffer.changes());
        buffer.clear();
    }

    void rollback(WriteToken token) {
        buffer(token).clear();
        node.rollback(token);
    }


    private WriteBuffer2 buffer(WriteToken token) {
        return buffers.computeIfAbsent(token, t -> new WriteBuffer2());
    }
}
