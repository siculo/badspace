package badspace.common.partition;

import java.util.Objects;

/**
 * The reference to a save: the snapshot of a tick of a partition, which the node of the
 * partition wrote to its save destination. A save of a partition is the only one of its tick,
 * so the partition and the tick name it. The reference does not say where the node keeps the
 * save: that is a detail of the node.
 *
 * @param partitionId the partition of the saved snapshot
 * @param tick the tick of the saved snapshot
 */
public record SavedPartition(PartitionId partitionId, long tick) {

    /**
     * Fails with NullPointerException if partitionId is null, and with
     * IllegalArgumentException if tick is negative.
     */
    public SavedPartition {
        Objects.requireNonNull(partitionId);
        if (tick < 0) {
            throw new IllegalArgumentException("Tick is negative: " + tick);
        }
    }
}
