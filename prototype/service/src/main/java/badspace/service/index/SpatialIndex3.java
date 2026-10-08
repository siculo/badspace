package badspace.service.index;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;

/**
 * Spatial index of a 3D partition. The partition storage calls it after each
 * change, so the index can keep its own structure up to date, and asks it to
 * answer the queries. Each partition has its own index, chosen when the
 * partition is created. All the indices must give the same results, so they
 * can be compared with each other and with the linear scan.
 * <p>
 * The index works with the slots of the storage, which it reads through a
 * {@link SlotView3}: it gets slots in the calls and gives slots in the
 * results, and the storage builds the entities. A slot can change: when an
 * entity is removed, the storage moves the last entity into the free slot
 * and calls {@link #relocated}. Indices are created by {@link SpatialIndexes}.
 * <p>
 * Writes are grouped in commits. At each commit the index gives a
 * {@link IndexVersion3}: a version that does not change when the writer goes on.
 */
public interface SpatialIndex3 {

    /** Called after an entity is added in the slot. */
    void inserted(int slot, Point3 position);

    /** Called after the entity in the slot changes its position. */
    void moved(int slot, Point3 from, Point3 to);

    /** Called after the entity in the slot is removed. The slot is now free. */
    void removed(int slot, Point3 position);

    /**
     * Called after a removal, when the storage moves an entity from one slot
     * to another to fill the free slot. The position does not change.
     */
    void relocated(int from, int to, Point3 position);

    /**
     * Closes the current commit with the number of the partition commit. The
     * storage checks that n is greater than the last commit. Returns the version
     * of the index at this commit, which the next writes do not change.
     */
    IndexVersion3 commit(long n);

    /**
     * Returns the slots of the entities inside the region, border included, in any order.
     * The query reads the current state, with the writes after the last commit.
     */
    int[] findInRegion(Region3 region);

    /**
     * Returns the slots of the {@code count} entities nearest to the point,
     * ordered by distance and then by ID. The storage checks that
     * {@code count} is positive.
     */
    int[] findNearest(Point3 point, int count);
}
