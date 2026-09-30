package badspace.service;

import badspace.common.Entity3;
import badspace.common.Point3;
import badspace.common.Region3;
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
 * Entities of a 3D partition. It has a single writer and is not thread-safe.
 * Entities are stored in primitive arrays: ids[i] has coordinates
 * coords[3*i], coords[3*i+1], coords[3*i+2]. A map gives the index of each ID.
 * Removal moves the last entity into the free slot, so the arrays stay compact
 * and the order of the entities can change.
 * Each write first checks all the input, then applies it, so a failed write
 * does not change the partition.
 * Queries scan all the entities: there is no spatial index yet.
 */
final class PartitionStore3 {

    private static final int DIMENSIONS = 3;
    private static final int INITIAL_CAPACITY = 16;

    private static final Comparator<Candidate> NEAREST_FIRST =
            Comparator.comparingDouble(Candidate::distanceSquared).thenComparingLong(Candidate::id);

    private long[] ids = new long[INITIAL_CAPACITY];
    private double[] coords = new double[INITIAL_CAPACITY * DIMENSIONS];
    private final Map<Long, Integer> indexById = new HashMap<>();
    private int size;

    void insertAll(List<Entity3> entities) {
        Set<Long> seen = new HashSet<>();
        for (Entity3 e : entities) {
            if (indexById.containsKey(e.id()) || !seen.add(e.id())) {
                throw new IllegalArgumentException("Duplicate entity ID: " + e.id());
            }
        }
        ensureCapacity(size + entities.size());
        for (Entity3 e : entities) {
            ids[size] = e.id();
            write(size, e.position());
            indexById.put(e.id(), size);
            size++;
        }
    }

    List<Entity3> getAll(long[] entityIds) {
        List<Entity3> result = new ArrayList<>(entityIds.length);
        for (long id : entityIds) {
            Integer index = indexById.get(id);
            if (index != null) {
                result.add(new Entity3(id, read(index)));
            }
        }
        return result;
    }

    void updateAll(List<Entity3> entities) {
        for (Entity3 e : entities) {
            indexOf(e.id());
        }
        for (Entity3 e : entities) {
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

    List<Entity3> findInRegion(Region3 region) {
        List<Entity3> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Point3 p = read(i);
            if (region.contains(p)) {
                result.add(new Entity3(ids[i], p));
            }
        }
        return result;
    }

    List<Entity3> findNearest(Point3 point, int count) {
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
        List<Entity3> result = new ArrayList<>(sorted.size());
        for (Candidate c : sorted) {
            result.add(new Entity3(c.id(), read(c.index())));
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

    private double distanceSquared(int index, Point3 point) {
        int base = index * DIMENSIONS;
        double dx = coords[base] - point.x();
        double dy = coords[base + 1] - point.y();
        double dz = coords[base + 2] - point.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private Point3 read(int index) {
        int base = index * DIMENSIONS;
        return new Point3(coords[base], coords[base + 1], coords[base + 2]);
    }

    private void write(int index, Point3 p) {
        int base = index * DIMENSIONS;
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

    /** An entity found by findNearest, with its squared distance from the point. */
    private record Candidate(double distanceSquared, long id, int index) {
    }
}
