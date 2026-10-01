package badspace.service;

/** Runs the index contract on the linear scan, against the brute-force model. */
class LinearScanIndex2Test extends SpatialIndex2Contract {

    @Override
    SpatialIndex2 createIndex(PartitionStorage2 storage) {
        return new LinearScanIndex2(storage);
    }
}
