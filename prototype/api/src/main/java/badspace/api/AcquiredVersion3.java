package badspace.api;

import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity3;
import badspace.common.partition.PartitionNode3;
import java.util.List;
import java.util.Optional;

/**
 * A version of a 3D partition that a reader acquired: the partition as it was
 * at a commit. The version never changes, so all its reads see the same commit
 * while the writer goes on, also after the version leaves the retention window.
 * Use it with try-with-resources: {@link #close()} releases the acquisition.
 * <p>
 * Each read checks the acquisition when it starts. After the release, or after the
 * timeout of the acquisition if it has one, a new read fails with IllegalStateException;
 * a read that started before ends normally, and the entities already read stay valid.
 * Any thread can use the view.
 */
public final class AcquiredVersion3 implements AutoCloseable {

    private final PartitionNode3 node;
    private final Acquisition acquisition;

    AcquiredVersion3(PartitionNode3 node, Acquisition acquisition) {
        this.node = node;
        this.acquisition = acquisition;
    }

    /**
     * Returns the number of the commit, or 0 for the empty version before the first commit.
     * It does not check the acquisition: the number is part of it.
     */
    public long commit() {
        return acquisition.commit();
    }

    /** Returns the number of entities. */
    public int size() {
        return node.size(acquisition);
    }

    /** Returns the position of an entity, or empty if the ID is not in the version. */
    public Optional<Point3> get(long entityId) {
        return getAll(new long[] {entityId}).stream().findFirst().map(Entity3::position);
    }

    /**
     * Returns the entities with the given IDs, in the same order.
     * IDs that are not in the version are skipped.
     */
    public List<Entity3> getAll(long[] entityIds) {
        return node.getAll(acquisition, entityIds);
    }

    /**
     * Returns the entities that are inside the region or on its border.
     * The order of the result is not defined.
     */
    public List<Entity3> findInRegion(Region3 region) {
        return node.findInRegion(acquisition, region);
    }

    /**
     * Returns the entities nearest to a point, at most count of them, sorted by distance
     * from the point and then by ID. Fails with IllegalArgumentException if count is negative.
     */
    public List<Entity3> findNearest(Point3 point, int count) {
        return node.findNearest(acquisition, point, count);
    }

    /** Releases the acquisition. Calling it again, or after the timeout, does nothing. */
    @Override
    public void close() {
        node.release(acquisition);
    }
}
