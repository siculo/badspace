package badspace.service.index;

import badspace.common.partition.IndexConfig;

/**
 * Creates the spatial indices from their configuration. It is the only way
 * to create an index from outside this package, so the classes of the
 * indices stay hidden.
 */
public final class SpatialIndexes {

    private SpatialIndexes() {
    }

    /** Creates the 2D index of the configuration, which reads the given slots. */
    public static SpatialIndex2 create(IndexConfig config, SlotView2 slots) {
        return switch (config) {
            case IndexConfig.LinearScan _ -> new LinearScanIndex2(slots);
            case IndexConfig.UniformGrid g -> new UniformGridIndex2(slots, g.cellSize());
            case IndexConfig.GridQuadtree g -> new GridQuadtreeIndex2(slots, g.cellSize(), g.leafCapacity());
        };
    }

    /** Creates the 3D index of the configuration, which reads the given slots. */
    public static SpatialIndex3 create(IndexConfig config, SlotView3 slots) {
        return switch (config) {
            case IndexConfig.LinearScan _ -> new LinearScanIndex3(slots);
            case IndexConfig.UniformGrid g -> new UniformGridIndex3(slots, g.cellSize());
            case IndexConfig.GridQuadtree g -> new GridOctreeIndex3(slots, g.cellSize(), g.leafCapacity());
        };
    }
}
