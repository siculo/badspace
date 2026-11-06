package badspace.service.partition;

import badspace.common.partition.WriteMode;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration of a local partition node, chosen when the node is created. New settings can
 * be added later as new components.
 * <p>
 * Unlike the configuration of a space, every component has a default, so {@link #of()} gives
 * the default node: no save directory and the {@link WriteMode#DIRECT} mode. The {@code with...}
 * methods return a copy with one component changed.
 *
 * @param saveDirectory the directory of the saves, or {@code null} if the node cannot save
 * @param writeMode how the writers send their writes to the node
 */
public record PartitionNodeConfig(Path saveDirectory, WriteMode writeMode) {

    /** Fails with NullPointerException if the write mode is null. */
    public PartitionNodeConfig {
        Objects.requireNonNull(writeMode);
    }

    /** Returns the default configuration: no save directory and the direct mode. */
    public static PartitionNodeConfig of() {
        return new PartitionNodeConfig(null, WriteMode.DIRECT);
    }

    /**
     * Returns a copy of this configuration that saves in the given directory.
     *
     * @param saveDirectory the directory of the saves, or {@code null} if the node cannot save
     */
    public PartitionNodeConfig withSaveDirectory(Path saveDirectory) {
        return new PartitionNodeConfig(saveDirectory, writeMode);
    }

    /**
     * Returns a copy of this configuration with the given write mode.
     *
     * @param writeMode how the writers send their writes to the node
     * @throws NullPointerException if the write mode is null
     */
    public PartitionNodeConfig withWriteMode(WriteMode writeMode) {
        return new PartitionNodeConfig(saveDirectory, writeMode);
    }
}
