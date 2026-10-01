package badspace.benchmark;

import badspace.common.IndexConfig;
import java.util.List;

/**
 * Names of the indices in the parameters and in the results of the benchmarks.
 * A name gives the index and its parameters, for example {@code LINEAR_SCAN}
 * or {@code UNIFORM_GRID_100} (a uniform grid with cells of side 100).
 */
final class IndexNames {

    /** The indices that a run measures when the {@code index} parameter is not given. */
    static final List<String> DEFAULT = List.of("LINEAR_SCAN", "UNIFORM_GRID_100");

    private static final String UNIFORM_GRID = "UNIFORM_GRID_";

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
        throw new IllegalArgumentException("Unknown index: " + name);
    }
}
