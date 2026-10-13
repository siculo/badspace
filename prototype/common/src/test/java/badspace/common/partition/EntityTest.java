package badspace.common.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Point3;
import org.junit.jupiter.api.Test;

class EntityTest {

    @Test
    void withPositionKeepsTheId() {
        assertEquals(new Entity2(7, new Point2(5, 6)), new Entity2(7, new Point2(1, 2)).withPosition(new Point2(5, 6)));
        assertEquals(new Entity3(7, new Point3(5, 6, 7)),
                new Entity3(7, new Point3(1, 2, 3)).withPosition(new Point3(5, 6, 7)));
    }

    @Test
    void moveByMovesThePositionAndKeepsTheId() {
        assertEquals(new Entity2(7, new Point2(4, 1)), new Entity2(7, new Point2(1, 2)).moveBy(3, -1));
        assertEquals(new Entity3(7, new Point3(4, 1, 3.5)), new Entity3(7, new Point3(1, 2, 3)).moveBy(3, -1, 0.5));
    }

    @Test
    void moveByFailsIfThePositionIsNotFinite() {
        assertThrows(IllegalArgumentException.class,
                () -> new Entity2(7, new Point2(Double.MAX_VALUE, 0)).moveBy(Double.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Entity3(7, new Point3(0, 0, 0)).moveBy(0, 0, Double.NaN));
    }
}
