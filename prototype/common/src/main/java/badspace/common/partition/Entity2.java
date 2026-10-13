package badspace.common.partition;

import badspace.common.geometry.Point2;

/** An entity of a 2D space: its ID and its position. */
public record Entity2(long id, Point2 position) {

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
