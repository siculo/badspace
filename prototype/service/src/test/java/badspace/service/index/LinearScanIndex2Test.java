package badspace.service.index;

import badspace.common.partition.IndexConfig;

/** Runs the index contract on the linear scan, against the brute-force model. */
class LinearScanIndex2Test extends SpatialIndex2Contract {

    @Override
    IndexConfig index() {
        return IndexConfig.linearScan();
    }
}
