package badspace.service.index;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.service.transaction.TransactionalComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Simple 2D storage for the index tests. It keeps the slots and informs the
 * index of each change in the same way as the partition storage: a removal
 * moves the last entity into the free slot. It does not check the writes, so
 * the tests must give known IDs, new IDs for the inserts, and positions in the
 * limits of the index.
 * <p>
 * Like the partition storage, it takes part in the commits as a whole: its snapshot has the
 * snapshot of the index and a copy of the slots, and the restore brings back the slots with
 * the same numbering and then the index.
 */
final class TestStorage2 implements SlotView2, TransactionalComponent<TestStorage2.Snapshot> {

    private final List<Entity2> entities = new ArrayList<>();
    private final Map<Long, Integer> slotById = new HashMap<>();
    private final SpatialIndex2 index;

    /** Creates a storage with the index of the configuration. */
    TestStorage2(IndexConfig config) {
        this(slots -> SpatialIndexes.create(config, slots));
    }

    /** Creates a storage with the index made by the factory, which gets this storage. */
    TestStorage2(Function<SlotView2, SpatialIndex2> indexFactory) {
        this.index = indexFactory.apply(this);
    }

    void insertAll(List<Entity2> added) {
        for (Entity2 e : added) {
            int slot = entities.size();
            entities.add(e);
            slotById.put(e.id(), slot);
            index.inserted(slot, e.position());
        }
    }

    void updateAll(List<Entity2> updated) {
        for (Entity2 e : updated) {
            int slot = slotById.get(e.id());
            Point2 from = entities.get(slot).position();
            entities.set(slot, e);
            index.moved(slot, from, e.position());
        }
    }

    void removeAll(long[] entityIds) {
        for (long id : entityIds) {
            int slot = slotById.remove(id);
            Point2 position = entities.get(slot).position();
            int last = entities.size() - 1;
            Entity2 moved = entities.remove(last);
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

    @Override
    public void acceptCommit(Snapshot prepared) {
        index.acceptCommit(prepared.index());
    }

    @Override
    public void abandonCommit(Snapshot prepared) {
        index.abandonCommit(prepared.index());
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
        return new Snapshot(IndexSnapshot2.empty(), List.of());
    }

    /** Returns the index, so the tests can check its structure. */
    SpatialIndex2 index() {
        return index;
    }

    List<Entity2> findInRegion(Region2 region) {
        return entitiesAt(index.findInRegion(region));
    }

    List<Entity2> findNearest(Point2 point, int count) {
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
    public Point2 positionAt(int slot) {
        return entities.get(slot).position();
    }

    private List<Entity2> entitiesAt(int[] slots) {
        return entitiesAt(entities, slots);
    }

    private static List<Entity2> entitiesAt(List<Entity2> entities, int[] slots) {
        List<Entity2> result = new ArrayList<>(slots.length);
        for (int slot : slots) {
            result.add(entities.get(slot));
        }
        return result;
    }

    /** A snapshot of the index with the slots of its commit, which never change. */
    record Snapshot(IndexSnapshot2 index, List<Entity2> entities) implements SlotView2 {

        List<Entity2> findInRegion(Region2 region) {
            return entitiesAt(entities, index.findInRegion(region, this));
        }

        List<Entity2> findNearest(Point2 point, int count) {
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
        public Point2 positionAt(int slot) {
            return entities.get(slot).position();
        }
    }
}
