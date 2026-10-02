package badspace.common;

/** A position in a 2D space. */
public record Point2(double x, double y) {

    /**
     * Fails with IllegalArgumentException if a coordinate is NaN or infinite.
     * The position of an entity must also be in the
     * {@linkplain CoordinateLimits limits} of its partition; the points of the
     * queries can be outside.
     */
    public Point2 {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Coordinate is not finite: " + x + ", " + y);
        }
    }

    /** Returns the squared distance from the other point. */
    public double distanceSquared(Point2 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return dx * dx + dy * dy;
    }
}
