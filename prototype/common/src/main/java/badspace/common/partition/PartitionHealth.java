package badspace.common.partition;

/**
 * The health of a partition: whether the state of the writer is reliable. It is separate
 * from the life cycle of the partition and from its ownership. A partition is healthy when it
 * is created. After a failure whose restore did not work, it needs a recovery: it refuses
 * the commits, while its confirmed snapshots stay readable and the move of the tick
 * publishes them as usual. A rollback of the owner, or a claim or a release of the ownership,
 * tries the restore again, and the partition is healthy again when it works.
 */
public sealed interface PartitionHealth {

    /** The health of a partition whose state of the writer is reliable. */
    PartitionHealth HEALTHY = new Healthy();

    /** The state of the partition is reliable: the partition takes commits. */
    record Healthy() implements PartitionHealth {
    }

    /**
     * The state of the writer is not reliable: the partition needs a recovery.
     *
     * @param failure the report of the failure, or null if the partition could not build it,
     *        for example because the memory ended
     */
    record RecoveryRequired(PartitionFailure failure) implements PartitionHealth {

        /** A recovery without a report, which the partition can set without allocations. */
        public static final RecoveryRequired UNREPORTED = new RecoveryRequired(null);
    }
}
