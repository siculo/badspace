package badspace.api;

import badspace.common.partition.PartitionNode2;

/**
 * Handle of a node of a 2D space: the space returns it from
 * {@link Space2#createNode(PartitionNodeFactory2)}, and the software uses it to choose the node
 * of a partition. The handle belongs to the space that created it. It has no public ID or name,
 * and it does not move the tick of the node: only its space does. Only the space builds it.
 */
public final class Node2 {

    private final Space2 space;
    private final PartitionNode2 node;

    Node2(Space2 space, PartitionNode2 node) {
        this.space = space;
        this.node = node;
    }

    /** Returns the space that created the handle. */
    Space2 space() {
        return space;
    }

    /** Returns the node contract that the handle selects. */
    PartitionNode2 node() {
        return node;
    }
}
