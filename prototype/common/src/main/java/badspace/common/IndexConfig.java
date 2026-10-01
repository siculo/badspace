package badspace.common;

/**
 * Spatial index of a partition, with its parameters, chosen when the partition
 * is created. All the indices give the same query results; they differ only in
 * speed and memory. New indices can be added later as new records.
 */
public sealed interface IndexConfig {

    /** No structure: each query reads all the entities of the partition. */
    record LinearScan() implements IndexConfig {
    }

    /**
     * A grid of square (2D) or cubic (3D) cells, all of the same size, that
     * covers the whole space. Only the cells with entities use memory.
     * Updates are cheap; queries are fast when the cell size is close to the
     * size of the regions and the distances of the queries.
     *
     * @param cellSize side of a cell; it must be positive and finite
     */
    record UniformGrid(double cellSize) implements IndexConfig {

        /** Fails with IllegalArgumentException if the cell size is not positive and finite. */
        public UniformGrid {
            if (!(cellSize > 0 && cellSize < Double.POSITIVE_INFINITY)) {
                throw new IllegalArgumentException("Bad cell size: " + cellSize);
            }
        }
    }

    /** Returns the linear scan. */
    static IndexConfig linearScan() {
        return new LinearScan();
    }

    /** Returns a uniform grid with the given cell size. */
    static IndexConfig uniformGrid(double cellSize) {
        return new UniformGrid(cellSize);
    }
}
