package badspace.api;

import badspace.api.id.IdGenerator;
import java.util.Objects;

/**
 * Configuration of a space, chosen when the space is created. New settings can be added
 * later as new components.
 * <p>
 * There is no default configuration: each ID generator must be shared by all the spaces
 * of the process, so the caller creates it once and passes it to every space.
 * Entity IDs and partition IDs are two kinds of ID: their generators are separate and
 * can give the same values.
 *
 * @param entityIds the generator of the entity IDs, shared by all the spaces of the process
 * @param partitionIds the generator of the partition IDs, shared by all the spaces of the
 *        process; a partition ID is never used again
 */
public record SpaceConfig(IdGenerator entityIds, IdGenerator partitionIds) {

    /** Fails with NullPointerException if a component is null. */
    public SpaceConfig {
        Objects.requireNonNull(entityIds);
        Objects.requireNonNull(partitionIds);
    }

    /** Returns the configuration with the given generators of entity IDs and of partition IDs. */
    public static SpaceConfig of(IdGenerator entityIds, IdGenerator partitionIds) {
        return new SpaceConfig(entityIds, partitionIds);
    }

    /** Returns a copy of this configuration with the given generator of entity IDs. */
    public SpaceConfig withEntityIds(IdGenerator entityIds) {
        return new SpaceConfig(entityIds, partitionIds);
    }

    /** Returns a copy of this configuration with the given generator of partition IDs. */
    public SpaceConfig withPartitionIds(IdGenerator partitionIds) {
        return new SpaceConfig(entityIds, partitionIds);
    }
}
