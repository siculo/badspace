package badspace.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
