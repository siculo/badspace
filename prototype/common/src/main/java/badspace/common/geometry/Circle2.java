package badspace.common.geometry;

/** A circle of a 2D space. */
public record Circle2(Point2 center, double radius) implements Region2 {

    /** Fails with IllegalArgumentException if the radius is negative, infinite or not a number. */
    public Circle2 {
        if (!(radius >= 0 && radius < Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("Bad radius: " + radius);
        }
    }

    @Override
    public boolean contains(Point2 p) {
        return p.distanceSquared(center) <= radius * radius;
    }
}
