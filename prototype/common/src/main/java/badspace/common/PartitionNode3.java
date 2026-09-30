package badspace.common;

import java.util.List;

/**
 * Contract of a node that hosts partitions of a 3-dimensional space.
 * A node can be in the same process as the API or in a remote process.
 * A node serves a single space, so partition IDs never collide on a node.
 * Write operations work on many entities in one call, to reduce the number of
 * remote calls. Each write is all or nothing: if it fails, the partition does not change.
 */
public interface PartitionNode3 {

    /** Creates an empty partition. Fails if the ID is already in use on this node. */
    void createPartition(PartitionId partition);

    /**
     * Removes an empty partition. Fails with IllegalArgumentException if the ID is unknown,
     * and with IllegalStateException if the partition still has entities.
     */
    void removePartition(PartitionId partition);

    /** Removes a partition together with its entities. Fails with IllegalArgumentException if the ID is unknown. */
    void dropPartition(PartitionId partition);

    /**
     * Adds new entities to a partition. The API generates the entity IDs, which must
     * not be used anywhere else. Fails with IllegalArgumentException if an ID is
     * already in the partition or appears twice in the list.
     */
    void insertAll(PartitionId partition, List<Entity3> entities);

    /**
     * Returns the entities with the given IDs, in the same order.
     * IDs that are not in the partition are skipped.
     */
    List<Entity3> getAll(PartitionId partition, long[] entityIds);

    /**
     * Sets the position of existing entities. Fails with NoSuchElementException
     * if an ID is not in the partition. If an ID appears more than once, the last position wins.
     */
    void updateAll(PartitionId partition, List<Entity3> entities);

    /**
     * Removes entities from a partition. Fails with NoSuchElementException if an ID
     * is not in the partition, and with IllegalArgumentException if an ID appears twice.
     */
    void removeAll(PartitionId partition, long[] entityIds);

    /**
     * Returns the entities of a partition that are inside the region or on its border.
     * The order of the result is not defined.
     */
    List<Entity3> findInRegion(PartitionId partition, Region3 region);

    /**
     * Returns the entities of a partition that are nearest to a point, at most count of them.
     * The result is sorted by distance from the point; entities at the same distance are
     * sorted by ID. Fails with IllegalArgumentException if count is negative.
     */
    List<Entity3> findNearest(PartitionId partition, Point3 point, int count);

    /** Returns the number of entities in a partition. */
    int size(PartitionId partition);
}
