package badspace.benchmark;

import badspace.common.Point2;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * How the entities are placed in the world, a square that goes from 0 to
 * {@link #WORLD_SIZE} on each axis. The sources are reproducible: the same
 * seed always gives the same points.
 */
public enum Distribution {

    /** Every point of the world has the same probability. */
    UNIFORM,

    /** A few dense groups, with a normal distribution around their centers. */
    CLUSTERS,

    /** Most of the entities in a small square, the others uniform. */
    HOTSPOT,

    /** Thin horizontal and vertical strips, like roads or corridors. */
    CORRIDORS,

    /** A few positions, each shared by many entities. */
    COINCIDENT;

    public static final double WORLD_SIZE = 10_000;

    static final int CLUSTER_COUNT = 16;
    static final double CLUSTER_SPREAD = WORLD_SIZE / 100;
    static final double HOTSPOT_SHARE = 0.9;
    static final double HOTSPOT_AREA = 0.05;
    static final int CORRIDOR_COUNT = 8;
    static final double CORRIDOR_WIDTH = WORLD_SIZE / 1000;
    static final int COINCIDENT_POSITIONS = 1000;

    /** Returns a new source of points with this distribution. */
    public Supplier<Point2> source(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        return switch (this) {
            case UNIFORM -> () -> uniform(random);
            case CLUSTERS -> clusters(random);
            case HOTSPOT -> hotspot(random);
            case CORRIDORS -> corridors(random);
            case COINCIDENT -> coincident(random);
        };
    }

    /** Returns a point of the world, with the same probability everywhere. */
    static Point2 uniform(SplittableRandom random) {
        return new Point2(random.nextDouble(WORLD_SIZE), random.nextDouble(WORLD_SIZE));
    }

    /** Returns the point, moved inside the world if it is outside. */
    static Point2 clamp(double x, double y) {
        return new Point2(Math.clamp(x, 0, WORLD_SIZE), Math.clamp(y, 0, WORLD_SIZE));
    }

    private static Supplier<Point2> clusters(SplittableRandom random) {
        Point2[] centers = new Point2[CLUSTER_COUNT];
        for (int i = 0; i < centers.length; i++) {
            // Centers far from the border, so most of each cluster is inside the world.
            centers[i] = new Point2(
                    WORLD_SIZE * (0.1 + 0.8 * random.nextDouble()),
                    WORLD_SIZE * (0.1 + 0.8 * random.nextDouble()));
        }
        return () -> {
            Point2 c = centers[random.nextInt(centers.length)];
            return clamp(
                    c.x() + random.nextGaussian() * CLUSTER_SPREAD,
                    c.y() + random.nextGaussian() * CLUSTER_SPREAD);
        };
    }

    private static Supplier<Point2> hotspot(SplittableRandom random) {
        double side = WORLD_SIZE * Math.sqrt(HOTSPOT_AREA);
        double minX = random.nextDouble(WORLD_SIZE - side);
        double minY = random.nextDouble(WORLD_SIZE - side);
        return () -> random.nextDouble() < HOTSPOT_SHARE
                ? new Point2(minX + random.nextDouble(side), minY + random.nextDouble(side))
                : uniform(random);
    }

    private static Supplier<Point2> corridors(SplittableRandom random) {
        double[] offsets = new double[CORRIDOR_COUNT];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = random.nextDouble(WORLD_SIZE - CORRIDOR_WIDTH);
        }
        return () -> {
            int i = random.nextInt(offsets.length);
            double along = random.nextDouble(WORLD_SIZE);
            double across = offsets[i] + random.nextDouble(CORRIDOR_WIDTH);
            // Even corridors are horizontal, odd corridors are vertical.
            return i % 2 == 0 ? new Point2(along, across) : new Point2(across, along);
        };
    }

    private static Supplier<Point2> coincident(SplittableRandom random) {
        Point2[] positions = new Point2[COINCIDENT_POSITIONS];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = uniform(random);
        }
        return () -> positions[random.nextInt(positions.length)];
    }
}
