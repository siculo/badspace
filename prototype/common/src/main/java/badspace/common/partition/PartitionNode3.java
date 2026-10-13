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
 * call them. The other threads read the versions of the commits: they
 * {@linkplain #acquireLatest acquire} a version, read it through the
 * {@link Acquisition} and then {@linkplain #release release} it. The partition
 * keeps the last version and k versions before it acquirable (the retention
 * window); a version that leaves the window stays readable through the
 * acquisitions made before, until they are released. The acquisitions, the
 * reads through them and the release are thread-safe.
 */
public interface PartitionNode3 {

    /** Creates an empty partition that uses the linear scan as index. Fails if the ID is already in use on this node. */
    default void createPartition(PartitionId partitionId) {
        createPartition(partitionId, IndexConfig.linearScan());
    }

    /**
     * Creates an empty partition with the given index and the default number of past versions.
     * Fails if the ID is already in use on this node.
     */
    default void createPartition(PartitionId partitionId, IndexConfig index) {
        createPartition(partitionId, index, RetentionWindow.DEFAULT_RETAINED_PAST_VERSIONS);
    }

    /**
     * Creates an empty partition with the given index. The partition keeps the last version and
     * retainedPastVersions versions before it. Fails with IllegalArgumentException if the ID is
     * already in use on this node or if retainedPastVersions is negative; in these cases no
     * partition is created.
     */
    void createPartition(PartitionId partitionId, IndexConfig index, int retainedPastVersions);

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
     * at the commit, which {@link #acquireLatest} then acquires.
     */
    void commit(PartitionId partitionId, long commit);

    /**
     * Returns the version of the last commit of a partition: the empty version at commit 0
     * if the partition has no commit yet. The version never changes. Unlike the other reads,
     * any thread can call it.
     */
    PartitionVersion3 lastVersion(PartitionId partitionId);

    /**
     * Acquires the version of the last commit of a partition: the empty version at
     * commit 0 if the partition has no commit yet. Choosing the version and acquiring
     * it is a single step, so a commit at the same time cannot make it go away.
     */
    Acquisition acquireLatest(PartitionId partitionId);

    /**
     * Acquires a version in the retention window of a partition: offset 0 is the last
     * version, offset i the i-th published commit before it. Fails with
     * IllegalArgumentException if offset is negative, and with
     * VersionNotAvailableException if the window has no version at that offset.
     */
    Acquisition acquirePrevious(PartitionId partitionId, int offset);

    /**
     * Acquires the version in the retention window of a partition with the greatest
     * commit that is not greater than tick. Fails with IllegalArgumentException if
     * tick is negative, and with VersionNotAvailableException if no version in the
     * window has such a commit.
     */
    Acquisition acquireAtOrBefore(PartitionId partitionId, long tick);

    /**
     * Acquires the version of the given commit of a partition. Fails with
     * IllegalArgumentException if commit is negative, and with
     * VersionNotAvailableException if that version is not in the retention window.
     */
    Acquisition acquireExact(PartitionId partitionId, long commit);

    /**
     * Releases an acquisition: after this, its reads fail. Releasing it again does
     * nothing. Fails with IllegalArgumentException if the partition never made the
     * acquisition; the other acquisitions do not change.
     */
    void release(Acquisition acquisition);

    /**
     * Returns the number of entities in the acquired version. Fails with
     * IllegalStateException if the acquisition was released, and with
     * IllegalArgumentException if the partition never made it.
     */
    int size(Acquisition acquisition);

    /**
     * Returns the entities of the acquired version with the given IDs, in the same order.
     * IDs that are not in the version are skipped. Fails like {@link #size(Acquisition)}.
     */
    List<Entity3> getAll(Acquisition acquisition, long[] entityIds);

    /**
     * Returns the entities of the acquired version that are inside the region or on its
     * border. The order of the result is not defined. Fails like {@link #size(Acquisition)}.
     */
    List<Entity3> findInRegion(Acquisition acquisition, Region3 region);

    /**
     * Returns the entities of the acquired version nearest to a point, at most count of
     * them, sorted by distance from the point and then by ID. Fails like
     * {@link #size(Acquisition)}, and with IllegalArgumentException if count is negative.
     */
    List<Entity3> findNearest(Acquisition acquisition, Point3 point, int count);

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
