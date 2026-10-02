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

    @Test
    void gridQuadtreeKeepsItsParameters() {
        assertEquals(new IndexConfig.GridQuadtree(0.25, IndexConfig.GridQuadtree.DEFAULT_LEAF_CAPACITY),
                IndexConfig.gridQuadtree(0.25));
        assertEquals(new IndexConfig.GridQuadtree(128, 4), IndexConfig.gridQuadtree(128, 4));
        assertEquals(new IndexConfig.GridQuadtree(0x1p-30, 1), IndexConfig.gridQuadtree(0x1p-30, 1));
        assertEquals(new IndexConfig.GridQuadtree(0x1p30, 1), IndexConfig.gridQuadtree(0x1p30, 1));
    }

    @Test
    void gridQuadtreeRejectsCellSizesThatAreNotPowersOf2InTheRange() {
        for (double cellSize : new double[] {0, -1, -2, 3, 100, Math.nextUp(1.0), 0x1p-31, 0x1p31,
                Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> IndexConfig.gridQuadtree(cellSize));
        }
    }

    @Test
    void gridQuadtreeRejectsBadLeafCapacities() {
        assertThrows(IllegalArgumentException.class, () -> IndexConfig.gridQuadtree(1, 0));
        assertThrows(IllegalArgumentException.class, () -> IndexConfig.gridQuadtree(1, -1));
    }
}
