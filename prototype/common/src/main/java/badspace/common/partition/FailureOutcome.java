package badspace.common.partition;

/** What a failure left in the partition, in a {@link PartitionFailure}. */
public enum FailureOutcome {
    /**
     * The partition brought its state back to the last commit: the batch of the commit is
     * discarded, the writer discards its writes, and the partition takes new commits.
     */
    WRITES_DISCARDED,
    /**
     * The partition could not bring its state back to the last commit: the partition needs a
     * recovery, and it refuses the commits until then. The confirmed
     * snapshots are whole and can be read.
     */
    RECOVERY_REQUIRED
}
