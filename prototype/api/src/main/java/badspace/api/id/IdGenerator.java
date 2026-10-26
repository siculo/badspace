package badspace.api.id;

/**
 * Generates the IDs of one kind: entity IDs or partition IDs. There must be one generator
 * for each kind of ID per API process, shared by all spaces (see {@link badspace.api.SpaceConfig}).
 *
 * <p>The IDs are unique across all partitions and spaces, also across process restarts.
 * A new generator must not reuse an ID assigned by an earlier generator of the same kind,
 * including an entity ID kept in a saved partition and loaded later. Uniqueness holds
 * within one kind of ID: an entity ID and a partition ID can have the same value.
 * Changing the generator implementation does not reset this rule. An entity ID does not
 * tell the partition. IDs have no promised order or sign: any {@code long} value is valid.
 *
 * <p>Implementations must be thread-safe.
 */
public interface IdGenerator {

    /**
     * Returns a new ID.
     *
     * @throws IllegalStateException in exceptional cases, when the generator cannot create
     *                               a new ID
     */
    long nextId();
}
