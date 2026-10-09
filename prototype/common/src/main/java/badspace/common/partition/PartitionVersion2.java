package badspace.common.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import java.util.List;
import java.util.Optional;

/**
 * A 2D partition as it was at a commit. It never changes, so its reads do not
 * see the writes after the commit, and many threads can read it at the same
 * time while the writer goes on. All the reads of a version see the same
 * commit. A version stays readable also after the partition is removed: it
 * shows the partition as it was at its commit.
 */
public interface PartitionVersion2 {

    /** Returns the number of the commit, or 0 for the empty version before the first commit. */
    long commit();

    /** Returns the number of entities. */
    int size();

    /** Returns the position of an entity, or empty if the ID is not in the version. */
    default Optional<Point2> get(long entityId) {
        return getAll(new long[] {entityId}).stream().findFirst().map(Entity2::position);
    }

    /**
     * Returns the entities with the given IDs, in the same order.
     * IDs that are not in the version are skipped.
     */
    List<Entity2> getAll(long[] entityIds);

    /**
     * Returns the entities that are inside the region or on its border.
     * The order of the result is not defined.
     */
    List<Entity2> findInRegion(Region2 region);

    /**
     * Returns the entities nearest to a point, at most count of them, sorted by distance
     * from the point and then by ID. Fails with IllegalArgumentException if count is negative.
     */
    List<Entity2> findNearest(Point2 point, int count);
}
