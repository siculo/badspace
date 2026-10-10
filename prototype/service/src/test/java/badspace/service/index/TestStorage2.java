package badspace.service.index;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
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
 */
final class TestStorage2 implements SlotView2 {

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

    /** Closes a commit and returns the version of the index with a copy of the slots of the commit. */
    Version commit(long n) {
        return new Version(index.commit(n), List.copyOf(entities));
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

    /** A version of the index with the slots of its commit, which never change. */
    record Version(IndexVersion2 index, List<Entity2> entities) implements SlotView2 {

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
