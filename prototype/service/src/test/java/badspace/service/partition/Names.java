package badspace.service.partition;

import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;

/**
 * Gives the tests of the node a name for each partition ID: in these tests the name is only a
 * label, so it comes from the ID.
 */
final class Names {

    private Names() {
    }

    /** Returns the name of the partition with the given ID in the tests: "p" and the ID. */
    static PartitionName nameOf(PartitionId partitionId) {
        return new PartitionName("p" + partitionId.value());
    }
}
