package badspace.service.index;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import java.util.Arrays;

/**
 * Index that has no structure: each query reads all the entities of the
 * storage. It is the simplest index and the reference for the tests and the
 * benchmarks of the other indices.
 */
final class LinearScanIndex2 implements SpatialIndex2 {

    private final SlotView2 storage;

    LinearScanIndex2(SlotView2 storage) {
        this.storage = storage;
    }

    @Override
    public void inserted(int slot, Point2 position) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public void moved(int slot, Point2 from, Point2 to) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public void removed(int slot, Point2 position) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public void relocated(int from, int to, Point2 position) {
        // Nothing to do: queries read the storage.
    }

    @Override
    public int[] findInRegion(Region2 region) {
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
    public int[] findNearest(Point2 point, int count) {
        NearestSlots nearest = new NearestSlots(count);
        for (int slot = 0; slot < storage.size(); slot++) {
            nearest.offer(storage.positionAt(slot).distanceSquared(point), storage.idAt(slot), slot);
        }
        return nearest.slots();
    }
}
