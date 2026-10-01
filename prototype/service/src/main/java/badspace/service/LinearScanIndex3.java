package badspace.service;

import badspace.common.Point3;
import badspace.common.Region3;
import java.util.Arrays;
import java.util.Comparator;
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
    public void inserted(int slot, Point3 position) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public void moved(int slot, Point3 from, Point3 to) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public void removed(int slot, Point3 position) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public void relocated(int from, int to, Point3 position) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public int[] findInRegion(Region3 region) {
        int[] found = new int[storage.size()];
        int count = 0;
        for (int slot = 0; slot < storage.size(); slot++) {
            if (region.contains(storage.positionAt(slot))) {
                found[count++] = slot;
            }
        }
        return Arrays.copyOf(found, count);
    }

    @Override
    public int[] findNearest(Point3 point, int count) {
        // The head of the queue is the farthest of the nearest entities found so far.
        PriorityQueue<Candidate> nearest = new PriorityQueue<>(NEAREST_FIRST.reversed());
        for (int slot = 0; slot < storage.size(); slot++) {
            double distanceSquared = storage.positionAt(slot).distanceSquared(point);
            Candidate c = new Candidate(distanceSquared, storage.idAt(slot), slot);
            if (nearest.size() < count) {
                nearest.add(c);
            } else if (NEAREST_FIRST.compare(c, nearest.peek()) < 0) {
                nearest.poll();
                nearest.add(c);
            }
        }
        return nearest.stream().sorted(NEAREST_FIRST).mapToInt(Candidate::slot).toArray();
    }

    /** An entity found by findNearest, with its squared distance from the point. */
    private record Candidate(double distanceSquared, long id, int slot) {
    }
}
