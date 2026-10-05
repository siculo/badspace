package badspace.service;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;

/**
 * Entities of a 3D partition. It has a single writer and is not thread-safe.
 * Entities are stored in primitive arrays: ids[i] has coordinates
 * coords[3*i], coords[3*i+1], coords[3*i+2]. A map gives the slot of each ID.
 * Removal moves the last entity into the free slot, so the arrays stay compact
 * and the order of the entities can change.
 * Each write first checks all the input, then applies it, so a failed write
 * does not change the partition.
 * Queries go to the spatial index of the partition, which the storage informs
 * of each change. The index finds the slots and the storage builds the entities.
 * The positions of the entities must be in the limits of the index: writes
 * outside them fail, so the indices never see them.
 */
final class PartitionStorage3 {

    private static final int DIMENSIONS = 3;
    private static final int INITIAL_CAPACITY = 16;

    private long[] ids = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private final Map<Long, Integer> slotById = new HashMap<>();
    private int size;
    private final CoordinateLimits limits;
    private final SpatialIndex3 index;

    /** Creates a storage that uses the linear scan as index. */
    PartitionStorage3() {
        this(IndexConfig.linearScan());
    }

    /** Creates a storage with the given index, and with its limits. */
    PartitionStorage3(IndexConfig index) {
        this(index.limits(), indexFactory(index));
    }

    /**
     * Creates a storage with the given limits and the index made by the
     * factory. The factory gets this storage, so the index can read it
     * during the queries.
     */
    PartitionStorage3(CoordinateLimits limits, Function<PartitionStorage3, SpatialIndex3> indexFactory) {
        this.limits = limits;
        this.index = indexFactory.apply(this);
    }

    private static Function<PartitionStorage3, SpatialIndex3> indexFactory(IndexConfig index) {
        return switch (index) {
            case IndexConfig.LinearScan _ -> LinearScanIndex3::new;
            case IndexConfig.UniformGrid g -> storage -> new UniformGridIndex3(storage, g.cellSize());
            case IndexConfig.GridQuadtree g ->
                    storage -> new GridOctreeIndex3(storage, g.cellSize(), g.leafCapacity());
        };
    }

    void insertAll(List<Entity3> entities) {
        Set<Long> seen = new HashSet<>();
        for (Entity3 e : entities) {
            if (slotById.containsKey(e.id()) || !seen.add(e.id())) {
                throw new IllegalArgumentException("Duplicate entity ID: " + e.id());
            }
            checkLimits(e);
        }
        ensureCapacity(size + entities.size());
        for (Entity3 e : entities) {
            int slot = size++;
            ids[slot] = e.id();
            write(slot, e.position());
            slotById.put(e.id(), slot);
            index.inserted(slot, e.position());
        }
    }

    List<Entity3> getAll(long[] entityIds) {
        List<Entity3> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            Integer slot = slotById.get(id);
            if (slot != null) {
                result.add(new Entity3(id, positionAt(slot)));
            }
        }
        return result;
    }

    void updateAll(List<Entity3> entities) {
        for (Entity3 e : entities) {
            slotOf(e.id());
            checkLimits(e);
        }
        for (Entity3 e : entities) {
            int slot = slotById.get(e.id());
            Point3 from = positionAt(slot);
            write(slot, e.position());
            index.moved(slot, from, e.position());
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

    List<Entity3> findInRegion(Region3 region) {
        return entitiesAt(index.findInRegion(region));
    }

    List<Entity3> findNearest(Point3 point, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        if (count == 0) {
            return List.of();
        }
        return entitiesAt(index.findNearest(point, count));
    }

    int size() {
        return size;
    }

    CoordinateLimits limits() {
        return limits;
    }

    /** Returns the ID of the entity in the slot. Slots go from 0 to size() - 1. */
    long idAt(int slot) {
        return ids[slot];
    }

    /** Returns the position of the entity in the slot. */
    Point3 positionAt(int slot) {
        int base = slot * DIMENSIONS;
        return new Point3(coords[base], coords[base + 1], coords[base + 2]);
    }

    private void remove(long id) {
        int slot = slotById.remove(id);
        Point3 position = positionAt(slot);
        int last = size - 1;
        if (slot != last) {
            ids[slot] = ids[last];
            System.arraycopy(coords, last * DIMENSIONS, coords, slot * DIMENSIONS, DIMENSIONS);
            slotById.put(ids[slot], slot);
        }
        size--;
        index.removed(slot, position);
        if (slot != last) {
            index.relocated(last, slot, positionAt(slot));
        }
    }

    private void checkLimits(Entity3 e) {
        if (!limits.contains(e.position())) {
            throw new IllegalArgumentException("Position of entity " + e.id() + " is outside the limits "
                    + limits.min() + ", " + limits.max() + ": " + e.position());
        }
    }

    private List<Entity3> entitiesAt(int[] slots) {
        List<Entity3> result = new ArrayList<>(slots.length);
        for (int slot : slots) {
            result.add(new Entity3(ids[slot], positionAt(slot)));
        }
        return result;
    }

    private int slotOf(long id) {
        Integer slot = slotById.get(id);
        if (slot == null) {
            throw new NoSuchElementException("Unknown entity ID: " + id);
        }
        return slot;
    }

    private void write(int slot, Point3 p) {
        int base = slot * DIMENSIONS;
        coords[base] = p.x();
        coords[base + 1] = p.y();
        coords[base + 2] = p.z();
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
