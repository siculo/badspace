package badspace.api.id;

/**
 * Generates entity IDs. There must be one generator per API process, shared by all spaces.
 *
 * <p>The IDs are unique across all partitions and spaces, and the generator never returns
 * the same ID twice, also after a restart. An ID does not tell the partition. There is no
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
