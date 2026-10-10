package badspace.common.partition;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import java.util.List;

/**
 * Contract of a node that hosts partitions of a 3-dimensional space.
 * A node can be in the same process as the API or in a remote process.
 * A node serves a single space, so partition IDs never collide on a node.
 * Write operations work on many entities in one call, to reduce the number of
 * remote calls. Each write is all or nothing: if it fails, the partition does not change.
 * <p>
 * The reads of a partition by ID or by position see the state of its writer,
 * with the writes not yet committed, and only the writer of the partition can
 * call them. The other threads read the version of the last commit, from
 * {@link #lastVersion}.
 */
public interface PartitionNode3 {

    /** Creates an empty partition that uses the linear scan as index. Fails if the ID is already in use on this node. */
    default void createPartition(PartitionId partitionId) {
        createPartition(partitionId, IndexConfig.linearScan());
    }

    /** Creates an empty partition with the given index. Fails if the ID is already in use on this node. */
    void createPartition(PartitionId partitionId, IndexConfig index);

    /**
     * Removes an empty partition. Fails with IllegalArgumentException if the ID is unknown,
     * and with IllegalStateException if the partition still has entities.
     */
    void removePartition(PartitionId partitionId);

    /** Removes a partition together with its entities. Fails with IllegalArgumentException if the ID is unknown. */
    void dropPartition(PartitionId partitionId);

    /**
     * Adds new entities to a partition. The API generates the entity IDs, which must
     * not be used anywhere else. Fails with IllegalArgumentException if an ID is
     * already in the partition or appears twice in the list, or if a position is
     * outside the {@linkplain #limits limits} of the partition.
     */
    void insertAll(PartitionId partitionId, List<Entity3> entities);

    /**
     * Returns the entities with the given IDs, in the same order.
     * IDs that are not in the partition are skipped.
     */
    List<Entity3> getAll(PartitionId partitionId, long[] entityIds);

    /**
     * Sets the position of existing entities. Fails with NoSuchElementException
     * if an ID is not in the partition, and with IllegalArgumentException if a
     * position is outside the {@linkplain #limits limits} of the partition.
     * If an ID appears more than once, the last position wins.
     */
    void updateAll(PartitionId partitionId, List<Entity3> entities);

    /**
     * Removes entities from a partition. Fails with NoSuchElementException if an ID
     * is not in the partition, and with IllegalArgumentException if an ID appears twice.
     */
    void removeAll(PartitionId partitionId, long[] entityIds);

    /**
     * Returns the entities of a partition that are inside the region or on its border.
     * The order of the result is not defined.
     */
    List<Entity3> findInRegion(PartitionId partitionId, Region3 region);

    /**
     * Returns the entities of a partition that are nearest to a point, at most count of them.
     * The result is sorted by distance from the point; entities at the same distance are
     * sorted by ID. Fails with IllegalArgumentException if count is negative.
     */
    List<Entity3> findNearest(PartitionId partitionId, Point3 point, int count);

    /**
     * Closes the current commit of a partition with the given number. The number must be
     * greater than the last commit of the partition, but numbers can be skipped. Fails with
     * IllegalArgumentException if it is not greater; in this case the partition does not change.
     * A commit with no writes is valid. The commit publishes the version of the partition
     * at the commit, which {@link #lastVersion} then returns.
     */
    void commit(PartitionId partitionId, long commit);

    /**
     * Returns the version of the last commit of a partition: the empty version at commit 0
     * if the partition has no commit yet. The version never changes. Unlike the other reads,
     * any thread can call it.
     */
    PartitionVersion3 lastVersion(PartitionId partitionId);

    /** Returns the number of the last commit of a partition, or 0 if it has no commit yet. */
    long lastCommit(PartitionId partitionId);

    /** Returns the number of entities in a partition. */
    int size(PartitionId partitionId);

    /**
     * Returns the coordinates that the entities of a partition can have. The
     * index of the partition sets them: they are the same as
     * {@link IndexConfig#limits()} of the index chosen at creation.
     */
    CoordinateLimits limits(PartitionId partitionId);
}
