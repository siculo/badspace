package badspace.common.geometry;

/** A sphere of a 3D space. */
public record Sphere3(Point3 center, double radius) implements Region3 {

    /** Fails with IllegalArgumentException if the radius is negative, infinite or not a number. */
    public Sphere3 {
        if (!(radius >= 0 && radius < Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("Bad radius: " + radius);
        }
    }

    @Override
    public boolean contains(Point3 p) {
        return p.distanceSquared(center) <= radius * radius;
    }
}
