package badspace.common;

/**
 * The coordinates that the entities of a partition can have: from min to max,
 * limits included, the same on each axis. The index of the partition sets
 * them (see {@link IndexConfig#limits()}), and a write with a position outside
 * them fails. Queries have no limits: their regions and points can be outside.
 */
public record CoordinateLimits(double min, double max) {

    /**
     * The largest absolute value of a coordinate, with any index: 2^60. The
     * squared distances between entities stay finite.
     */
    public static final double MAX_ABS = 0x1p60;

    /** Fails with IllegalArgumentException if min is greater than max or a limit is NaN. */
    public CoordinateLimits {
        if (!(min <= max)) {
            throw new IllegalArgumentException("Bad limits: " + min + ", " + max);
        }
    }

    /** Returns the limits from -limit to limit. */
    public static CoordinateLimits symmetric(double limit) {
        return new CoordinateLimits(-limit, limit);
    }

    /** Returns true if the coordinate is between the limits, limits included. */
    public boolean contains(double coordinate) {
        return coordinate >= min && coordinate <= max;
    }

    /** Returns true if all the coordinates of the point are between the limits. */
    public boolean contains(Point2 p) {
        return contains(p.x()) && contains(p.y());
    }

    /** Returns true if all the coordinates of the point are between the limits. */
    public boolean contains(Point3 p) {
        return contains(p.x()) && contains(p.y()) && contains(p.z());
    }
}
