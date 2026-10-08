package badspace.api;

import badspace.common.partition.IndexConfig;
import java.util.Objects;

/**
 * Configuration of a partition, chosen when the partition is created and fixed after that.
 * New settings can be added later as new components.
 *
 * @param index the spatial index of the partition, which also sets the
 *        {@linkplain Partition#limits() limits} of the coordinates
 * @param removalPolicy what happens to the entities when the partition is removed
 */
public record PartitionConfig(IndexConfig index, RemovalPolicy removalPolicy) {

    /** Fails with NullPointerException if a component is null. */
    public PartitionConfig {
        Objects.requireNonNull(index);
        Objects.requireNonNull(removalPolicy);
    }

    /** Returns the default configuration: the linear scan as index and the REQUIRE_EMPTY removal policy. */
    public static PartitionConfig defaults() {
        return new PartitionConfig(IndexConfig.linearScan(), RemovalPolicy.REQUIRE_EMPTY);
    }

    /** Returns a copy of this configuration with the given index. */
    public PartitionConfig withIndex(IndexConfig index) {
        return new PartitionConfig(index, removalPolicy);
    }

    /** Returns a copy of this configuration with the given removal policy. */
    public PartitionConfig withRemovalPolicy(RemovalPolicy removalPolicy) {
        return new PartitionConfig(index, removalPolicy);
    }
}
