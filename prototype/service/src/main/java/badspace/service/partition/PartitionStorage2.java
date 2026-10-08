package badspace.service.partition;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.service.index.SlotView2;
import badspace.service.index.SpatialIndex2;
import badspace.service.index.SpatialIndexes;
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
 * Entities of a 2D partition. It has a single writer and is not thread-safe.
 * Entities are stored in primitive arrays: ids[i] has coordinates
 * coords[2*i], coords[2*i+1]. A map gives the slot of each ID.
 * Removal moves the last entity into the free slot, so the arrays stay compact
 * and the order of the entities can change.
 * Each write first checks all the input, then applies it, so a failed write
 * does not change the partition.
 * Queries go to the spatial index of the partition, which the storage informs
 * of each change. The index finds the slots and the storage builds the entities.
 * The positions of the entities must be in the limits of the index: writes
 * outside them fail, so the indices never see them.
 * Writes are grouped in commits, whose numbers always grow. For now a commit
 * only moves the counter.
 */
final class PartitionStorage2 implements SlotView2 {

    private static final int DIMENSIONS = 2;
    private static final int INITIAL_CAPACITY = 16;

    private long[] ids = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private final Map<Long, Integer> slotById = new HashMap<>();
    private int size;
    private long lastCommit;
    private final CoordinateLimits limits;
    private final SpatialIndex2 index;

    /** Creates a storage that uses the linear scan as index. */
    PartitionStorage2() {
        this(IndexConfig.linearScan());
    }

    /** Creates a storage with the given index, and with its limits. */
    PartitionStorage2(IndexConfig index) {
        this(index.limits(), slots -> SpatialIndexes.create(index, slots));
    }

    /**
     * Creates a storage with the given limits and the index made by the
     * factory. The factory gets this storage, so the index can read it
     * during the queries.
     */
    PartitionStorage2(CoordinateLimits limits, Function<SlotView2, SpatialIndex2> indexFactory) {
        this.limits = limits;
        this.index = indexFactory.apply(this);
    }

    void insertAll(List<Entity2> entities) {
        Set<Long> seen = new HashSet<>();
        for (Entity2 e : entities) {
            if (slotById.containsKey(e.id()) || !seen.add(e.id())) {
                throw new IllegalArgumentException("Duplicate entity ID: " + e.id());
            }
            checkLimits(e);
        }
        ensureCapacity(size + entities.size());
        for (Entity2 e : entities) {
            int slot = size++;
            ids[slot] = e.id();
            write(slot, e.position());
            slotById.put(e.id(), slot);
            index.inserted(slot, e.position());
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
            checkLimits(e);
        }
        for (Entity2 e : entities) {
            int slot = slotById.get(e.id());
            Point2 from = positionAt(slot);
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

    List<Entity2> findInRegion(Region2 region) {
        return entitiesAt(index.findInRegion(region));
    }

    List<Entity2> findNearest(Point2 point, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        if (count == 0) {
            return List.of();
        }
        return entitiesAt(index.findNearest(point, count));
    }

    @Override
    public int size() {
        return size;
    }

    CoordinateLimits limits() {
        return limits;
    }

    void commit(long commit) {
        if (commit <= lastCommit) {
            throw new IllegalArgumentException(
                    "Commit " + commit + " is not greater than the last commit " + lastCommit);
        }
        // The partition does not keep the version of the index yet: it will publish
        // it together with the slots of the same commit.
        index.commit(commit);
        lastCommit = commit;
    }

    long lastCommit() {
        return lastCommit;
    }

    @Override
    public long idAt(int slot) {
        return ids[slot];
    }

    @Override
    public Point2 positionAt(int slot) {
        int base = slot * DIMENSIONS;
        return new Point2(coords[base], coords[base + 1]);
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
        index.removed(slot, position);
        if (slot != last) {
            index.relocated(last, slot, positionAt(slot));
        }
    }

    private void checkLimits(Entity2 e) {
        if (!limits.contains(e.position())) {
            throw new IllegalArgumentException("Position of entity " + e.id() + " is outside the limits "
                    + limits.min() + ", " + limits.max() + ": " + e.position());
        }
    }

    private List<Entity2> entitiesAt(int[] slots) {
        List<Entity2> result = new ArrayList<>(slots.length);
        for (int slot : slots) {
            result.add(new Entity2(ids[slot], positionAt(slot)));
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
