package badspace.service;

import badspace.common.Entity2;
import badspace.common.Point2;
import badspace.common.Region2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Entities of a 2D partition. It has a single writer and is not thread-safe.
 * Entities are stored in primitive arrays: ids[i] has coordinates
 * coords[2*i], coords[2*i+1]. A map gives the index of each ID.
 * Removal moves the last entity into the free slot, so the arrays stay compact
 * and the order of the entities can change.
 * Each write first checks all the input, then applies it, so a failed write
 * does not change the partition.
 * Queries scan all the entities: there is no spatial index yet.
 */
final class PartitionStore2 {

    private static final int DIMENSIONS = 2;
    private static final int INITIAL_CAPACITY = 16;

    private static final Comparator<Candidate> NEAREST_FIRST =
            Comparator.comparingDouble(Candidate::distanceSquared).thenComparingLong(Candidate::id);

    private long[] ids = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private final Map<Long, Integer> indexById = new HashMap<>();
    private int size;

    void insertAll(List<Entity2> entities) {
        Set<Long> seen = new HashSet<>();
        for (Entity2 e : entities) {
            if (indexById.containsKey(e.id()) || !seen.add(e.id())) {
                throw new IllegalArgumentException("Duplicate entity ID: " + e.id());
            }
        }
        ensureCapacity(size + entities.size());
        for (Entity2 e : entities) {
            ids[size] = e.id();
            write(size, e.position());
            indexById.put(e.id(), size);
            size++;
        }
    }

    List<Entity2> getAll(long[] entityIds) {
        List<Entity2> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            Integer index = indexById.get(id);
            if (index != null) {
                result.add(new Entity2(id, read(index)));
            }
        }
        return result;
    }

    void updateAll(List<Entity2> entities) {
        for (Entity2 e : entities) {
            indexOf(e.id());
        }
        for (Entity2 e : entities) {
            write(indexById.get(e.id()), e.position());
        }
    }

    void removeAll(long[] entityIds) {
        Set<Long> seen = new HashSet<>();
        for (long id : entityIds) {
            indexOf(id);
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Duplicate entity ID: " + id);
            }
        }
        for (long id : entityIds) {
            remove(id);
        }
    }

    List<Entity2> findInRegion(Region2 region) {
        List<Entity2> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Point2 p = read(i);
            if (region.contains(p)) {
                result.add(new Entity2(ids[i], p));
            }
        }
        return result;
    }

    List<Entity2> findNearest(Point2 point, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        if (count == 0) {
            return List.of();
        }
        // The head of the queue is the farthest of the nearest entities found so far.
        PriorityQueue<Candidate> nearest = new PriorityQueue<>(NEAREST_FIRST.reversed());
        for (int i = 0; i < size; i++) {
            Candidate c = new Candidate(distanceSquared(i, point), ids[i], i);
            if (nearest.size() < count) {
                nearest.add(c);
            } else if (NEAREST_FIRST.compare(c, nearest.peek()) < 0) {
                nearest.poll();
                nearest.add(c);
            }
        }
        List<Candidate> sorted = new ArrayList<>(nearest);
        sorted.sort(NEAREST_FIRST);
        List<Entity2> result = new ArrayList<>(sorted.size());
        for (Candidate c : sorted) {
            result.add(new Entity2(c.id(), read(c.index())));
        }
        return result;
    }

    int size() {
        return size;
    }

    private void remove(long id) {
        int index = indexById.remove(id);
        int last = size - 1;
        if (index != last) {
            ids[index] = ids[last];
            System.arraycopy(coords, last * DIMENSIONS, coords, index * DIMENSIONS, DIMENSIONS);
            indexById.put(ids[index], index);
        }
        size--;
    }

    private int indexOf(long id) {
        Integer index = indexById.get(id);
        if (index == null) {
            throw new NoSuchElementException("Unknown entity ID: " + id);
        }
        return index;
    }

    private double distanceSquared(int index, Point2 point) {
        int base = index * DIMENSIONS;
        double dx = coords[base] - point.x();
        double dy = coords[base + 1] - point.y();
        return dx * dx + dy * dy;
    }

    private Point2 read(int index) {
        int base = index * DIMENSIONS;
        return new Point2(coords[base], coords[base + 1]);
    }

    private void write(int index, Point2 p) {
        int base = index * DIMENSIONS;
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

    /** An entity found by findNearest, with its squared distance from the point. */
    private record Candidate(double distanceSquared, long id, int index) {
    }
}
