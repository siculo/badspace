package badspace.service;

import org.junit.jupiter.api.Nested;

/**
 * Runs the index contract on the uniform grid, against the brute-force model.
 * The test coordinates are mostly between -10 and 10, sometimes up to 1e9, so
 * the cell sizes give few entities per cell, many entities per cell, and
 * cells at the edge of the int range.
 */
class UniformGridIndex3Test {

    @Nested
    class SmallCells extends SpatialIndex3Contract {

        @Override
        SpatialIndex3 createIndex(PartitionStorage3 storage) {
            return new UniformGridIndex3(storage, 0.3);
        }
    }

    @Nested
    class MediumCells extends SpatialIndex3Contract {

        @Override
        SpatialIndex3 createIndex(PartitionStorage3 storage) {
            return new UniformGridIndex3(storage, 4);
        }
    }

    @Nested
    class LargeCells extends SpatialIndex3Contract {

        @Override
        SpatialIndex3 createIndex(PartitionStorage3 storage) {
            return new UniformGridIndex3(storage, 1e6);
        }
    }
}
