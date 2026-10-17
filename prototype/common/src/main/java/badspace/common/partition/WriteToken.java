package badspace.common.partition;

import java.util.Objects;

/**
 * The write token (fencing token) of a partition: the owner of the partition has the
 * token of the current generation of the {@link Ownership}. The node gives a token
 * when someone takes the ownership; this value only names it, so it can also go to
 * another writer or to a remote process. Partition IDs are never used again and the
 * generation only grows, so a token that is not current never becomes current again.
 *
 * @param partitionId the partition of the token
 * @param generation the generation of the ownership that the token names; the first
 *        owner of a partition has generation 1
 */
public record WriteToken(PartitionId partitionId, long generation) {

    /**
     * Fails with NullPointerException if partitionId is null, and with
     * IllegalArgumentException if generation is less than 1: generation 0 is the
     * state of a new partition, which has no owner.
     */
    public WriteToken {
        Objects.requireNonNull(partitionId);
        if (generation < 1) {
            throw new IllegalArgumentException("Generation of a token is less than 1: " + generation);
        }
    }
}
