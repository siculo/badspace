package badspace.service.partition;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.service.index.SlotView3;
import badspace.service.index.SpatialIndex3;
import badspace.service.index.SpatialIndexes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;

/**
 * Entities of a 3D partition. It has a single writer and is not thread-safe,
 * except {@link #lastVersion()}.
 * Entities are stored in primitive arrays: slotEntityIds[i] has coordinates
 * coords[3*i], coords[3*i+1], coords[3*i+2]. A {@link SlotTable} gives the slot of each ID.
 * Removal moves the last entity into the free slot, so the arrays stay compact
 * and the order of the entities can change.
 * Each write first checks all the input, then applies it, so a failed write
 * does not change the partition.
 * Queries go to the spatial index of the partition, which the storage informs
 * of each change. The index finds the slots and the storage builds the entities.
 * The positions of the entities must be in the limits of the index: writes
 * outside them fail, so the indices never see them.
 * Writes are grouped in commits, whose numbers always grow. Each commit makes
 * a {@link StorageVersion3}: a snapshot of the slots and the version of the
 * index of the same commit. The coordinates are copied at each commit; the
 * IDs and the table from ID to slot only after insertions or removals, else
 * the new snapshot shares them with the one before.
 * <p>
 * The reads of the storage see the state of the writer, with the writes not
 * yet committed, and only the writer can call them. The other threads read
 * the {@linkplain #lastVersion() last version}: the commit publishes it with
 * a single write of a volatile field, so a reader sees either the version
 * before or the whole new version.
 */
final class PartitionStorage3 implements SlotView3 {

    private static final int DIMENSIONS = 3;
    private static final int INITIAL_CAPACITY = 16;

    // Slot: entity ID and position. coords holds DIMENSIONS values per slot (x, y, z),
    // so slot i is slotEntityIds[i] and coords[DIMENSIONS * i ...]. The capacity of
    // coords is always DIMENSIONS times the capacity of slotEntityIds.
    private long[] slotEntityIds = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private final SlotTable slotById = new SlotTable();
    private int size;
    private long lastCommit;
    /** The version of the last commit, which the readers see. */
    private volatile StorageVersion3 lastVersion = StorageVersion3.empty();
    /** True after an insertion or a removal not yet in a snapshot. */
    private boolean idsChanged;
    private final CoordinateLimits limits;
    private final SpatialIndex3 index;

    /** Creates a storage that uses the linear scan as index. */
    PartitionStorage3() {
        this(IndexConfig.linearScan());
    }

    /** Creates a storage with the given index, and with its limits. */
    PartitionStorage3(IndexConfig index) {
        this(index.limits(), slots -> SpatialIndexes.create(index, slots));
    }

    /**
     * Creates a storage with the given limits and the index made by the
     * factory. The factory gets this storage, so the index can read it
     * during the queries.
     */
    PartitionStorage3(CoordinateLimits limits, Function<SlotView3, SpatialIndex3> indexFactory) {
        this.limits = limits;
        this.index = indexFactory.apply(this);
    }

    void insertAll(List<Entity3> entities) {
        Set<Long> seen = new HashSet<>();
        for (Entity3 e : entities) {
            if (slotById.contains(e.id()) || !seen.add(e.id())) {
                throw new IllegalArgumentException("Duplicate entity ID: " + e.id());
            }
            checkLimits(e);
        }
        ensureCapacity(size + entities.size());
        for (Entity3 e : entities) {
            int slot = size++;
            slotEntityIds[slot] = e.id();
            write(slot, e.position());
            slotById.put(e.id(), slot);
            idsChanged = true;
            index.inserted(slot, e.position());
        }
    }

    List<Entity3> getAll(long[] entityIds) {
        List<Entity3> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            int slot = slotById.get(id);
            if (slot != SlotTable.NONE) {
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

    @Override
    public int size() {
        return size;
    }

    CoordinateLimits limits() {
        return limits;
    }

    /** Closes the commit, publishes the version of the partition at the commit and returns it. */
    StorageVersion3 commit(long commit) {
        if (commit <= lastCommit) {
            throw new IllegalArgumentException(
                    "Commit " + commit + " is not greater than the last commit " + lastCommit);
        }
        StorageVersion3 version = new StorageVersion3(commit, snapshot(), index.commit(commit));
        idsChanged = false;
        lastCommit = commit;
        // The publication: the last write of the commit.
        lastVersion = version;
        return version;
    }

    /** Returns the version of the last commit. Unlike the other methods, any thread can call it. */
    StorageVersion3 lastVersion() {
        return lastVersion;
    }

    long lastCommit() {
        return lastCommit;
    }

    @Override
    public long idAt(int slot) {
        return slotEntityIds[slot];
    }

    @Override
    public Point3 positionAt(int slot) {
        int base = slot * DIMENSIONS;
        return new Point3(coords[base], coords[base + 1], coords[base + 2]);
    }

    private SlotSnapshot3 snapshot() {
        double[] coordsCopy = Arrays.copyOf(coords, size * DIMENSIONS);
        if (!idsChanged) {
            SlotSnapshot3 last = lastVersion.slots();
            return new SlotSnapshot3(last.slotEntityIds, coordsCopy, last.slotById);
        }
        return new SlotSnapshot3(Arrays.copyOf(slotEntityIds, size), coordsCopy, slotById.copy());
    }

    private void remove(long entityId) {
        int slot = slotById.remove(entityId);
        idsChanged = true;
        Point3 position = positionAt(slot);
        int last = size - 1;
        if (slot != last) {
            slotEntityIds[slot] = slotEntityIds[last];
            System.arraycopy(coords, last * DIMENSIONS, coords, slot * DIMENSIONS, DIMENSIONS);
            slotById.put(slotEntityIds[slot], slot);
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
            result.add(new Entity3(slotEntityIds[slot], positionAt(slot)));
        }
        return result;
    }

    private int slotOf(long entityId) {
        int slot = slotById.get(entityId);
        if (slot == SlotTable.NONE) {
            throw new NoSuchElementException("Unknown entity ID: " + entityId);
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
        if (needed <= slotEntityIds.length) {
            return;
        }
        int capacity = slotEntityIds.length;
        while (capacity < needed) {
            capacity *= 2;
        }
        slotEntityIds = Arrays.copyOf(slotEntityIds, capacity);
        coords = Arrays.copyOf(coords, capacity * DIMENSIONS);
    }
}
