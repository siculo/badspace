package badspace.common;

/**
 * A volume of a 3D space, used by range queries. Points on the border are inside.
 * New shapes can be added later without changing the node contract.
 */
public sealed interface Region3 permits Box3, Sphere3 {

    /** Returns true if the point is inside the region or on its border. */
    boolean contains(Point3 p);
}
