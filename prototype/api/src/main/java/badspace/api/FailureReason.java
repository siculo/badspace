package badspace.api;

import badspace.common.partition.SnapshotNotAvailableException;

/**
 * Why a snapshot set has no snapshot of a partition: the reasons of
 * {@link SnapshotNotAvailableException}, and the partition unknown to its node.
 */
public enum FailureReason {
    /** The tick is not before the tick of the partition: its snapshot is not published yet. */
    NOT_YET_PUBLISHED,
    /** The tick is older than the retention window. */
    OUT_OF_RETENTION,
    /** The partition did not exist yet at the tick. */
    BEFORE_CREATION,
    /** The partition was removed at the tick or before it. */
    AFTER_REMOVAL,
    /**
     * The node of the partition does not know it. The node cannot tell a destroyed partition
     * from one that never existed; with a handle made by the space it is a destroyed one.
     */
    UNKNOWN_PARTITION;

    /** Returns the failure reason of a snapshot that is not available. */
    static FailureReason of(SnapshotNotAvailableException.Reason reason) {
        return switch (reason) {
            case NOT_YET_PUBLISHED -> NOT_YET_PUBLISHED;
            case OUT_OF_RETENTION -> OUT_OF_RETENTION;
            case BEFORE_CREATION -> BEFORE_CREATION;
            case AFTER_REMOVAL -> AFTER_REMOVAL;
        };
    }
}
