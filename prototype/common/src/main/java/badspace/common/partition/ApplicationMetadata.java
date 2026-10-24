package badspace.common.partition;

import java.util.Map;

/**
 * The application metadata of an entity: free pairs of a string key and a long value, which
 * the software reads and writes. They are separate from the position, so the spatial
 * queries do not read them. An entity without metadata has an empty map.
 * <p>
 * A key is a well-formed UTF-16 string, see {@link #checkKey}: the saves write the keys in
 * UTF-8, which cannot keep an unpaired surrogate.
 *
 * @param entityId the entity of the metadata
 * @param values the values by key; the record keeps an unmodifiable copy
 */
public record ApplicationMetadata(long entityId, Map<String, Long> values) {

    /** Fails with NullPointerException if values, a key or a value is null. */
    public ApplicationMetadata {
        values = Map.copyOf(values);
    }

    /**
     * Checks that a key can be a key of the application metadata: a string without unpaired
     * surrogates, so each high surrogate is followed by a low surrogate and each low surrogate
     * follows a high surrogate. Any other string is valid, also the empty one.
     *
     * @param key the key to check
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if key has an unpaired surrogate
     */
    public static void checkKey(String key) {
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < key.length() && Character.isLowSurrogate(key.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException("Metadata key has an unpaired surrogate at index " + i
                        + ": " + Integer.toHexString(c));
            }
        }
    }
}
