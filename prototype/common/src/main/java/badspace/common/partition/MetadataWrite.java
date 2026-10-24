package badspace.common.partition;

/**
 * A write of one key of the application metadata of an entity: it sets the value of the
 * key, or deletes the key. Keys are strings without unpaired surrogates (see
 * {@link ApplicationMetadata#checkKey}) and values are long; there are no limits on the number
 * of keys or on their length. A write with a bad key cannot be created, so it never reaches a
 * batch.
 */
public sealed interface MetadataWrite permits MetadataWrite.Put, MetadataWrite.Delete {

    /** Returns the entity whose metadata the write changes. */
    long entityId();

    /** Returns the key that the write changes. */
    String key();

    /** Returns the write that sets the key of the entity to the value, shorter to use with an import static. */
    static Put put(long entityId, String key, long value) {
        return new Put(entityId, key, value);
    }

    /** Returns the write that deletes the key of the entity, shorter to use with an import static. */
    static Delete delete(long entityId, String key) {
        return new Delete(entityId, key);
    }

    /** Sets the key of the entity to the value, also if the key has no value yet. */
    record Put(long entityId, String key, long value) implements MetadataWrite {

        /**
         * Fails with NullPointerException if key is null, and with IllegalArgumentException if it
         * is not a valid key (see {@link ApplicationMetadata#checkKey}).
         */
        public Put {
            ApplicationMetadata.checkKey(key);
        }
    }

    /** Deletes the key of the entity. If the key has no value, it does nothing. */
    record Delete(long entityId, String key) implements MetadataWrite {

        /**
         * Fails with NullPointerException if key is null, and with IllegalArgumentException if it
         * is not a valid key (see {@link ApplicationMetadata#checkKey}).
         */
        public Delete {
            ApplicationMetadata.checkKey(key);
        }
    }
}
