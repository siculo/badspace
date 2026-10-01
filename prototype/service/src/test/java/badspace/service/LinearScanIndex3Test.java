package badspace.service;

/** Runs the index contract on the linear scan, against the brute-force model. */
class LinearScanIndex3Test extends SpatialIndex3Contract {

    @Override
    SpatialIndex3 createIndex(PartitionStorage3 storage) {
        return new LinearScanIndex3(storage);
    }
}
