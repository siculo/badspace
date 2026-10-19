package badspace.service.partition.metadata;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The application metadata of one entity. It never changes: a write makes a new instance,
 * so the snapshots that see the old one keep it. An entity has few keys, so they are in a
 * sorted array, and a write copies the arrays.
 */
final class MetadataValues {

    static final MetadataValues EMPTY = new MetadataValues(new String[0], new long[0]);

    // Entry: key and value. The two arrays have the same length and the same index for the
    // same entry; the keys are sorted and different.
    private final String[] keys;
    private final long[] values;

    private MetadataValues(String[] keys, long[] values) {
        this.keys = keys;
        this.values = values;
    }

    boolean isEmpty() {
        return keys.length == 0;
    }

    /** Returns the metadata with the key set to the value. */
    MetadataValues with(String key, long value) {
        int index = Arrays.binarySearch(keys, key);
        if (index >= 0) {
            if (values[index] == value) {
                return this;
            }
            long[] newValues = values.clone();
            newValues[index] = value;
            return new MetadataValues(keys, newValues);
        }
        int insertion = -index - 1;
        String[] newKeys = new String[keys.length + 1];
        long[] newValues = new long[values.length + 1];
        System.arraycopy(keys, 0, newKeys, 0, insertion);
        System.arraycopy(values, 0, newValues, 0, insertion);
        newKeys[insertion] = key;
        newValues[insertion] = value;
        System.arraycopy(keys, insertion, newKeys, insertion + 1, keys.length - insertion);
        System.arraycopy(values, insertion, newValues, insertion + 1, values.length - insertion);
        return new MetadataValues(newKeys, newValues);
    }

    /** Returns the metadata without the key. */
    MetadataValues without(String key) {
        int index = Arrays.binarySearch(keys, key);
        if (index < 0) {
            return this;
        }
        String[] newKeys = new String[keys.length - 1];
        long[] newValues = new long[values.length - 1];
        System.arraycopy(keys, 0, newKeys, 0, index);
        System.arraycopy(values, 0, newValues, 0, index);
        System.arraycopy(keys, index + 1, newKeys, index, keys.length - index - 1);
        System.arraycopy(values, index + 1, newValues, index, values.length - index - 1);
        return new MetadataValues(newKeys, newValues);
    }

    /** Returns a new map with the values by key. */
    Map<String, Long> toMap() {
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], values[i]);
        }
        return map;
    }
}
