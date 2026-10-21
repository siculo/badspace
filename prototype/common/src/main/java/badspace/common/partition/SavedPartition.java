package badspace.common.partition;

import java.util.Objects;

/**
 * The reference to a save: the snapshot of a tick of a partition, which the node of the
 * partition wrote to its save destination. A save of a partition is the only one of its tick,
 * so the name of the partition and the tick identify it, also after the end of the run that
 * wrote it. The reference does not say where the node keeps the save: that is a detail of the
 * node.
 *
 * @param name the name of the partition of the saved snapshot
 * @param tick the tick of the saved snapshot
 */
public record SavedPartition(PartitionName name, long tick) {

    /**
     * Fails with NullPointerException if name is null, and with IllegalArgumentException if
     * tick is negative.
     */
    public SavedPartition {
        Objects.requireNonNull(name);
        if (tick < 0) {
            throw new IllegalArgumentException("Tick is negative: " + tick);
        }
    }
}
