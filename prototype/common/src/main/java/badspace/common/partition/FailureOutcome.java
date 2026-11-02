package badspace.common.partition;

/** What a failure left in the partition, in a {@link PartitionFailure}. */
public enum FailureOutcome {
    /**
     * The partition brought the state of the writer back to the last commit: all the writes
     * not committed are discarded, and the partition takes new writes and commits.
     */
    WRITES_DISCARDED,
    /**
     * The partition could not bring the state of the writer back to the last commit: the
     * partition needs a recovery, and it refuses writes and commits until then. The confirmed
     * snapshots are whole and can be read.
     */
    RECOVERY_REQUIRED
}
