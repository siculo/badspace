package badspace.api;

import badspace.common.partition.IndexConfig;
import badspace.common.partition.RetentionWindow;
import java.util.Objects;

/**
 * Configuration of a partition, chosen when the partition is created and fixed after that.
 * New settings can be added later as new components.
 *
 * @param index the spatial index of the partition, which also sets the
 *        {@linkplain Partition#limits() limits} of the coordinates
 * @param removalPolicy what happens to the entities when the partition is removed
 * @param retainedPastVersions k: the number of past versions that the partition keeps
 *        besides the last one, so it keeps k + 1 versions; 0 keeps only the last one
 */
public record PartitionConfig(IndexConfig index, RemovalPolicy removalPolicy, int retainedPastVersions) {

    /**
     * Fails with NullPointerException if a component is null, and with
     * IllegalArgumentException if retainedPastVersions is negative.
     */
    public PartitionConfig {
        Objects.requireNonNull(index);
        Objects.requireNonNull(removalPolicy);
        if (retainedPastVersions < 0) {
            throw new IllegalArgumentException("Negative number of retained past versions: " + retainedPastVersions);
        }
    }

    /**
     * Returns the default configuration: the linear scan as index, the REQUIRE_EMPTY removal
     * policy and no past versions.
     */
    public static PartitionConfig defaults() {
        return new PartitionConfig(IndexConfig.linearScan(), RemovalPolicy.REQUIRE_EMPTY,
                RetentionWindow.DEFAULT_RETAINED_PAST_VERSIONS);
    }

    /** Returns a copy of this configuration with the given index. */
    public PartitionConfig withIndex(IndexConfig index) {
        return new PartitionConfig(index, removalPolicy, retainedPastVersions);
    }

    /** Returns a copy of this configuration with the given removal policy. */
    public PartitionConfig withRemovalPolicy(RemovalPolicy removalPolicy) {
        return new PartitionConfig(index, removalPolicy, retainedPastVersions);
    }

    /** Returns a copy of this configuration with the given number of retained past versions. */
    public PartitionConfig withRetainedPastVersions(int retainedPastVersions) {
        return new PartitionConfig(index, removalPolicy, retainedPastVersions);
    }
}
