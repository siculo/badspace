package badspace.service;

import badspace.common.Entity3;
import badspace.common.Point3;
import badspace.common.Region3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Index that has no structure: each query reads all the entities of the
 * storage. It is the simplest index and the reference for the tests and the
 * benchmarks of the other indices.
 */
final class LinearScanIndex3 implements SpatialIndex3 {

    private static final Comparator<Candidate> NEAREST_FIRST =
            Comparator.comparingDouble(Candidate::distanceSquared).thenComparingLong(Candidate::id);

    private final PartitionStorage3 storage;

    LinearScanIndex3(PartitionStorage3 storage) {
        this.storage = storage;
    }

    @Override
    public void inserted(long id, Point3 position) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public void moved(long id, Point3 from, Point3 to) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public void removed(long id, Point3 position) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public List<Entity3> findInRegion(Region3 region) {
        List<Entity3> result = new ArrayList<>();
        for (int slot = 0; slot < storage.size(); slot++) {
            Point3 p = storage.positionAt(slot);
            if (region.contains(p)) {
                result.add(new Entity3(storage.idAt(slot), p));
            }
        }
        return result;
    }

    @Override
    public List<Entity3> findNearest(Point3 point, int count) {
        // The head of the queue is the farthest of the nearest entities found so far.
        PriorityQueue<Candidate> nearest = new PriorityQueue<>(NEAREST_FIRST.reversed());
        for (int slot = 0; slot < storage.size(); slot++) {
            Candidate c = new Candidate(storage.distanceSquared(slot, point), storage.idAt(slot), slot);
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
            result.add(new Entity3(c.id(), storage.positionAt(c.slot())));
        }
        return result;
    }

    /** An entity found by findNearest, with its squared distance from the point. */
    private record Candidate(double distanceSquared, long id, int slot) {
    }
}
