package badspace.common;

/** A position in a 3D space. */
public record Point3(double x, double y, double z) {

    /**
     * Fails with IllegalArgumentException if a coordinate is NaN or infinite.
     * The position of an entity must also be in the
     * {@linkplain CoordinateLimits limits} of its partition; the points of the
     * queries can be outside.
     */
    public Point3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Coordinate is not finite: " + x + ", " + y + ", " + z);
        }
    }

    /** Returns the squared distance from the other point. */
    public double distanceSquared(Point3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
