package badspace.common.partition;

import java.util.Objects;

/**
 * The node does not know the partition: it was never created on the node, or it is already
 * destroyed. Partition IDs are never used again, so the node cannot tell the two cases apart.
 * Through a handle of the API the partition always existed, so for the holder of the handle
 * it means that the partition is destroyed.
 */
public final class UnknownPartitionException extends IllegalArgumentException {

    private final PartitionId partitionId;

    /** Creates the exception for the given partition. Fails with NullPointerException if partitionId is null. */
    public UnknownPartitionException(PartitionId partitionId) {
        super("Unknown partition: " + Objects.requireNonNull(partitionId));
        this.partitionId = partitionId;
    }

    /** Returns the ID of the partition that the node does not know. */
    public PartitionId partitionId() {
        return partitionId;
    }
}
