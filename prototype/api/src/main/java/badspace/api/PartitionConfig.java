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
 * @param retainedTicks k: the number of retained ticks; with the tick T of the partition,
 *        readers can acquire the snapshots of the ticks from T - k to T - 1, and not before
 *        the creation of the partition; at least 1
 */
public record PartitionConfig(IndexConfig index, RemovalPolicy removalPolicy, int retainedTicks) {

    /**
     * Fails with NullPointerException if a component is null, and with
     * IllegalArgumentException if retainedTicks is less than 1.
     */
    public PartitionConfig {
        Objects.requireNonNull(index);
        Objects.requireNonNull(removalPolicy);
        if (retainedTicks < RetentionWindow.MIN_RETAINED_TICKS) {
            throw new IllegalArgumentException("Number of retained ticks is less than "
                    + RetentionWindow.MIN_RETAINED_TICKS + ": " + retainedTicks);
        }
    }

    /**
     * Returns the default configuration: the linear scan as index, the REQUIRE_EMPTY removal
     * policy and one retained tick.
     */
    public static PartitionConfig defaults() {
        return new PartitionConfig(IndexConfig.linearScan(), RemovalPolicy.REQUIRE_EMPTY,
                RetentionWindow.DEFAULT_RETAINED_TICKS);
    }

    /** Returns a copy of this configuration with the given index. */
    public PartitionConfig withIndex(IndexConfig index) {
        return new PartitionConfig(index, removalPolicy, retainedTicks);
    }

    /** Returns a copy of this configuration with the given removal policy. */
    public PartitionConfig withRemovalPolicy(RemovalPolicy removalPolicy) {
        return new PartitionConfig(index, removalPolicy, retainedTicks);
    }

    /** Returns a copy of this configuration with the given number of retained ticks. */
    public PartitionConfig withRetainedTicks(int retainedTicks) {
        return new PartitionConfig(index, removalPolicy, retainedTicks);
    }
}
