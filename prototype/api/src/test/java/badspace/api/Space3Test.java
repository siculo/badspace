package badspace.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import badspace.common.geometry.Box3;
import badspace.common.geometry.Point3;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.ConditionalWrite3;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataCondition;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.WriteToken;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Checks API decisions and delegation without implementing the node contract again. */
class Space3Test {
    private static final PartitionId PARTITION_ID = new PartitionId(101);
    /** A token of the partition, as the node gives it to the first owner. */
    private static final WriteToken TOKEN = new WriteToken(PARTITION_ID, 1);
    private static final PartitionName NAME = new PartitionName("p");
    private final AtomicLong entityIds = new AtomicLong(10);
    private final AtomicLong partitionIds = new AtomicLong(100);
    private final SpaceConfig config = SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet);
    private final Space3 space = new Space3(config);
    private final PartitionNode3 node = mock(PartitionNode3.class);

    private static final Point3 A = new Point3(1, 2, 3);
    private static final Point3 B = new Point3(5, 6, 7);

    @Test
    void partitionCreationPassesTheGeneratedIdAndConfiguration() {
        PartitionConfig chosen = PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(4)).withRetainedTicks(3);
        Partition3 partition = space.createPartition(node, NAME, chosen);
        assertEquals(chosen, partition.config());
        var order = inOrder(node);
        order.verify(node).setTick(0);
        order.verify(node).createPartition(PARTITION_ID, NAME, chosen.index(), 3);
        order.verifyNoMoreInteractions();
    }

    @Test
    void nameIsPassedToTheNodeAndKeptByThePartition() {
        Partition3 partition = space.createPartition(node, new PartitionName("north"));
        assertEquals(new PartitionName("north"), partition.name());
        verify(node).createPartition(PARTITION_ID, new PartitionName("north"), IndexConfig.linearScan(), 1);
    }

    @Test
    void defaultConfigurationIsPassedToTheNode() {
        Partition3 partition = space.createPartition(node, NAME);
        assertEquals(PartitionConfig.defaults(), partition.config());
        verify(node).createPartition(PARTITION_ID, NAME, IndexConfig.linearScan(), 1);
    }

    @Test
    void insertAllMakesOneBatchWithGeneratedIdsInPositionOrder() {
        PartitionWriter3 writer = space.createPartition(node, NAME).writer(TOKEN);
        clearInvocations(node);
        assertArrayEquals(new long[] {11, 12}, writer.insertAll(List.of(A, B)));
        verify(node).insertAll(TOKEN, List.of(new Entity3(11, A), new Entity3(12, B)));
        verifyNoMoreInteractions(node);
    }

    @Test
    void removalUsesTheConfiguredPolicy() {
        for (RemovalPolicy policy : RemovalPolicy.values()) {
            Partition3 partition = space.createPartition(node, NAME, PartitionConfig.defaults().withRemovalPolicy(policy));
            PartitionId id = new PartitionId(partitionIds.get());
            clearInvocations(node);
            partition.writer(new WriteToken(id, 1)).removePartition();
            if (policy == RemovalPolicy.REQUIRE_EMPTY) {
                verify(node).removePartition(new WriteToken(id, 1));
            } else {
                verify(node).dropPartition(new WriteToken(id, 1));
            }
            verifyNoMoreInteractions(node);
        }
    }

    @Test
    void failedRemovalLeavesTheWriterUsable() {
        PartitionWriter3 writer = space.createPartition(node, NAME).writer(TOKEN);
        doThrow(new IllegalStateException("Partition is not empty")).when(node).removePartition(TOKEN);
        assertThrows(IllegalStateException.class, writer::removePartition);
        writer.insert(A);
        verify(node).insertAll(TOKEN, List.of(new Entity3(11, A)));
    }



    @Test
    void acquisitionsPassDefaultAndExplicitTimeouts() {
        Partition3 partition = space.createPartition(node, NAME);
        AcquireOptions options = AcquireOptions.timeout(Duration.ofSeconds(5));
        Acquisition acquisition = new Acquisition(PARTITION_ID, 7, 2);
        when(node.acquireLatest(PARTITION_ID, AcquireOptions.defaultTimeout())).thenReturn(acquisition);
        when(node.acquire(PARTITION_ID, 2, AcquireOptions.defaultTimeout())).thenReturn(acquisition);
        when(node.acquireLatest(PARTITION_ID, options)).thenReturn(acquisition);
        when(node.acquire(PARTITION_ID, 2, options)).thenReturn(acquisition);
        try (var latest = partition.acquireLatest(); var exact = partition.acquire(2);
                var timedLatest = partition.acquireLatest(options); var timedExact = partition.acquire(2, options)) {
            assertEquals(2, latest.tick());
            assertEquals(2, exact.tick());
            assertEquals(2, timedLatest.tick());
            assertEquals(2, timedExact.tick());
        }
        verify(node).acquireLatest(PARTITION_ID, AcquireOptions.defaultTimeout());
        verify(node).acquire(PARTITION_ID, 2, AcquireOptions.defaultTimeout());
        verify(node).acquireLatest(PARTITION_ID, options);
        verify(node).acquire(PARTITION_ID, 2, options);
    }

    @Test
    void acquiredViewUsesTheReturnedAcquisitionForEveryOperation() {
        Acquisition acquisition = new Acquisition(PARTITION_ID, 17, 4);
        AcquiredSnapshot3 snapshot = new AcquiredSnapshot3(node, acquisition);
        long[] ids = {3, 8};
        List<Entity3> found = List.of(new Entity3(8, B));
        Box3 region = new Box3(A, B);
        when(node.getAll(acquisition, ids)).thenReturn(found);
        when(node.findInRegion(acquisition, region)).thenReturn(found);
        when(node.findNearest(acquisition, A, 1)).thenReturn(found);
        when(node.size(acquisition)).thenReturn(1);
        List<ApplicationMetadata> metadata = List.of(new ApplicationMetadata(8, Map.of("version", 2L)));
        when(node.getMetadataAll(acquisition, ids)).thenReturn(metadata);
        when(node.getMetadataAll(acquisition, new long[] {8})).thenReturn(metadata);
        when(node.getMetadataAll(acquisition, new long[] {5})).thenReturn(List.of());
        assertEquals(4, snapshot.tick());
        assertEquals(1, snapshot.size());
        assertSame(found, snapshot.getAll(ids));
        assertSame(found, snapshot.findInRegion(region));
        assertSame(found, snapshot.findNearest(A, 1));
        assertSame(metadata, snapshot.getMetadataAll(ids));
        assertEquals(Optional.of(Map.of("version", 2L)), snapshot.getMetadata(8));
        assertEquals(Optional.empty(), snapshot.getMetadata(5));
        snapshot.close();
        verify(node).release(acquisition);
    }

    @Test
    void setTickReachesEachNodeOnceEvenWithSeveralPartitions() {
        PartitionNode3 other = mock(PartitionNode3.class);
        space.createPartition(node, NAME);
        space.createPartition(node, NAME);
        space.createPartition(other, NAME);
        clearInvocations(node, other);
        space.setTick(5);
        assertEquals(5, space.tick());
        verify(node).setTick(5);
        verify(other).setTick(5);
        verifyNoMoreInteractions(node, other);
    }

    @Test
    void newNodeReceivesTheSpaceTickBeforeCreation() {
        space.setTick(5);
        space.createPartition(node, NAME);
        var order = inOrder(node);
        order.verify(node).setTick(5);
        order.verify(node).createPartition(PARTITION_ID, NAME, IndexConfig.linearScan(), 1);
    }

    @Test
    void earlierTickIsRejectedWithoutCallingTheNode() {
        space.createPartition(node, NAME);
        space.setTick(5);
        clearInvocations(node);
        assertThrows(IllegalArgumentException.class, () -> space.setTick(4));
        assertEquals(5, space.tick());
        verifyNoInteractions(node);
    }

    @Test
    void limitsPassThePartitionId() {
        Partition3 partition = space.createPartition(node, NAME);
        when(node.limits(PARTITION_ID)).thenReturn(IndexConfig.uniformGrid(4).limits());
        assertEquals(IndexConfig.uniformGrid(4).limits(), partition.limits());
    }

    @Test
    void newPartitionIsNotClaimedByTheSpace() {
        space.createPartition(node, NAME);
        verify(node, never()).claim(any(), anyLong());
    }

    @Test
    void ownershipAndClaimPassThePartitionId() {
        Partition3 partition = space.createPartition(node, NAME);
        when(node.ownership(PARTITION_ID)).thenReturn(new Ownership(3, true));
        when(node.claim(PARTITION_ID, 3)).thenReturn(Optional.of(new WriteToken(PARTITION_ID, 4)));
        assertEquals(new Ownership(3, true), partition.ownership());
        assertEquals(Optional.of(new WriteToken(PARTITION_ID, 4)), partition.claim(3));
    }

    @Test
    void writerPassesItsTokenInEveryOperation() {
        PartitionWriter3 writer = space.createPartition(node, NAME).writer(TOKEN);
        clearInvocations(node);
        assertSame(TOKEN, writer.token());
        long entityId = writer.insert(A);
        writer.update(entityId, B);
        writer.putMetadata(entityId, "version", 2);
        writer.deleteMetadata(entityId, "owner");
        List<MetadataWrite> writes = List.of(MetadataWrite.put(entityId, "a", 1), MetadataWrite.delete(entityId, "b"));
        writer.updateMetadataAll(writes);
        writer.remove(entityId);
        writer.commit();
        writer.removePartition();
        writer.close();
        var order = inOrder(node);
        order.verify(node).insertAll(TOKEN, List.of(new Entity3(entityId, A)));
        order.verify(node).updateAll(TOKEN, List.of(new Entity3(entityId, B)));
        order.verify(node).updateMetadataAll(TOKEN, List.of(MetadataWrite.put(entityId, "version", 2)));
        order.verify(node).updateMetadataAll(TOKEN, List.of(MetadataWrite.delete(entityId, "owner")));
        order.verify(node).updateMetadataAll(TOKEN, writes);
        order.verify(node).removeAll(TOKEN, new long[] {entityId});
        order.verify(node).commit(TOKEN);
        order.verify(node).removePartition(TOKEN);
        order.verify(node).release(TOKEN);
        order.verifyNoMoreInteractions();
    }

    @Test
    void conditionalWritesPassTheTokenAndReturnTheFalseConditions() {
        PartitionWriter3 writer = space.createPartition(node, NAME).writer(TOKEN);
        MetadataCondition condition = MetadataCondition.valueIs(7, "version", 1);
        ConditionalWrite3 write = new ConditionalWrite3(List.of(condition), List.of(),
                List.of(MetadataWrite.put(7, "version", 2)));
        when(node.updateIf(TOKEN, write)).thenReturn(List.of(condition));
        assertEquals(List.of(condition), writer.updateIf(write));
        assertFalse(writer.compareAndSetMetadata(7, "version", 1, 2));
        when(node.updateIf(TOKEN, write)).thenReturn(List.of());
        assertTrue(writer.compareAndSetMetadata(7, "version", 1, 2));
    }

    @Test
    void writerOfATokenOfAnotherPartitionIsRejectedWithoutCallingTheNode() {
        Partition3 partition = space.createPartition(node, NAME);
        clearInvocations(node);
        assertThrows(IllegalArgumentException.class, () -> partition.writer(new WriteToken(new PartitionId(999), 1)));
        verifyNoInteractions(node);
    }

    @Test
    void savesGoToTheNodeWithThePartitionIdAndReturnItsReference() {
        Partition3 partition = space.createPartition(node, NAME);
        when(node.save(PARTITION_ID, 2)).thenReturn(new SavedPartition(NAME, 2));
        when(node.saveLatest(PARTITION_ID)).thenReturn(new SavedPartition(NAME, 4));
        assertEquals(new SavedPartition(NAME, 2), partition.save(2));
        assertEquals(new SavedPartition(NAME, 4), partition.saveLatest());
    }

    @Test
    void savesAndTheirDeletionGoToTheNodeAlsoWithoutPartitionsOnIt() {
        List<SavedPartition> saves = List.of(new SavedPartition(NAME, 1), new SavedPartition(NAME, 2));
        when(node.saves()).thenReturn(saves);
        when(node.saves(NAME)).thenReturn(saves);
        assertEquals(saves, space.saves(node));
        assertEquals(saves, space.saves(node, NAME));
        space.deleteSaves(node, saves);
        verify(node).deleteSaves(saves);
        // The space does not take the node: it creates no partition and gives it no tick.
        verify(node, never()).setTick(0);
    }

    @Test
    void savesOfAPartitionAreTheSavesOfItsName() {
        Partition3 partition = space.createPartition(node, NAME);
        List<SavedPartition> saves = List.of(new SavedPartition(NAME, 3));
        when(node.saves(NAME)).thenReturn(saves);
        assertEquals(saves, partition.saves());
    }

    @Test
    void saveErrorsKeepTheirReasonThroughTheProxy() {
        Partition3 partition = space.createPartition(node, NAME);
        var failure = new SnapshotNotAvailableException(
                SnapshotNotAvailableException.Reason.NOT_YET_PUBLISHED, "Tick is not published yet");
        when(node.saveLatest(PARTITION_ID)).thenThrow(failure);
        assertSame(failure, assertThrows(SnapshotNotAvailableException.class, partition::saveLatest));
    }

    @Test
    void acquisitionErrorsKeepTheirReasonThroughTheProxy() {
        Partition3 partition = space.createPartition(node, NAME);
        var failure = new SnapshotNotAvailableException(
                SnapshotNotAvailableException.Reason.OUT_OF_RETENTION, "Tick is outside the window");
        when(node.acquire(PARTITION_ID, 2, AcquireOptions.defaultTimeout())).thenThrow(failure);
        assertSame(failure, assertThrows(SnapshotNotAvailableException.class, () -> partition.acquire(2)));
    }
    @Test
    void configurationRejectsNullValuesAndInvalidRetention() {
        assertThrows(NullPointerException.class, () -> SpaceConfig.of(null, partitionIds::incrementAndGet));
        assertThrows(NullPointerException.class, () -> SpaceConfig.of(entityIds::incrementAndGet, null));
        assertThrows(NullPointerException.class, () -> config.withEntityIds(null));
        assertThrows(NullPointerException.class, () -> config.withPartitionIds(null));
        assertThrows(NullPointerException.class, () -> new Space3(null));
        assertThrows(NullPointerException.class, () -> space.createPartition(node, NAME, null));
        assertThrows(NullPointerException.class, () -> space.createPartition(node, null, PartitionConfig.defaults()));
        assertThrows(NullPointerException.class, () -> new PartitionConfig(null, RemovalPolicy.REQUIRE_EMPTY, 1));
        assertThrows(NullPointerException.class, () -> PartitionConfig.defaults().withRemovalPolicy(null));
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.defaults().withRetainedTicks(0));
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.defaults().withRetainedTicks(-1));
    }
}