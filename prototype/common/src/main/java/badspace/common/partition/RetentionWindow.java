package badspace.common.partition;

/**
 * The retention window of a partition: the last version and k past versions
 * before it, so k + 1 versions. k is chosen when the partition is created.
 */
public final class RetentionWindow {

    /** The default k: the partition keeps only the last version. */
    public static final int DEFAULT_RETAINED_PAST_VERSIONS = 0;

    private RetentionWindow() {
    }
}
