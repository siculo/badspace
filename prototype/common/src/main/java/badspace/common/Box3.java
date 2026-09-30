package badspace.common;

/** An axis-aligned box of a 3D space, from its lowest corner to its highest corner. */
public record Box3(Point3 min, Point3 max) implements Region3 {

    /** Fails with IllegalArgumentException if min is greater than max on some axis. */
    public Box3 {
        if (!(min.x() <= max.x() && min.y() <= max.y() && min.z() <= max.z())) {
            throw new IllegalArgumentException("Box min is greater than max: " + min + ", " + max);
        }
    }

    @Override
    public boolean contains(Point3 p) {
        return p.x() >= min.x() && p.x() <= max.x()
                && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }
}
