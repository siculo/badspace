package badspace.common.partition;

import java.util.Objects;

/**
 * The identifier of an acquisition: the right to read one version of a
 * partition, until it is released. The node that hosts the partition keeps
 * the acquisitions; this value only names one of them, so it can also go
 * to a remote process. Two acquisitions of the same version have different
 * identifiers and are independent.
 *
 * @param partitionId the partition of the acquired version
 * @param id the number of the acquisition, chosen by the node; it is never
 *        used again for the same partition
 * @param commit the commit of the acquired version
 */
public record Acquisition(PartitionId partitionId, long id, long commit) {

    /** Fails with NullPointerException if partitionId is null. */
    public Acquisition {
        Objects.requireNonNull(partitionId);
    }
}
