package badspace.common.partition;

/** The operation on a partition that failed, in a {@link PartitionFailure}. */
public enum PartitionOperation {
    /** A commit of the owner: the application of its batch of writes, or the preparation of the snapshot. */
    COMMIT,
    /** A claim of the ownership, which restores the last commit. */
    CLAIM,
    /** A release of the ownership, which restores the last commit. */
    RELEASE,
    /** A rollback of the owner, which restores the last commit. */
    ROLLBACK
}
