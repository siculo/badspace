package badspace.common.partition;

/** The operation on a partition that failed, in a {@link PartitionFailure}. */
public enum PartitionOperation {
    /** A write of the owner: insertion, change, removal or write of metadata. */
    WRITE,
    /** A commit of the owner. */
    COMMIT,
    /** A claim of the ownership, which discards the writes not committed. */
    CLAIM,
    /** A release of the ownership, which discards the writes not committed. */
    RELEASE,
    /** A rollback of the owner, which discards the writes not committed. */
    ROLLBACK
}
