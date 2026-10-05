package badspace.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Point2;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DistributionTest {

    private static final int COUNT = 10_000;

    private static List<Point2> points(Distribution distribution, long seed) {
        Supplier<Point2> source = distribution.source(seed);
        return Stream.generate(source).limit(COUNT).toList();
    }

    private static boolean inWorld(Distribution distribution, Point2 p) {
        return p.x() >= distribution.worldMin() && p.x() <= distribution.worldMax()
                && p.y() >= distribution.worldMin() && p.y() <= distribution.worldMax();
    }

    @ParameterizedTest
    @EnumSource(Distribution.class)
    void sameSeedGivesSamePoints(Distribution distribution) {
        assertEquals(points(distribution, 7), points(distribution, 7));
        assertNotEquals(points(distribution, 7), points(distribution, 8));
    }

    @ParameterizedTest
    @EnumSource(Distribution.class)
    void pointsAreInsideTheWorld(Distribution distribution) {
        assertTrue(points(distribution, 7).stream().allMatch(p -> inWorld(distribution, p)));
    }

    @ParameterizedTest
    @EnumSource(Distribution.class)
    void worldHasTheSameSizeForAllTheDistributions(Distribution distribution) {
        assertEquals(Distribution.WORLD_SIZE, distribution.worldMax() - distribution.worldMin());
    }

    @ParameterizedTest
    @EnumSource(value = Distribution.class, names = {"FAR_CLUSTER", "ORIGIN_CLUSTER", "EDGE_CLUSTER"})
    void oneClusterIsDenseAroundTheCenterOfTheWorld(Distribution distribution) {
        double center = (distribution.worldMin() + distribution.worldMax()) / 2;
        Point2 c = new Point2(center, center);
        double radius = 4 * Distribution.CLUSTER_SPREAD;
        long near = points(distribution, 7).stream()
                .filter(p -> p.distanceSquared(c) <= radius * radius)
                .count();
        assertTrue(near > COUNT * 0.99, "Points near the center: " + near);
    }

    @Test
    void clusterWorldsAreOnTheOriginAndFarFromIt() {
        assertEquals(0, (Distribution.ORIGIN_CLUSTER.worldMin() + Distribution.ORIGIN_CLUSTER.worldMax()) / 2);
        assertEquals(Distribution.FAR_CENTER,
                (Distribution.FAR_CLUSTER.worldMin() + Distribution.FAR_CLUSTER.worldMax()) / 2);
        assertEquals(Distribution.EDGE_CENTER,
                (Distribution.EDGE_CLUSTER.worldMin() + Distribution.EDGE_CLUSTER.worldMax()) / 2);
        // The cluster on the origin has points on each side of both axes.
        List<Point2> points = points(Distribution.ORIGIN_CLUSTER, 7);
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            boolean right = (quadrant & 1) == 1;
            boolean up = (quadrant & 2) == 2;
            assertTrue(points.stream().anyMatch(p -> (p.x() >= 0) == right && (p.y() >= 0) == up),
                    "No point in quadrant " + quadrant);
        }
    }

    @Test
    void coincidentPointsUseFewPositions() {
        assertTrue(new HashSet<>(points(Distribution.COINCIDENT, 7)).size() <= Distribution.COINCIDENT_POSITIONS);
    }

    @Test
    void uniformPointsAreAllDifferent() {
        assertEquals(COUNT, new HashSet<>(points(Distribution.UNIFORM, 7)).size());
    }

    @Test
    void hotspotPutsMostPointsInASmallArea() {
        // Cells of a quarter of the hotspot side: 5 x 5 cells always cover the whole hotspot.
        double cellSize = Distribution.WORLD_SIZE * Math.sqrt(Distribution.HOTSPOT_AREA) / 4;
        int cells = (int) Math.ceil(Distribution.WORLD_SIZE / cellSize) + 1;
        int[][] counts = new int[cells][cells];
        for (Point2 p : points(Distribution.HOTSPOT, 7)) {
            counts[(int) (p.x() / cellSize)][(int) (p.y() / cellSize)]++;
        }
        int best = 0;
        for (int i = 0; i + 5 <= cells; i++) {
            for (int j = 0; j + 5 <= cells; j++) {
                int sum = 0;
                for (int a = 0; a < 5; a++) {
                    for (int b = 0; b < 5; b++) {
                        sum += counts[i + a][j + b];
                    }
                }
                best = Math.max(best, sum);
            }
        }
        assertTrue(best > COUNT * 0.88, "Points in the hotspot: " + best);
    }

    @Test
    void corridorPointsAreInFewThinStrips() {
        // Each corridor covers at most two strips as wide as a corridor, on the axis across it.
        List<Point2> points = points(Distribution.CORRIDORS, 7);
        Set<Long> rows = fullestStrips(points.stream().map(Point2::y).toList());
        Set<Long> columns = fullestStrips(points.stream().map(Point2::x).toList());
        long inStrips = points.stream()
                .filter(p -> rows.contains(strip(p.y())) || columns.contains(strip(p.x())))
                .count();
        // A corridor can touch a strip only with a thin border: those few points can be missed.
        assertTrue(inStrips > COUNT * 0.99, "Points in the strips: " + inStrips);
    }

    private static long strip(double coordinate) {
        return (long) Math.floor(coordinate / Distribution.CORRIDOR_WIDTH);
    }

    /** Returns the strips with the most points, two for each corridor. */
    private static Set<Long> fullestStrips(List<Double> coordinates) {
        Map<Long, Long> counts = coordinates.stream()
                .collect(Collectors.groupingBy(DistributionTest::strip, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(2L * Distribution.CORRIDOR_COUNT)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    @Test
    void localMovementIsAStepInsideTheWorld() {
        Workload workload = Workload.generate(Distribution.UNIFORM, 10, 7);
        SplittableRandom random = new SplittableRandom(7);
        for (double step : List.of(1.0, 10.0, 400.0)) {
            for (Point2 from : List.of(new Point2(0, 0), new Point2(5000, 5000), new Point2(10_000, 10_000))) {
                for (int i = 0; i < 100; i++) {
                    Point2 to = Movement.LOCAL.move(from, step, workload, random);
                    assertTrue(inWorld(Distribution.UNIFORM, to));
                    assertTrue(Math.sqrt(to.distanceSquared(from)) <= step + 1e-9);
                }
            }
            // Far from the limits of the world the step is never cut.
            Point2 center = new Point2(5000, 5000);
            Point2 to = Movement.LOCAL.move(center, step, workload, random);
            assertEquals(step, Math.sqrt(to.distanceSquared(center)), 1e-9);
        }
    }

    @Test
    void localMovementAtTheEdgeIsRoundedToTheGapBetweenDoubles() {
        Workload workload = Workload.generate(Distribution.EDGE_CLUSTER, 10, 7);
        SplittableRandom random = new SplittableRandom(7);
        double center = Distribution.EDGE_CENTER;
        double gap = Math.ulp(center);
        assertEquals(0x1p-8, gap);
        Point2 from = new Point2(center, center);
        for (double step : List.of(10.0, 0.1, 0.01)) {
            for (int i = 0; i < 100; i++) {
                Point2 to = Movement.LOCAL.move(from, step, workload, random);
                // Each coordinate is rounded by at most half a gap.
                assertEquals(step, Math.sqrt(to.distanceSquared(from)), gap);
            }
        }
    }
}
