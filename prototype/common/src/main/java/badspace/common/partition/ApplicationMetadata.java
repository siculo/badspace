package badspace.common.partition;

import java.util.Map;

/**
 * The application metadata of an entity: free pairs of a string key and a long value, which
 * the software reads and writes. They are separate from the position, so the spatial
 * queries do not read them. An entity without metadata has an empty map.
 *
 * @param entityId the entity of the metadata
 * @param values the values by key; the record keeps an unmodifiable copy
 */
public record ApplicationMetadata(long entityId, Map<String, Long> values) {

    /** Fails with NullPointerException if values, a key or a value is null. */
    public ApplicationMetadata {
        values = Map.copyOf(values);
    }
}
