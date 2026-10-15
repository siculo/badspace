package badspace.common.partition;

/**
 * The retention window of a partition: the last snapshot and k past snapshots
 * before it, so k + 1 snapshots. k is chosen when the partition is created.
 */
public final class RetentionWindow {

    /** The default k: the partition keeps only the last snapshot. */
    public static final int DEFAULT_RETAINED_PAST_SNAPSHOTS = 0;

    private RetentionWindow() {
    }
}
