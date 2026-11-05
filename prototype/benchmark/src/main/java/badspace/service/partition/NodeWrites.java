package badspace.service.partition;

import badspace.common.partition.Entity2;
import badspace.common.partition.Entity3;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.WriteToken;
import java.util.List;

/**
 * Gives the benchmarks the writes of the storage of a local node, one call for each batch,
 * without the commit: the node gets the writes only with its commit, but the benchmarks of
 * the writes measure the slots and the index alone, like before the writes went to the
 * commit. The writes stay in the state of the writer of the storage until {@link #commit},
 * which confirms them with an empty batch. This class is in the package of the service, but
 * in the benchmark module, so the code of the service does not change its visibility.
 */
public final class NodeWrites {

    private NodeWrites() {
    }

    /** Adds entities to a 2D partition; the IDs must not be in the partition. */
    public static void insertAll(PartitionNode2 node, WriteToken token, List<Entity2> entities) {
        storage(node, token).insertAll(token.generation(), entities);
    }

    /** Sets the positions of entities of a 2D partition. */
    public static void setAll(PartitionNode2 node, WriteToken token, List<Entity2> entities) {
        storage(node, token).setAll(token.generation(), entities);
    }

    /** Removes entities of a 2D partition. */
    public static void removeAll(PartitionNode2 node, WriteToken token, long[] entityIds) {
        storage(node, token).removeAll(token.generation(), entityIds);
    }

    /** Writes the metadata of entities of a 2D partition. */
    public static void updateMetadataAll(PartitionNode2 node, WriteToken token, List<MetadataWrite> writes) {
        storage(node, token).updateMetadataAll(token.generation(), writes);
    }

    /** Confirms the writes of a 2D partition made through this class. */
    public static void commit(PartitionNode2 node, WriteToken token) {
        node.commit(token, List.of());
    }

    /** Adds entities to a 3D partition; the IDs must not be in the partition. */
    public static void insertAll(PartitionNode3 node, WriteToken token, List<Entity3> entities) {
        storage(node, token).insertAll(token.generation(), entities);
    }

    /** Writes the metadata of entities of a 3D partition. */
    public static void updateMetadataAll(PartitionNode3 node, WriteToken token, List<MetadataWrite> writes) {
        storage(node, token).updateMetadataAll(token.generation(), writes);
    }

    /** Confirms the writes of a 3D partition made through this class. */
    public static void commit(PartitionNode3 node, WriteToken token) {
        node.commit(token, List.of());
    }

    private static PartitionStorage2 storage(PartitionNode2 node, WriteToken token) {
        return ((LocalPartitionNode2) node).writerStorage(token.partitionId());
    }

    private static PartitionStorage3 storage(PartitionNode3 node, WriteToken token) {
        return ((LocalPartitionNode3) node).writerStorage(token.partitionId());
    }
}
