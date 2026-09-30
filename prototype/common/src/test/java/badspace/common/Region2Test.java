package badspace.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Region2Test {

    @Test
    void boxContainsPointsInsideAndOnBorder() {
        Box2 box = new Box2(new Point2(0, 0), new Point2(2, 1));
        assertTrue(box.contains(new Point2(1, 0.5)));
        assertTrue(box.contains(new Point2(0, 0)));
        assertTrue(box.contains(new Point2(2, 1)));
        assertFalse(box.contains(new Point2(2.001, 0.5)));
        assertFalse(box.contains(new Point2(1, -0.001)));
    }

    @Test
    void boxRejectsMinGreaterThanMax() {
        assertThrows(IllegalArgumentException.class, () -> new Box2(new Point2(1, 0), new Point2(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> new Box2(new Point2(0, 1), new Point2(1, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Box2(new Point2(Double.NaN, 0), new Point2(1, 1)));
    }

    @Test
    void circleContainsPointsInsideAndOnBorder() {
        Circle2 circle = new Circle2(new Point2(1, 1), 2);
        assertTrue(circle.contains(new Point2(1, 1)));
        assertTrue(circle.contains(new Point2(3, 1)));
        assertTrue(circle.contains(new Point2(1, -1)));
        assertFalse(circle.contains(new Point2(2.5, 2.5)));
    }

    @Test
    void circleWithZeroRadiusContainsOnlyItsCenter() {
        Circle2 circle = new Circle2(new Point2(1, 1), 0);
        assertTrue(circle.contains(new Point2(1, 1)));
        assertFalse(circle.contains(new Point2(1, 1.001)));
    }

    @Test
    void circleRejectsBadRadius() {
        assertThrows(IllegalArgumentException.class, () -> new Circle2(new Point2(0, 0), -1));
        assertThrows(IllegalArgumentException.class, () -> new Circle2(new Point2(0, 0), Double.NaN));
    }
}
