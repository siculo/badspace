package badspace.service;

import badspace.common.IndexConfig;
import org.junit.jupiter.api.Nested;

/**
 * Runs the index contract on the uniform grid, against the brute-force model.
 * The test coordinates are mostly between -10 and 10, sometimes up to 1e9 or
 * to the limits of the index, so the cell sizes give few entities per cell,
 * many entities per cell, and entities on the limits.
 */
class UniformGridIndex2Test {

    @Nested
    class SmallCells extends SpatialIndex2Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.uniformGrid(0.3);
        }
    }

    @Nested
    class MediumCells extends SpatialIndex2Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.uniformGrid(4);
        }
    }

    @Nested
    class LargeCells extends SpatialIndex2Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.uniformGrid(1e6);
        }
    }
}
