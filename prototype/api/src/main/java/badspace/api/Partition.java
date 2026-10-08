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
     * Closes the current commit with the number n, which the software chooses: for example
     * the tick. n must be greater than the {@linkplain #lastCommit() last commit}, but numbers
     * can be skipped. Fails with IllegalArgumentException if n is not greater; in this case
     * the partition does not change. A commit with no writes is valid.
     * For now a commit only moves the counter: writes are visible as soon as they are done.
     */
    void commit(long n);

    /** Returns the number of the last commit, or 0 if the partition has no commit yet. */
    long lastCommit();

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
