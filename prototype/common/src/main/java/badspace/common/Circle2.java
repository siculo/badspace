package badspace.common;

/** A circle of a 2D space. */
public record Circle2(Point2 center, double radius) implements Region2 {

    /** Fails with IllegalArgumentException if the radius is negative or not a number. */
    public Circle2 {
        if (!(radius >= 0)) {
            throw new IllegalArgumentException("Bad radius: " + radius);
        }
    }

    @Override
    public boolean contains(Point2 p) {
        double dx = p.x() - center.x();
        double dy = p.y() - center.y();
        return dx * dx + dy * dy <= radius * radius;
    }
}
