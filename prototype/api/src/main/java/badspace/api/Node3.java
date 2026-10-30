package badspace.api;

import badspace.common.partition.PartitionNode3;

/**
 * Handle of a node of a 3D space: the space returns it from
 * {@link Space3#createNode(PartitionNodeFactory3)}, and the software uses it to choose the node
 * of a partition. The handle belongs to the space that created it. It has no public ID or name,
 * and it does not move the tick of the node: only its space does. Only the space builds it.
 */
public final class Node3 {

    private final Space3 space;
    private final PartitionNode3 node;

    Node3(Space3 space, PartitionNode3 node) {
        this.space = space;
        this.node = node;
    }

    /** Returns the space that created the handle. */
    Space3 space() {
        return space;
    }

    /** Returns the node contract that the handle selects. */
    PartitionNode3 node() {
        return node;
    }
}
