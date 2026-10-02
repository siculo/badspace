package badspace.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.IndexConfig;
import org.junit.jupiter.api.Test;

class IndexNamesTest {

    @Test
    void parsesTheNamesOfTheIndices() {
        assertEquals(IndexConfig.linearScan(), IndexNames.parse("LINEAR_SCAN"));
        assertEquals(IndexConfig.uniformGrid(100), IndexNames.parse("UNIFORM_GRID_100"));
        assertEquals(IndexConfig.gridQuadtree(128), IndexNames.parse("GRID_QUADTREE_128"));
        assertEquals(IndexConfig.gridQuadtree(0.5, 32), IndexNames.parse("GRID_QUADTREE_0.5_32"));
    }

    @Test
    void defaultNamesAreValid() {
        IndexNames.DEFAULT.forEach(IndexNames::parse);
    }

    @Test
    void rejectsBadNames() {
        for (String name : new String[] {"", "QUADTREE", "UNIFORM_GRID_", "UNIFORM_GRID_x", "GRID_QUADTREE_",
                "GRID_QUADTREE_100", "GRID_QUADTREE_128_", "GRID_QUADTREE_128_0", "GRID_QUADTREE_128_16_2"}) {
            assertThrows(IllegalArgumentException.class, () -> IndexNames.parse(name), name);
        }
    }
}
