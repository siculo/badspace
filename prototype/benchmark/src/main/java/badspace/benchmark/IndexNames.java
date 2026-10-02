package badspace.benchmark;

import badspace.common.IndexConfig;
import java.util.List;

/**
 * Names of the indices in the parameters and in the results of the benchmarks.
 * A name gives the index and its parameters, for example {@code LINEAR_SCAN},
 * {@code UNIFORM_GRID_100} (a uniform grid with cells of side 100),
 * {@code GRID_QUADTREE_128} (a grid of quadtrees with cells of side 128 and
 * the default leaf capacity) or {@code GRID_QUADTREE_128_32} (the same with
 * leaves of 32 entities).
 */
final class IndexNames {

    /** The indices that a run measures when the {@code index} parameter is not given. */
    static final List<String> DEFAULT = List.of("LINEAR_SCAN", "UNIFORM_GRID_100");

    private static final String UNIFORM_GRID = "UNIFORM_GRID_";
    private static final String GRID_QUADTREE = "GRID_QUADTREE_";

    private IndexNames() {
    }

    /** Returns the index with the name. Fails with IllegalArgumentException if the name is not valid. */
    static IndexConfig parse(String name) {
        if (name.equals("LINEAR_SCAN")) {
            return IndexConfig.linearScan();
        }
        if (name.startsWith(UNIFORM_GRID)) {
            try {
                return IndexConfig.uniformGrid(Double.parseDouble(name.substring(UNIFORM_GRID.length())));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad cell size in index name: " + name, e);
            }
        }
        if (name.startsWith(GRID_QUADTREE)) {
            String[] parts = name.substring(GRID_QUADTREE.length()).split("_", -1);
            try {
                double cellSize = Double.parseDouble(parts[0]);
                if (parts.length == 1) {
                    return IndexConfig.gridQuadtree(cellSize);
                }
                if (parts.length == 2) {
                    return IndexConfig.gridQuadtree(cellSize, Integer.parseInt(parts[1]));
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad parameters in index name: " + name, e);
            }
            throw new IllegalArgumentException("Too many parameters in index name: " + name);
        }
        throw new IllegalArgumentException("Unknown index: " + name);
    }
}
