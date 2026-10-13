package badspace.common.geometry;

/** A position in a 3D space. */
public record Point3(double x, double y, double z) {

    /**
     * Fails with IllegalArgumentException if a coordinate is NaN or infinite.
     * The position of an entity must also be in the
     * {@linkplain badspace.common.partition.CoordinateLimits limits} of its partition; the points of the
     * queries can be outside.
     */
    public Point3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Coordinate is not finite: " + x + ", " + y + ", " + z);
        }
    }

    /**
     * Returns this point moved by dx on the x axis, by dy on the y axis and by dz on the
     * z axis. Fails with IllegalArgumentException if a coordinate of the result is not
     * finite. The result can be outside the limits of a partition: the write of the
     * entity checks them.
     */
    public Point3 add(double dx, double dy, double dz) {
        return new Point3(x + dx, y + dy, z + dz);
    }

    /** Returns the squared distance from the other point. */
    public double distanceSquared(Point3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
