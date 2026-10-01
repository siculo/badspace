package badspace.service;

import badspace.common.Entity2;
import badspace.common.Point2;
import badspace.common.Region2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Entities of a 2D partition. It has a single writer and is not thread-safe.
 * Entities are stored in primitive arrays: ids[i] has coordinates
 * coords[2*i], coords[2*i+1]. A map gives the slot of each ID.
 * Removal moves the last entity into the free slot, so the arrays stay compact
 * and the order of the entities can change.
 * Each write first checks all the input, then applies it, so a failed write
 * does not change the partition.
 * Queries go to the spatial index of the partition, which the storage informs
 * of each change.
 */
final class PartitionStorage2 {

    private static final int DIMENSIONS = 2;
    private static final int INITIAL_CAPACITY = 16;

    private long[] ids = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private final Map<Long, Integer> slotById = new HashMap<>();
    private int size;
    private final SpatialIndex2 index;

    PartitionStorage2() {
        this.index = new LinearScanIndex2(this);
    }

    void insertAll(List<Entity2> entities) {
        Set<Long> seen = new HashSet<>();
        for (Entity2 e : entities) {
            if (slotById.containsKey(e.id()) || !seen.add(e.id())) {
                throw new IllegalArgumentException("Duplicate entity ID: " + e.id());
            }
        }
        ensureCapacity(size + entities.size());
        for (Entity2 e : entities) {
            ids[size] = e.id();
            write(size, e.position());
            slotById.put(e.id(), size);
            size++;
            index.inserted(e.id(), e.position());
        }
    }

    List<Entity2> getAll(long[] entityIds) {
        List<Entity2> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            Integer slot = slotById.get(id);
            if (slot != null) {
                result.add(new Entity2(id, positionAt(slot)));
            }
        }
        return result;
    }

    void updateAll(List<Entity2> entities) {
        for (Entity2 e : entities) {
            slotOf(e.id());
        }
        for (Entity2 e : entities) {
            int slot = slotById.get(e.id());
            Point2 from = positionAt(slot);
            write(slot, e.position());
            index.moved(e.id(), from, e.position());
        }
    }

    void removeAll(long[] entityIds) {
        Set<Long> seen = new HashSet<>();
        for (long id : entityIds) {
            slotOf(id);
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Duplicate entity ID: " + id);
            }
        }
        for (long id : entityIds) {
            remove(id);
        }
    }

    List<Entity2> findInRegion(Region2 region) {
        return index.findInRegion(region);
    }

    List<Entity2> findNearest(Point2 point, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        if (count == 0) {
            return List.of();
        }
        return index.findNearest(point, count);
    }

    int size() {
        return size;
    }

    /** Returns the ID of the entity in the slot. Slots go from 0 to size() - 1. */
    long idAt(int slot) {
        return ids[slot];
    }

    /** Returns the position of the entity in the slot. */
    Point2 positionAt(int slot) {
        int base = slot * DIMENSIONS;
        return new Point2(coords[base], coords[base + 1]);
    }

    /** Returns the squared distance between the entity in the slot and the point. */
    double distanceSquared(int slot, Point2 point) {
        int base = slot * DIMENSIONS;
        double dx = coords[base] - point.x();
        double dy = coords[base + 1] - point.y();
        return dx * dx + dy * dy;
    }

    private void remove(long id) {
        int slot = slotById.remove(id);
        Point2 position = positionAt(slot);
        int last = size - 1;
        if (slot != last) {
            ids[slot] = ids[last];
            System.arraycopy(coords, last * DIMENSIONS, coords, slot * DIMENSIONS, DIMENSIONS);
            slotById.put(ids[slot], slot);
        }
        size--;
        index.removed(id, position);
    }

    private int slotOf(long id) {
        Integer slot = slotById.get(id);
        if (slot == null) {
            throw new NoSuchElementException("Unknown entity ID: " + id);
        }
        return slot;
    }

    private void write(int slot, Point2 p) {
        int base = slot * DIMENSIONS;
        coords[base] = p.x();
        coords[base + 1] = p.y();
    }

    private void ensureCapacity(int needed) {
        if (needed <= ids.length) {
            return;
        }
        int capacity = ids.length;
        while (capacity < needed) {
            capacity *= 2;
        }
        ids = Arrays.copyOf(ids, capacity);
        coords = Arrays.copyOf(coords, capacity * DIMENSIONS);
    }
}
