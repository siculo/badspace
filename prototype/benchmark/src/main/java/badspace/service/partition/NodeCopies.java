package badspace.service.partition;

import badspace.common.partition.PartitionId;
import badspace.service.persistence.PartitionCopy2;
import badspace.service.persistence.PartitionCopy3;

/**
 * Gives the benchmarks the autonomous copy of a snapshot that the nodes make for the saves.
 * The copy is package-private in the nodes: this class is in the same package, but in the
 * benchmark module, so the code of the service does not change its visibility.
 */
public final class NodeCopies {

    private NodeCopies() {
    }

    /** Copies the snapshot of a tick of a 2D partition, like the node does for a save. */
    public static PartitionCopy2 copy(LocalPartitionNode2 node, PartitionId partitionId, long tick) {
        return node.copy(partitionId, tick);
    }

    /** Copies the snapshot of a tick of a 3D partition, like the node does for a save. */
    public static PartitionCopy3 copy(LocalPartitionNode3 node, PartitionId partitionId, long tick) {
        return node.copy(partitionId, tick);
    }
}
