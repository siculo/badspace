package badspace.common.partition;

/** The operation on a partition that failed, in a {@link PartitionFailure}. */
public enum PartitionOperation {
    /** A commit of the owner: the application of its batch of writes, or the preparation of the snapshot. */
    COMMIT,
    /** A claim of the ownership, which discards the writes not committed. */
    CLAIM,
    /** A release of the ownership, which discards the writes not committed. */
    RELEASE,
    /** A rollback of the owner, which discards the writes not committed. */
    ROLLBACK
}
