package badspace.service;

import java.util.Arrays;

/**
 * Entities of a 3D partition. It has a single writer and is not thread-safe.
 * Entities are stored in primitive arrays: ids[i] has coordinates
 * coords[3*i], coords[3*i+1], coords[3*i+2].
 */
final class PartitionStore3 {

    private static final int DIMENSIONS = 3;
    private static final int INITIAL_CAPACITY = 16;

    private long[] ids = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private int size;

    void insert(long id, double x, double y, double z) {
        if (size == ids.length) {
            grow();
        }
        ids[size] = id;
        int base = size * DIMENSIONS;
        coords[base] = x;
        coords[base + 1] = y;
        coords[base + 2] = z;
        size++;
    }

    int size() {
        return size;
    }

    private void grow() {
        int capacity = ids.length * 2;
        ids = Arrays.copyOf(ids, capacity);
        coords = Arrays.copyOf(coords, capacity * DIMENSIONS);
    }
}
