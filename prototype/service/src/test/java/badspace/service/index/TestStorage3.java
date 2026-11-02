package badspace.service.index;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.service.transaction.TransactionalComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Simple 3D storage for the index tests. It keeps the slots and informs the
 * index of each change in the same way as the partition storage: a removal
 * moves the last entity into the free slot. It does not check the writes, so
 * the tests must give known IDs, new IDs for the inserts, and positions in the
 * limits of the index.
 * <p>
 * Like the partition storage, it takes part in the commits as a whole: its snapshot has the
 * snapshot of the index and a copy of the slots, and the restore brings back the slots with
 * the same numbering and then the index.
 */
final class TestStorage3 implements SlotView3, TransactionalComponent<TestStorage3.Snapshot> {

    private final List<Entity3> entities = new ArrayList<>();
    private final Map<Long, Integer> slotById = new HashMap<>();
    private final SpatialIndex3 index;

    /** Creates a storage with the index of the configuration. */
    TestStorage3(IndexConfig config) {
        this(slots -> SpatialIndexes.create(config, slots));
    }

    /** Creates a storage with the index made by the factory, which gets this storage. */
    TestStorage3(Function<SlotView3, SpatialIndex3> indexFactory) {
        this.index = indexFactory.apply(this);
    }

    void insertAll(List<Entity3> added) {
        for (Entity3 e : added) {
            int slot = entities.size();
            entities.add(e);
            slotById.put(e.id(), slot);
            index.inserted(slot, e.position());
        }
    }

    void updateAll(List<Entity3> updated) {
        for (Entity3 e : updated) {
            int slot = slotById.get(e.id());
            Point3 from = entities.get(slot).position();
            entities.set(slot, e);
            index.moved(slot, from, e.position());
        }
    }

    void removeAll(long[] entityIds) {
        for (long id : entityIds) {
            int slot = slotById.remove(id);
            Point3 position = entities.get(slot).position();
            int last = entities.size() - 1;
            Entity3 moved = entities.remove(last);
            if (slot != last) {
                entities.set(slot, moved);
                slotById.put(moved.id(), slot);
            }
            index.removed(slot, position);
            if (slot != last) {
                index.relocated(last, slot, moved.position());
            }
        }
    }

    /** Closes a commit and returns the snapshot of the index with a copy of the slots of the commit. */
    @Override
    public Snapshot prepareCommit(long n) {
        return new Snapshot(index.prepareCommit(n), List.copyOf(entities));
    }

    /** Brings back the slots of the snapshot, in the same order, and then the index. */
    @Override
    public void restore(Snapshot confirmed) {
        entities.clear();
        entities.addAll(confirmed.entities());
        slotById.clear();
        for (int slot = 0; slot < entities.size(); slot++) {
            slotById.put(entities.get(slot).id(), slot);
        }
        index.restore(confirmed.index());
    }

    /** Returns the snapshot before the first commit: no slots and the empty index. */
    static Snapshot emptySnapshot() {
        return new Snapshot(IndexSnapshot3.empty(), List.of());
    }

    /** Returns the index, so the tests can check its structure. */
    SpatialIndex3 index() {
        return index;
    }

    List<Entity3> findInRegion(Region3 region) {
        return entitiesAt(index.findInRegion(region));
    }

    List<Entity3> findNearest(Point3 point, int count) {
        return entitiesAt(index.findNearest(point, count));
    }

    @Override
    public int size() {
        return entities.size();
    }

    @Override
    public long idAt(int slot) {
        return entities.get(slot).id();
    }

    @Override
    public Point3 positionAt(int slot) {
        return entities.get(slot).position();
    }

    private List<Entity3> entitiesAt(int[] slots) {
        return entitiesAt(entities, slots);
    }

    private static List<Entity3> entitiesAt(List<Entity3> entities, int[] slots) {
        List<Entity3> result = new ArrayList<>(slots.length);
        for (int slot : slots) {
            result.add(entities.get(slot));
        }
        return result;
    }

    /** A snapshot of the index with the slots of its commit, which never change. */
    record Snapshot(IndexSnapshot3 index, List<Entity3> entities) implements SlotView3 {

        List<Entity3> findInRegion(Region3 region) {
            return entitiesAt(entities, index.findInRegion(region, this));
        }

        List<Entity3> findNearest(Point3 point, int count) {
            return entitiesAt(entities, index.findNearest(point, count, this));
        }

        @Override
        public int size() {
            return entities.size();
        }

        @Override
        public long idAt(int slot) {
            return entities.get(slot).id();
        }

        @Override
        public Point3 positionAt(int slot) {
            return entities.get(slot).position();
        }
    }
}
