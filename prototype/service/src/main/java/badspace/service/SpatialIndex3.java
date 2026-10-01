package badspace.service;

import badspace.common.Entity3;
import badspace.common.Point3;
import badspace.common.Region3;
import java.util.List;

/**
 * Spatial index of a 3D partition. The partition storage calls it after each
 * change, so the index can keep its own structure up to date, and asks it to
 * answer the queries. Each partition has its own index, chosen when the
 * partition is created. All the indices must give the same results, so they
 * can be compared with each other and with the linear scan.
 */
interface SpatialIndex3 {

    /** Called after an entity is added to the partition. */
    void inserted(long id, Point3 position);

    /** Called after an entity changes its position. */
    void moved(long id, Point3 from, Point3 to);

    /** Called after an entity is removed from the partition. */
    void removed(long id, Point3 position);

    /** Returns the entities inside the region, border included, in any order. */
    List<Entity3> findInRegion(Region3 region);

    /**
     * Returns the {@code count} entities nearest to the point, ordered by
     * distance and then by ID. The storage checks that {@code count} is positive.
     */
    List<Entity3> findNearest(Point3 point, int count);
}
