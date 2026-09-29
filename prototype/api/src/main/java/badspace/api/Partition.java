package badspace.api;

/**
 * Operations shared by 2D and 3D partitions. Operations that depend on the
 * number of dimensions, like insert, are only in Partition2 and Partition3.
 */
public interface Partition {

    /** Returns the number of entities in the partition. */
    int size();
}
