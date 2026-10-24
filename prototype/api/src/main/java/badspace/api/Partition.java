package badspace.api;

import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.WriteToken;
import java.util.List;
import java.util.Optional;

/**
 * Operations shared by 2D and 3D partitions: the information about the partition, its
 * ownership and the saves of its snapshots. The acquisitions of the snapshots and the creation of the writer handle depend on
 * the number of dimensions, so they are only in Partition2 and Partition3. A partition has at
 * most one owner, its writer, which writes through a {@link PartitionWriter}; everyone, the
 * writer too, reads the snapshots. Any thread can call these operations.
 */
public interface Partition {

    /**
     * Returns the name of the partition, chosen at the creation: its public identifier, which
     * also identifies its saves.
     */
    PartitionName name();

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

    /**
     * Saves the snapshot of a tick to the save destination of the node of the partition, and
     * returns the reference to the save. The snapshot must be acquirable, like with
     * {@code acquire(tick)}: also a removed partition can be saved while the tick is in the
     * window. The call is synchronous: it returns when the save is complete, and it blocks
     * only the calling thread, while the writer and the readers continue. The node acquires
     * and copies the snapshot itself, so the call has no timeout options. A save of a tick
     * already saved replaces it with the same content; a save of a snapshot that another
     * thread is saving waits for that save and returns the same reference, or fails if that
     * save fails.
     * <p>
     * Fails with IllegalArgumentException if tick is negative; with
     * SnapshotNotAvailableException, with the reason, if the snapshot is not acquirable, and
     * then nothing is written; with IllegalStateException if the node has no save destination;
     * and with UncheckedIOException if the node cannot write the save: the saves already there
     * do not change.
     */
    SavedPartition save(long tick);

    /**
     * Saves the latest snapshot, the one of the tick before the tick of the partition, like
     * {@link #save(long)}. Fails with SnapshotNotAvailableException like
     * {@code acquireLatest()}.
     */
    SavedPartition saveLatest();

    /**
     * Returns the saves of the name of the partition on its node, sorted by tick, like
     * {@code Space.saves(node, name())}: also the saves of an earlier partition with the same
     * name, for example of an earlier run.
     */
    List<SavedPartition> saves();
}
