package badspace.api;

import badspace.common.partition.CoordinateLimits;

/**
 * Operations shared by 2D and 3D partitions. Operations that depend on the
 * number of dimensions, like insert, get and update, are only in Partition2 and Partition3.
 */
public interface Partition {

    /** Removes an entity. Fails with NoSuchElementException if the ID is not in the partition. */
    void remove(long entityId);

    /**
     * Removes many entities in one call. Fails with NoSuchElementException if an ID is not
     * in the partition, and with IllegalArgumentException if an ID appears twice.
     * If it fails, no entity is removed.
     */
    void removeAll(long[] entityIds);

    /**
     * Confirms the writes in the current tick of the partition: the snapshot of the tick will
     * have them. A later commit in the same tick replaces it, and the writes after the last
     * commit go to the next commit, also in a later tick. A commit with no writes is valid.
     * The snapshot becomes acquirable when the tick moves forward, with {@code acquire(tick)}
     * and {@code acquireLatest()} of Partition2 and Partition3.
     */
    void commit();

    /**
     * Returns the tick of the partition. While the space moves to a new tick, it can be less
     * than the {@linkplain Space#tick() tick of the space}. Any thread can call it. It is only
     * information: to read a snapshot, acquire it, since the window can change between the
     * two calls.
     */
    long tick();

    /** Returns the tick at which the partition was created: the tick of the space at that moment. */
    long creationTick();

    /** Returns the configuration chosen when the partition was created. */
    PartitionConfig config();

    /** Returns the number of entities in the partition. */
    int size();

    /**
     * Returns the coordinates that the entities of the partition can have.
     * The index chosen at creation sets them (see {@code IndexConfig.limits()}):
     * an insert or an update with a position outside them fails with
     * IllegalArgumentException. Queries have no limits.
     */
    CoordinateLimits limits();
}
