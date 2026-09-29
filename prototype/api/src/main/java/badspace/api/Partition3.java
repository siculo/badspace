package badspace.api;

import badspace.common.PartitionId;
import badspace.common.PartitionNode3;

/** Proxy of a 3D partition that lives on a node. It has a single writer and is not thread-safe. */
public final class Partition3 implements Partition {

    private final Space3 space;
    private final PartitionNode3 node;
    private final PartitionId id;

    Partition3(Space3 space, PartitionNode3 node, PartitionId id) {
        this.space = space;
        this.node = node;
        this.id = id;
    }

    /** Adds a new entity and returns its ID. */
    public long insert(double x, double y, double z) {
        long entityId = space.nextEntityId();
        node.insert(id, entityId, x, y, z);
        return entityId;
    }

    @Override
    public int size() {
        return node.size(id);
    }
}
