package badspace.common;

/** An axis-aligned box of a 2D space, from its lowest corner to its highest corner. */
public record Box2(Point2 min, Point2 max) implements Region2 {

    /** Fails with IllegalArgumentException if min is greater than max on some axis. */
    public Box2 {
        if (!(min.x() <= max.x() && min.y() <= max.y())) {
            throw new IllegalArgumentException("Box min is greater than max: " + min + ", " + max);
        }
    }

    @Override
    public boolean contains(Point2 p) {
        return p.x() >= min.x() && p.x() <= max.x()
                && p.y() >= min.y() && p.y() <= max.y();
    }
}
