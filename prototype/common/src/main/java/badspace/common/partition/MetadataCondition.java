package badspace.common.partition;

/**
 * A condition on one key of the application metadata of an entity, for a conditional write:
 * the key has a given value, or the key has no value. The conditions are checked on the state
 * of the writer, with the writes not committed yet.
 */
public sealed interface MetadataCondition permits MetadataCondition.ValueIs, MetadataCondition.Absent {

    /** Returns the entity whose metadata the condition checks. */
    long entityId();

    /** Returns the key that the condition checks. */
    String key();

    /** Returns the condition that the key of the entity has the value, shorter to use with an import static. */
    static ValueIs valueIs(long entityId, String key, long value) {
        return new ValueIs(entityId, key, value);
    }

    /** Returns the condition that the key of the entity has no value, shorter to use with an import static. */
    static Absent absent(long entityId, String key) {
        return new Absent(entityId, key);
    }

    /** True if the key of the entity has the value. */
    record ValueIs(long entityId, String key, long value) implements MetadataCondition {

        /**
         * Fails with NullPointerException if key is null, and with IllegalArgumentException if it
         * is not a valid key (see {@link ApplicationMetadata#checkKey}).
         */
        public ValueIs {
            ApplicationMetadata.checkKey(key);
        }
    }

    /** True if the key of the entity has no value. */
    record Absent(long entityId, String key) implements MetadataCondition {

        /**
         * Fails with NullPointerException if key is null, and with IllegalArgumentException if it
         * is not a valid key (see {@link ApplicationMetadata#checkKey}).
         */
        public Absent {
            ApplicationMetadata.checkKey(key);
        }
    }
}
