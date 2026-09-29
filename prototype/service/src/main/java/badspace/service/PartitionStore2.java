package badspace.service;

import java.util.Arrays;

/**
 * Entities of a 2D partition. It has a single writer and is not thread-safe.
 * Entities are stored in primitive arrays: ids[i] has coordinates
 * coords[2*i], coords[2*i+1].
 */
final class PartitionStore2 {

    private static final int DIMENSIONS = 2;
    private static final int INITIAL_CAPACITY = 16;

    private long[] ids = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private int size;

    void insert(long id, double x, double y) {
        if (size == ids.length) {
            grow();
        }
        ids[size] = id;
        int base = size * DIMENSIONS;
        coords[base] = x;
        coords[base + 1] = y;
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
