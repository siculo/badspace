package badspace.service.index;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
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
 */
final class TestStorage3 implements SlotView3 {

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

    void removeAll(long[] ids) {
        for (long id : ids) {
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

    /** Closes a commit and returns the version of the index with a copy of the slots of the commit. */
    Version commit(long n) {
        return new Version(index.commit(n), List.copyOf(entities));
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

    /** A version of the index with the slots of its commit, which never change. */
    record Version(IndexVersion3 index, List<Entity3> entities) implements SlotView3 {

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
