package badspace.common.partition;

import badspace.common.geometry.Point2;

/** An entity of a 2D space: its ID and its position. */
public record Entity2(long id, Point2 position) {

    /**
     * Returns the entity with the given ID at the point (x, y), shorter to use with an
     * import static, for example in tests and clients. Fails like the constructor of Point2.
     */
    public static Entity2 entity(long id, double x, double y) {
        return new Entity2(id, new Point2(x, y));
    }

    /** Returns an entity with the same ID and the given position. */
    public Entity2 withPosition(Point2 position) {
        return new Entity2(id, position);
    }

    /**
     * Returns an entity with the same ID, moved by dx on the x axis and by dy on the y axis.
     * Fails like {@link Point2#add(double, double)}.
     */
    public Entity2 moveBy(double dx, double dy) {
        return withPosition(position.add(dx, dy));
    }
}
