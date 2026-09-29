package badspace;

import java.util.Arrays;

/**
 * A partition of a 3-dimensional space. It has a single writer and is not thread-safe.
 * Entities are stored in primitive arrays: ids[i] has coordinates
 * coords[3*i], coords[3*i+1], coords[3*i+2].
 */
public final class Partition3 {

    private static final int DIMENSIONS = 3;
    private static final int INITIAL_CAPACITY = 16;

    private final Space3 space;
    private long[] ids = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private int size;

    Partition3(Space3 space) {
        this.space = space;
    }

    /** Adds a new entity and returns its ID. */
    public long insert(double x, double y, double z) {
        if (size == ids.length) {
            grow();
        }
        long id = space.nextId();
        ids[size] = id;
        int base = size * DIMENSIONS;
        coords[base] = x;
        coords[base + 1] = y;
        coords[base + 2] = z;
        size++;
        return id;
    }

    public int size() {
        return size;
    }

    private void grow() {
        int capacity = ids.length * 2;
        ids = Arrays.copyOf(ids, capacity);
        coords = Arrays.copyOf(coords, capacity * DIMENSIONS);
    }
}
