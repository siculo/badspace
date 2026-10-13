package badspace.common.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PointTest {

    @Test
    void distanceSquaredIn2D() {
        Point2 a = new Point2(1, 2);
        Point2 b = new Point2(4, 6);
        assertEquals(25, a.distanceSquared(b));
        assertEquals(25, b.distanceSquared(a));
        assertEquals(0, a.distanceSquared(a));
    }

    @Test
    void distanceSquaredIn3D() {
        Point3 a = new Point3(1, 2, 3);
        Point3 b = new Point3(3, 5, 9);
        assertEquals(49, a.distanceSquared(b));
        assertEquals(49, b.distanceSquared(a));
        assertEquals(0, a.distanceSquared(a));
    }

    @Test
    void addMovesThePointIn2D() {
        assertEquals(new Point2(4, 1), new Point2(1, 2).add(3, -1));
        assertEquals(new Point2(1, 2), new Point2(1, 2).add(0, 0));
    }

    @Test
    void addMovesThePointIn3D() {
        assertEquals(new Point3(4, 1, 3.5), new Point3(1, 2, 3).add(3, -1, 0.5));
        assertEquals(new Point3(1, 2, 3), new Point3(1, 2, 3).add(0, 0, 0));
    }

    @Test
    void addFailsIfTheResultIsNotFinite() {
        assertThrows(IllegalArgumentException.class, () -> new Point2(Double.MAX_VALUE, 0).add(Double.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Point2(0, 0).add(0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new Point3(0, 0, -Double.MAX_VALUE).add(0, 0, -Double.MAX_VALUE));
    }

    @Test
    void pointsRejectCoordinatesThatAreNotFinite() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Point2(bad, 0));
            assertThrows(IllegalArgumentException.class, () -> new Point2(0, bad));
            assertThrows(IllegalArgumentException.class, () -> new Point3(bad, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new Point3(0, bad, 0));
            assertThrows(IllegalArgumentException.class, () -> new Point3(0, 0, bad));
        }
    }

    @Test
    void pointsAcceptTheLargestFiniteCoordinates() {
        assertEquals(Double.MAX_VALUE, new Point2(Double.MAX_VALUE, -Double.MAX_VALUE).x());
        assertEquals(-Double.MAX_VALUE, new Point3(0, 0, -Double.MAX_VALUE).z());
    }
}
