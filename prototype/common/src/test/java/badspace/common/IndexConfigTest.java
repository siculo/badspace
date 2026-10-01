package badspace.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IndexConfigTest {

    @Test
    void uniformGridKeepsItsCellSize() {
        assertEquals(new IndexConfig.UniformGrid(2.5), IndexConfig.uniformGrid(2.5));
        assertEquals(2.5, ((IndexConfig.UniformGrid) IndexConfig.uniformGrid(2.5)).cellSize());
    }

    @Test
    void uniformGridRejectsBadCellSizes() {
        for (double cellSize : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> IndexConfig.uniformGrid(cellSize));
        }
    }
}
