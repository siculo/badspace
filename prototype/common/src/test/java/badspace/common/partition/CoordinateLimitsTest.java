package badspace.common.partition;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Point3;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoordinateLimitsTest {

    private final CoordinateLimits limits = new CoordinateLimits(-2, 4);

    @Test
    void containsItsLimits() {
        assertTrue(limits.contains(-2));
        assertTrue(limits.contains(4));
        assertTrue(limits.contains(0));
        assertFalse(limits.contains(Math.nextDown(-2.0)));
        assertFalse(limits.contains(Math.nextUp(4.0)));
        assertFalse(limits.contains(Double.NaN));
        assertFalse(limits.contains(Double.POSITIVE_INFINITY));
    }

    @Test
    void containsAPointOnlyWithAllItsCoordinates() {
        assertTrue(limits.contains(new Point2(-2, 4)));
        assertFalse(limits.contains(new Point2(0, 5)));
        assertTrue(limits.contains(new Point3(-2, 0, 4)));
        assertFalse(limits.contains(new Point3(0, 0, -3)));
    }

    @Test
    void rejectsBadLimits() {
        assertThrows(IllegalArgumentException.class, () -> new CoordinateLimits(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CoordinateLimits(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new CoordinateLimits(0, Double.NaN));
    }
}
