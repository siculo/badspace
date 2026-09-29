package badspace.api;

import badspace.common.PartitionId;
import badspace.common.PartitionNode2;

/** Proxy of a 2D partition that lives on a node. It has a single writer and is not thread-safe. */
public final class Partition2 implements Partition {

    private final Space2 space;
    private final PartitionNode2 node;
    private final PartitionId id;

    Partition2(Space2 space, PartitionNode2 node, PartitionId id) {
        this.space = space;
        this.node = node;
        this.id = id;
    }

    /** Adds a new entity and returns its ID. */
    public long insert(double x, double y) {
        long entityId = space.nextEntityId();
        node.insert(id, entityId, x, y);
        return entityId;
    }

    @Override
    public int size() {
        return node.size(id);
    }
}
