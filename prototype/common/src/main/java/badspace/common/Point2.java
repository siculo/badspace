package badspace.common;

/** A position in a 2D space. */
public record Point2(double x, double y) {

    /** Returns the squared distance from the other point. */
    public double distanceSquared(Point2 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return dx * dx + dy * dy;
    }
}
