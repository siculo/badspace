package badspace.common.partition;

/**
 * The retention window of a partition: with the tick T of the partition and its creation
 * tick C, the snapshots of the ticks from max(T - k, C) to T - 1. They are the only
 * snapshots that a reader can acquire. k, the number of retained ticks, is chosen when
 * the partition is created.
 */
public final class RetentionWindow {

    /** The default k: only the snapshot of the tick before the current one. */
    public static final int DEFAULT_RETAINED_TICKS = 1;
    /** The least k: the snapshot of the tick before the current one is always kept. */
    public static final int MIN_RETAINED_TICKS = 1;

    private RetentionWindow() {
    }
}
