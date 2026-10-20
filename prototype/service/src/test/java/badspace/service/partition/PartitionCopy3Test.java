package badspace.service.partition;

import static badspace.common.partition.Entity3.entity;
import static badspace.common.partition.MetadataWrite.put;
import static badspace.service.partition.Owners.owner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Point3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.persistence.PartitionCopy3;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Checks the autonomous copy that the node makes of the content of a 3D snapshot, for the
 * persistence: it has the IDs, the positions and the metadata of the snapshot, and it does
 * not depend on the life of the snapshot.
 */
class PartitionCopy3Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);

    private LocalPartitionNode3 node;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode3();
        node.createPartition(P1, IndexConfig.uniformGrid(4));
    }

    @Test
    void copyHasTheIdsPositionsAndMetadataOfTheSnapshot() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 2, 3), entity(2, 4, 5, 6)));
        node.updateMetadataAll(owner(node, P1), List.of(put(1, "version", 7), put(1, "owner", -3)));
        node.commit(owner(node, P1));
        node.setTick(1);
        PartitionCopy3 copy = node.copyLatest(P1);
        assertEquals(0, copy.tick());
        assertEquals(2, copy.size());
        assertEquals(Map.of(1L, new Point3(1, 2, 3), 2L, new Point3(4, 5, 6)), positionsById(copy));
        assertEquals(Map.of(1L, Map.of("version", 7L, "owner", -3L), 2L, Map.of()), metadataById(copy));
    }

    @Test
    void copyOfAnEmptySnapshotHasNoEntities() {
        node.setTick(1);
        PartitionCopy3 copy = node.copyLatest(P1);
        assertEquals(0, copy.tick());
        assertEquals(0, copy.size());
    }

    @Test
    void copyAtATickChoosesThatSnapshot() {
        node.createPartition(P2, IndexConfig.linearScan(), 5);
        node.insertAll(owner(node, P2), List.of(entity(1, 1, 1, 1)));
        node.commit(owner(node, P2));
        node.setTick(1);
        node.insertAll(owner(node, P2), List.of(entity(2, 2, 2, 2)));
        node.commit(owner(node, P2));
        node.setTick(2);
        assertEquals(1, node.copy(P2, 0).size());
        assertEquals(2, node.copy(P2, 1).size());
    }

    @Test
    void copyDoesNotDependOnTheLifeOfTheSnapshot() {
        node.createPartition(P2, IndexConfig.linearScan(), 1);
        node.insertAll(owner(node, P2), List.of(entity(1, 1, 2, 3)));
        node.commit(owner(node, P2));
        node.setTick(1);
        PartitionCopy3 copy = node.copyLatest(P2);
        // The copy released its acquisition, so the partition can be dropped and destroyed.
        node.dropPartition(owner(node, P2));
        node.setTick(2);
        node.setTick(3);
        assertThrows(IllegalArgumentException.class, () -> node.tick(P2));
        // The copy still has the content, after the snapshot ended.
        assertEquals(1, copy.size());
        assertEquals(Map.of(1L, new Point3(1, 2, 3)), positionsById(copy));
    }

    @Test
    void copyFailsLikeAnAcquisitionWhenTheSnapshotIsNotAvailable() {
        assertReason(Reason.NOT_YET_PUBLISHED, () -> node.copyLatest(P1));
        assertReason(Reason.NOT_YET_PUBLISHED, () -> node.copy(P1, 0));
        node.createPartition(P2, IndexConfig.linearScan(), 1);
        node.setTick(1);
        node.setTick(2);
        assertReason(Reason.OUT_OF_RETENTION, () -> node.copy(P2, 0));
    }

    @Test
    void copyOfAnUnknownPartitionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> node.copyLatest(new PartitionId(99)));
        assertThrows(IllegalArgumentException.class, () -> node.copy(new PartitionId(99), 0));
    }

    /** Collects the positions of a copy by entity ID. */
    private static Map<Long, Point3> positionsById(PartitionCopy3 copy) {
        Map<Long, Point3> positions = new HashMap<>();
        for (int i = 0; i < copy.size(); i++) {
            positions.put(copy.entityId(i), copy.position(i));
        }
        return positions;
    }

    /** Collects the metadata of a copy by entity ID. */
    private static Map<Long, Map<String, Long>> metadataById(PartitionCopy3 copy) {
        Map<Long, Map<String, Long>> metadata = new HashMap<>();
        for (int i = 0; i < copy.size(); i++) {
            metadata.put(copy.entityId(i), copy.metadata(i));
        }
        return metadata;
    }

    /** Checks that the copy fails as not available, for the given reason. */
    private static void assertReason(Reason reason, Executable copy) {
        assertEquals(reason, assertThrows(SnapshotNotAvailableException.class, copy).reason());
    }
}
