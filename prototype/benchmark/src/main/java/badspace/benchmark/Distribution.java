package badspace.benchmark;

import badspace.common.geometry.Point2;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * How the entities are placed in the world, a square with side
 * {@link #WORLD_SIZE}. The world goes from 0 to {@link #WORLD_SIZE} on each
 * axis, except for the distributions with one cluster, which move it to put
 * the cluster on the origin or far from it (see {@link #worldMin()}). The
 * sources are reproducible: the same seed always gives the same points.
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
    COINCIDENT,

    /**
     * One dense group, with a normal distribution, in the center of a world
     * that is far from the origin, at {@link #FAR_CENTER} on each axis.
     */
    FAR_CLUSTER,

    /**
     * One dense group, with a normal distribution, on the origin, in the center
     * of the world. The origin is a border of the cells on all the axes, for any
     * cell size, so the group is split among the cells around it.
     */
    ORIGIN_CLUSTER,

    /**
     * One dense group, with a normal distribution, in the center of a world at
     * {@link #EDGE_CENTER} on each axis: 200 astronomical units, the edge of
     * the solar system scenario, with 1 unit = 1 meter. There the gap between
     * two doubles is about 4 mm, so short steps lose precision. Only the
     * indices with large limits accept it, for example a grid of quadtrees
     * with cells of 32768, so it is not in the default parameters.
     */
    EDGE_CLUSTER;

    public static final double WORLD_SIZE = 10_000;

    static final int CLUSTER_COUNT = 16;
    static final double CLUSTER_SPREAD = WORLD_SIZE / 100;
    static final double HOTSPOT_SHARE = 0.9;
    static final double HOTSPOT_AREA = 0.05;
    static final int CORRIDOR_COUNT = 8;
    static final double CORRIDOR_WIDTH = WORLD_SIZE / 1000;
    static final int COINCIDENT_POSITIONS = 1000;
    static final double FAR_CENTER = 1e8;

    /** 200 astronomical units, in meters. */
    static final double EDGE_CENTER = 200 * 149_597_870_700.0;

    /** Returns a new source of points with this distribution. */
    public Supplier<Point2> source(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        return switch (this) {
            case UNIFORM -> () -> randomPoint(random);
            case CLUSTERS -> clusters(random);
            case HOTSPOT -> hotspot(random);
            case CORRIDORS -> corridors(random);
            case COINCIDENT -> coincident(random);
            case FAR_CLUSTER, ORIGIN_CLUSTER, EDGE_CLUSTER -> oneCluster(random);
        };
    }

    /** Returns the lowest coordinate of the world on each axis. */
    double worldMin() {
        return switch (this) {
            case FAR_CLUSTER -> FAR_CENTER - WORLD_SIZE / 2;
            case ORIGIN_CLUSTER -> -WORLD_SIZE / 2;
            case EDGE_CLUSTER -> EDGE_CENTER - WORLD_SIZE / 2;
            default -> 0;
        };
    }

    /** Returns the highest coordinate of the world on each axis. */
    double worldMax() {
        return worldMin() + WORLD_SIZE;
    }

    /** Returns a point of the world, with the same probability everywhere. */
    Point2 randomPoint(SplittableRandom random) {
        return new Point2(worldMin() + random.nextDouble(WORLD_SIZE), worldMin() + random.nextDouble(WORLD_SIZE));
    }

    /** Returns the point, moved inside the world if it is outside. */
    Point2 clamp(double x, double y) {
        return new Point2(Math.clamp(x, worldMin(), worldMax()), Math.clamp(y, worldMin(), worldMax()));
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
            return CLUSTERS.clamp(
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
                : HOTSPOT.randomPoint(random);
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
            positions[i] = COINCIDENT.randomPoint(random);
        }
        return () -> positions[random.nextInt(positions.length)];
    }

    private Supplier<Point2> oneCluster(SplittableRandom random) {
        double center = (worldMin() + worldMax()) / 2;
        return () -> clamp(
                center + random.nextGaussian() * CLUSTER_SPREAD,
                center + random.nextGaussian() * CLUSTER_SPREAD);
    }
}
