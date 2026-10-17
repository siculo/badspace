package badspace.api;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Ownership;
import badspace.common.partition.WriteToken;
import java.util.Optional;

/**
 * Operations shared by 2D and 3D partitions: the information about the partition and its
 * ownership. The acquisitions of the snapshots and the creation of the writer handle depend on
 * the number of dimensions, so they are only in Partition2 and Partition3. A partition has at
 * most one owner, its writer, which writes through a {@link PartitionWriter}; everyone, the
 * writer too, reads the snapshots. Any thread can call these operations.
 */
public interface Partition {

    /**
     * Returns the tick of the partition. While the space moves to a new tick, it can be less
     * than the {@linkplain Space#tick() tick of the space}. It is only information: to read a
     * snapshot, acquire it, since the window can change between the two calls.
     */
    long tick();

    /** Returns the tick at which the partition was created: the tick of the space at that moment. */
    long creationTick();

    /** Returns the configuration chosen when the partition was created. */
    PartitionConfig config();

    /**
     * Returns the coordinates that the entities of the partition can have.
     * The index chosen at creation sets them (see {@code IndexConfig.limits()}):
     * an insert or an update with a position outside them fails with
     * IllegalArgumentException. Queries have no limits.
     */
    CoordinateLimits limits();

    /**
     * Returns the state of the ownership: its generation and whether the partition has an
     * owner. A new partition has no owner, at generation 0. It is only a read: nobody is told
     * about the changes.
     */
    Ownership ownership();

    /**
     * Makes the caller the owner if the generation of the ownership is still
     * expectedGeneration: the generation grows by one, the writes not committed are discarded,
     * and the result is the token of the new generation. Otherwise the result is empty and
     * nothing changes. With the generation of a free partition it takes the partition; with the
     * generation of the token of the owner it passes the ownership to a new token, or takes it
     * away from the owner. The result is a token and not a writer handle, because who claims is
     * often not who writes: the token can go to another writer, which makes its handle with
     * {@code writer(token)}. Fails with IllegalArgumentException if expectedGeneration is
     * negative, and with IllegalStateException after the removal of the partition.
     */
    Optional<WriteToken> claim(long expectedGeneration);
}
