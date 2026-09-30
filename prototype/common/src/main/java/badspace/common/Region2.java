package badspace.common;

/**
 * An area of a 2D space, used by range queries. Points on the border are inside.
 * New shapes can be added later without changing the node contract.
 */
public sealed interface Region2 permits Box2, Circle2 {

    /** Returns true if the point is inside the region or on its border. */
    boolean contains(Point2 p);
}
