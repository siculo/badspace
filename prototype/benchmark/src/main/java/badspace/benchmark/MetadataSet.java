package badspace.benchmark;

import java.util.List;

/**
 * The application metadata of the entities in the persistence benchmarks. The values are
 * fixed and documented, so two runs with the same set save the same bytes.
 */
public enum MetadataSet {

    /** No metadata: each entity has no pairs. */
    NONE,

    /**
     * Each entity has the same {@link #KEYS 4 keys}, of 4 to 6 ASCII characters, with a
     * value of 64 bits taken from a fixed seed over the whole range of long. The values are
     * almost never small, so the copy does not share them with the cache of boxed longs.
     */
    FIXED;

    /** The keys of {@link #FIXED}, in string order. */
    static final List<String> KEYS = List.of("health", "owner", "score", "team");

    /** Returns the keys of each entity: none for {@link #NONE}. */
    List<String> keys() {
        return switch (this) {
            case NONE -> List.of();
            case FIXED -> KEYS;
        };
    }
}
