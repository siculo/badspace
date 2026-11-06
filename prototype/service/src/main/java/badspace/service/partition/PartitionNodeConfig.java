package badspace.service.partition;

import badspace.common.partition.WriteMode;
import badspace.service.partition.snapshot.MonotonicClock;
import badspace.service.persistence.NodeSaves;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration of a local partition node, chosen when the node is created. New settings can
 * be added later as new components.
 * <p>
 * Unlike the configuration of a space, every component has a default, so {@link #of()} gives
 * the default node: the system clock, no save directory and the {@link WriteMode#DIRECT} mode.
 * The {@code with...} methods return a copy with one component changed.
 *
 * @param clock the clock that measures the timeouts of the acquisitions, shared by all the
 *        partitions of the node
 * @param saves the saves of the node in its save directory, or {@code null} if the node cannot
 *        save
 * @param writeMode how the writers send their writes to the node
 */
public record PartitionNodeConfig(MonotonicClock clock, NodeSaves saves, WriteMode writeMode) {

    /** Fails with NullPointerException if the clock or the write mode is null. */
    public PartitionNodeConfig {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(writeMode);
    }

    /** Returns the default configuration: system clock, no saves and direct mode. */
    public static PartitionNodeConfig of() {
        return new PartitionNodeConfig(MonotonicClock.SYSTEM, null, WriteMode.DIRECT);
    }

    /** Returns a copy of this configuration with the given clock. */
    public PartitionNodeConfig withClock(MonotonicClock clock) {
        return new PartitionNodeConfig(clock, saves, writeMode);
    }

    /** Returns a copy of this configuration with the given saves, or {@code null} for none. */
    public PartitionNodeConfig withSaves(NodeSaves saves) {
        return new PartitionNodeConfig(clock, saves, writeMode);
    }

    /**
     * Returns a copy of this configuration that saves in the given directory, which must exist.
     *
     * @throws IllegalArgumentException if the directory does not exist
     * @throws java.io.UncheckedIOException if the directory cannot be prepared for the saves
     */
    public PartitionNodeConfig withSaveDirectory(Path directory) {
        return withSaves(new NodeSaves(directory));
    }

    /** Returns a copy of this configuration with the given write mode. */
    public PartitionNodeConfig withWriteMode(WriteMode writeMode) {
        return new PartitionNodeConfig(clock, saves, writeMode);
    }
}
