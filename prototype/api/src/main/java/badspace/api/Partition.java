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

    /** Returns the removal policy chosen when the partition was created. */
    RemovalPolicy removalPolicy();

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
