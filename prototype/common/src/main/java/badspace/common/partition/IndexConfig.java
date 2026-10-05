package badspace.common.partition;

/**
 * Spatial index of a partition, with its parameters, chosen when the partition
 * is created. All the indices give the same query results; they differ only in
 * speed and memory, and in the {@linkplain #limits() limits} of the
 * coordinates that the entities can have. New indices can be added later as
 * new records.
 */
public sealed interface IndexConfig {

    /**
     * Number of cells on each side of the origin, on each axis, that the
     * indices with cells accept: their limits are this number of cells, so
     * the cell coordinates fit in an int with a large margin.
     */
    double CELLS_PER_SIDE = 0x1p30;

    /**
     * Returns the coordinates that the entities can have with this index. They
     * are never larger than {@link CoordinateLimits#MAX_ABS}.
     */
    CoordinateLimits limits();

    /**
     * No structure: each query reads all the entities of the partition.
     * Its limits are the largest ones, {@link CoordinateLimits#MAX_ABS}.
     */
    record LinearScan() implements IndexConfig {

        @Override
        public CoordinateLimits limits() {
            return CoordinateLimits.symmetric(CoordinateLimits.MAX_ABS);
        }
    }

    /**
     * A grid of square (2D) or cubic (3D) cells, all of the same size, that
     * covers the whole space. Only the cells with entities use memory.
     * Updates are cheap; queries are fast when the cell size is close to the
     * size of the regions and the distances of the queries.
     * Its limits are {@link #CELLS_PER_SIDE} cells on each side of the origin.
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

        @Override
        public CoordinateLimits limits() {
            return CoordinateLimits.symmetric(Math.min(CELLS_PER_SIDE * cellSize, CoordinateLimits.MAX_ABS));
        }
    }

    /**
     * A grid of square (2D) or cubic (3D) cells, like {@link UniformGrid},
     * where each cell keeps its entities in a quadtree (2D) or an octree (3D):
     * a node with more than {@code leafCapacity} entities is split in 4 or 8
     * equal parts, so the index adapts to the density. A node becomes a leaf
     * again when it has {@code leafCapacity / 2} entities or fewer.
     * <p>
     * The cell size is a power of 2, so the borders of the cells and of their
     * parts are exact with doubles. Its limits are {@link #CELLS_PER_SIDE}
     * cells on each side of the origin.
     *
     * @param cellSize side of a cell; it must be a power of 2, from
     *        {@link #MIN_CELL_SIZE} to {@link #MAX_CELL_SIZE}
     * @param leafCapacity entities in a leaf before it is split; it must be positive
     */
    record GridQuadtree(double cellSize, int leafCapacity) implements IndexConfig {

        /** Smallest cell size: 2^-30. */
        public static final double MIN_CELL_SIZE = 0x1p-30;

        /** Largest cell size: 2^30. */
        public static final double MAX_CELL_SIZE = 0x1p30;

        /** Leaf capacity when it is not given. */
        public static final int DEFAULT_LEAF_CAPACITY = 16;

        /** Fails with IllegalArgumentException if a parameter is not valid. */
        public GridQuadtree {
            if (!(cellSize >= MIN_CELL_SIZE && cellSize <= MAX_CELL_SIZE)
                    || cellSize != Math.scalb(1.0, Math.getExponent(cellSize))) {
                throw new IllegalArgumentException("Cell size is not a power of 2 from 2^-30 to 2^30: " + cellSize);
            }
            if (leafCapacity < 1) {
                throw new IllegalArgumentException("Bad leaf capacity: " + leafCapacity);
            }
        }

        @Override
        public CoordinateLimits limits() {
            return CoordinateLimits.symmetric(Math.min(CELLS_PER_SIDE * cellSize, CoordinateLimits.MAX_ABS));
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

    /** Returns a grid of quadtrees or octrees with the given cell size and the default leaf capacity. */
    static IndexConfig gridQuadtree(double cellSize) {
        return new GridQuadtree(cellSize, GridQuadtree.DEFAULT_LEAF_CAPACITY);
    }

    /** Returns a grid of quadtrees or octrees with the given cell size and leaf capacity. */
    static IndexConfig gridQuadtree(double cellSize, int leafCapacity) {
        return new GridQuadtree(cellSize, leafCapacity);
    }
}
