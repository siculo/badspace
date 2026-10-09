package badspace.service.index;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import java.util.Arrays;

/**
 * Index that has no structure: each query reads all the entities of the
 * storage. It is the simplest index and the reference for the tests and the
 * benchmarks of the other indices.
 */
final class LinearScanIndex3 implements SpatialIndex3 {

    /** The version at commit 0, see {@link IndexVersion3#empty()}. */
    static final IndexVersion3 EMPTY = new Version(0);

    private final SlotView3 storage;

    LinearScanIndex3(SlotView3 storage) {
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
    public IndexVersion3 commit(long n) {
        // Nothing to copy: a version reads the slots of its commit.
        return new Version(n);
    }

    @Override
    public int[] findInRegion(Region3 region) {
        return findInRegion(region, storage);
    }

    @Override
    public int[] findNearest(Point3 point, int count) {
        return findNearest(point, count, storage);
    }

    private static int[] findInRegion(Region3 region, SlotView3 storage) {
        int[] found = new int[storage.size()];
        int count = 0;
        for (int slot = 0; slot < storage.size(); slot++) {
            if (region.contains(storage.positionAt(slot))) {
                found[count++] = slot;
            }
        }
        return Arrays.copyOf(found, count);
    }

    private static int[] findNearest(Point3 point, int count, SlotView3 storage) {
        NearestSlots nearest = new NearestSlots(count);
        for (int slot = 0; slot < storage.size(); slot++) {
            nearest.offer(storage.positionAt(slot).distanceSquared(point), storage.idAt(slot), slot);
        }
        return nearest.slots();
    }

    private record Version(long commit) implements IndexVersion3 {

        @Override
        public int[] findInRegion(Region3 region, SlotView3 slots) {
            return LinearScanIndex3.findInRegion(region, slots);
        }

        @Override
        public int[] findNearest(Point3 point, int count, SlotView3 slots) {
            return LinearScanIndex3.findNearest(point, count, slots);
        }
    }
}
