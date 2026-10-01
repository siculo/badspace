package badspace.service;

import org.junit.jupiter.api.Nested;

/**
 * Runs the index contract on the uniform grid, against the brute-force model.
 * The test coordinates are mostly between -10 and 10, sometimes up to 1e9, so
 * the cell sizes give few entities per cell, many entities per cell, and
 * cells at the edge of the int range.
 */
class UniformGridIndex2Test {

    @Nested
    class SmallCells extends SpatialIndex2Contract {

        @Override
        SpatialIndex2 createIndex(PartitionStorage2 storage) {
            return new UniformGridIndex2(storage, 0.3);
        }
    }

    @Nested
    class MediumCells extends SpatialIndex2Contract {

        @Override
        SpatialIndex2 createIndex(PartitionStorage2 storage) {
            return new UniformGridIndex2(storage, 4);
        }
    }

    @Nested
    class LargeCells extends SpatialIndex2Contract {

        @Override
        SpatialIndex2 createIndex(PartitionStorage2 storage) {
            return new UniformGridIndex2(storage, 1e6);
        }
    }
}
