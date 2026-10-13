package badspace.common.geometry;

/** A position in a 2D space. */
public record Point2(double x, double y) {

    /**
     * Fails with IllegalArgumentException if a coordinate is NaN or infinite.
     * The position of an entity must also be in the
     * {@linkplain badspace.common.partition.CoordinateLimits limits} of its partition; the points of the
     * queries can be outside.
     */
    public Point2 {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Coordinate is not finite: " + x + ", " + y);
        }
    }

    /**
     * Returns the point (x, y): the same as the constructor, but shorter to use with an
     * import static, for example in tests and clients. Fails like the constructor.
     */
    public static Point2 point(double x, double y) {
        return new Point2(x, y);
    }

    /**
     * Returns this point moved by dx on the x axis and by dy on the y axis. Fails with
     * IllegalArgumentException if a coordinate of the result is not finite. The result
     * can be outside the limits of a partition: the write of the entity checks them.
     */
    public Point2 add(double dx, double dy) {
        return new Point2(x + dx, y + dy);
    }

    /** Returns the squared distance from the other point. */
    public double distanceSquared(Point2 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return dx * dx + dy * dy;
    }
}
