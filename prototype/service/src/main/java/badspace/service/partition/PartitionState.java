package badspace.service.partition;

/** The states of a partition during its removal. */
enum PartitionState {
    /** The partition accepts writes, commits and acquisitions. */
    ACTIVE,
    /**
     * The writer asked for the removal: no more writes or commits. The partition still exists
     * in the current tick, and the next move of the tick makes the removal effective.
     */
    REMOVAL_PENDING,
    /**
     * The removal is effective since the removal tick. The snapshots of the ticks before it
     * can still be acquired while they are in the retention window.
     */
    RETIRED,
    /** No tick of the partition is in the window and nobody uses its snapshots: it is gone. */
    DESTROYED
}
