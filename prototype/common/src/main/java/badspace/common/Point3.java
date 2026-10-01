package badspace.common;

/** A position in a 3D space. */
public record Point3(double x, double y, double z) {

    /** Returns the squared distance from the other point. */
    public double distanceSquared(Point3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
