package badspace.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Region3Test {

    @Test
    void boxContainsPointsInsideAndOnBorder() {
        Box3 box = new Box3(new Point3(0, 0, 0), new Point3(2, 1, 3));
        assertTrue(box.contains(new Point3(1, 0.5, 1)));
        assertTrue(box.contains(new Point3(0, 0, 0)));
        assertTrue(box.contains(new Point3(2, 1, 3)));
        assertFalse(box.contains(new Point3(1, 0.5, 3.001)));
        assertFalse(box.contains(new Point3(-0.001, 0.5, 1)));
    }

    @Test
    void boxRejectsMinGreaterThanMax() {
        assertThrows(IllegalArgumentException.class, () -> new Box3(new Point3(0, 0, 1), new Point3(1, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Box3(new Point3(0, Double.NaN, 0), new Point3(1, 1, 1)));
    }

    @Test
    void sphereContainsPointsInsideAndOnBorder() {
        Sphere3 sphere = new Sphere3(new Point3(1, 1, 1), 2);
        assertTrue(sphere.contains(new Point3(1, 1, 1)));
        assertTrue(sphere.contains(new Point3(1, 1, 3)));
        assertTrue(sphere.contains(new Point3(-1, 1, 1)));
        assertFalse(sphere.contains(new Point3(2.5, 2.5, 1)));
    }

    @Test
    void sphereRejectsBadRadius() {
        assertThrows(IllegalArgumentException.class, () -> new Sphere3(new Point3(0, 0, 0), -1));
        assertThrows(IllegalArgumentException.class, () -> new Sphere3(new Point3(0, 0, 0), Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new Sphere3(new Point3(0, 0, 0), Double.POSITIVE_INFINITY));
    }
}
