package badspace.api.id;

/**
 * Generates the IDs of one kind: entity IDs or partition IDs. There must be one generator
 * for each kind of ID per API process, shared by all spaces (see {@link badspace.api.SpaceConfig}).
 *
 * <p>The IDs are unique across all partitions and spaces, and the generator never returns
 * the same ID twice, also after a restart. Uniqueness holds within one kind of ID: an
 * entity ID and a partition ID can have the same value. An entity ID does not tell the
 * partition. There is no
 * promise on order or sign: any {@code long} value is a valid ID.
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
