package badspace.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.SnapshotNotAvailableException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Checks API decisions and delegation without implementing the node contract again. */
class Space2Test {
    private static final PartitionId PARTITION_ID = new PartitionId(101);
    private final AtomicLong entityIds = new AtomicLong(10);
    private final AtomicLong partitionIds = new AtomicLong(100);
    private final SpaceConfig config = SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet);
    private final Space2 space = new Space2(config);
    private final PartitionNode2 node = mock(PartitionNode2.class);
    private static final Point2 A = new Point2(1, 2);
    private static final Point2 B = new Point2(5, 6);

    @Test
    void partitionCreationPassesTheGeneratedIdAndConfiguration() {
        PartitionConfig chosen = PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(4)).withRetainedTicks(3);
        Partition2 partition = space.createPartition(node, chosen);
        assertEquals(chosen, partition.config());
        var order = inOrder(node);
        order.verify(node).setTick(0);
        order.verify(node).createPartition(PARTITION_ID, chosen.index(), 3);
        order.verifyNoMoreInteractions();
    }

    @Test
    void defaultConfigurationIsPassedToTheNode() {
        Partition2 partition = space.createPartition(node);
        assertEquals(PartitionConfig.defaults(), partition.config());
        verify(node).createPartition(PARTITION_ID, IndexConfig.linearScan(), 1);
    }

    @Test
    void insertAllMakesOneBatchWithGeneratedIdsInPositionOrder() {
        Partition2 partition = space.createPartition(node);
        clearInvocations(node);
        assertArrayEquals(new long[] {11, 12}, partition.insertAll(List.of(A, B)));
        verify(node).insertAll(PARTITION_ID, List.of(new Entity2(11, A), new Entity2(12, B)));
        verifyNoMoreInteractions(node);
    }

    @Test
    void removalUsesTheConfiguredPolicy() {
        for (RemovalPolicy policy : RemovalPolicy.values()) {
            Partition2 partition = space.createPartition(node, PartitionConfig.defaults().withRemovalPolicy(policy));
            PartitionId id = new PartitionId(partitionIds.get());
            clearInvocations(node);
            space.removePartition(partition);
            if (policy == RemovalPolicy.REQUIRE_EMPTY) {
                verify(node).removePartition(id);
            } else {
                verify(node).dropPartition(id);
            }
            verifyNoMoreInteractions(node);
        }
    }

    @Test
    void failedRemovalLeavesTheProxyUsable() {
        Partition2 partition = space.createPartition(node);
        doThrow(new IllegalStateException("Partition is not empty")).when(node).removePartition(PARTITION_ID);
        assertThrows(IllegalStateException.class, () -> space.removePartition(partition));
        partition.insert(A);
        verify(node).insertAll(PARTITION_ID, List.of(new Entity2(11, A)));
    }

    @Test
    void removedProxyRejectsEveryWriterOperationBeforeCallingTheNode() {
        Partition2 partition = space.createPartition(node);
        space.removePartition(partition);
        clearInvocations(node);
        assertThrows(IllegalStateException.class, () -> partition.insert(A));
        assertThrows(IllegalStateException.class, () -> partition.insertAll(List.of(A)));
        assertThrows(IllegalStateException.class, () -> partition.update(1, B));
        assertThrows(IllegalStateException.class, () -> partition.updateAll(List.of(new Entity2(1, B))));
        assertThrows(IllegalStateException.class, () -> partition.remove(1));
        assertThrows(IllegalStateException.class, () -> partition.removeAll(new long[] {1}));
        assertThrows(IllegalStateException.class, partition::limits);
        assertThrows(IllegalStateException.class, partition::commit);
        assertThrows(IllegalStateException.class, () -> space.removePartition(partition));
        verifyNoInteractions(node);
    }

    @Test
    void partitionOfAnotherSpaceIsRejectedBeforeCallingTheNode() {
        Partition2 partition = space.createPartition(node);
        clearInvocations(node);
        Space2 other = new Space2(config);
        assertThrows(IllegalArgumentException.class, () -> other.removePartition(partition));
        verifyNoInteractions(node);
    }

    @Test
    void acquisitionsPassDefaultAndExplicitTimeouts() {
        Partition2 partition = space.createPartition(node);
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
        AcquiredSnapshot2 snapshot = new AcquiredSnapshot2(node, acquisition);
        long[] ids = {3, 8};
        List<Entity2> found = List.of(new Entity2(8, B));
        Box2 region = new Box2(A, B);
        when(node.getAll(acquisition, ids)).thenReturn(found);
        when(node.findInRegion(acquisition, region)).thenReturn(found);
        when(node.findNearest(acquisition, A, 1)).thenReturn(found);
        when(node.size(acquisition)).thenReturn(1);
        assertEquals(4, snapshot.tick());
        assertEquals(1, snapshot.size());
        assertSame(found, snapshot.getAll(ids));
        assertSame(found, snapshot.findInRegion(region));
        assertSame(found, snapshot.findNearest(A, 1));
        snapshot.close();
        verify(node).release(acquisition);
    }

    @Test
    void setTickReachesEachNodeOnceEvenWithSeveralPartitions() {
        PartitionNode2 other = mock(PartitionNode2.class);
        space.createPartition(node);
        space.createPartition(node);
        space.createPartition(other);
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
        space.createPartition(node);
        var order = inOrder(node);
        order.verify(node).setTick(5);
        order.verify(node).createPartition(PARTITION_ID, IndexConfig.linearScan(), 1);
    }

    @Test
    void earlierTickIsRejectedWithoutCallingTheNode() {
        space.createPartition(node);
        space.setTick(5);
        clearInvocations(node);
        assertThrows(IllegalArgumentException.class, () -> space.setTick(4));
        assertEquals(5, space.tick());
        verifyNoInteractions(node);
    }

    @Test
    void limitsAndCommitPassThePartitionId() {
        Partition2 partition = space.createPartition(node);
        when(node.limits(PARTITION_ID)).thenReturn(IndexConfig.uniformGrid(4).limits());
        assertEquals(IndexConfig.uniformGrid(4).limits(), partition.limits());
        partition.commit();
        verify(node).commit(PARTITION_ID);
    }

    @Test
    void acquisitionErrorsKeepTheirReasonThroughTheProxy() {
        Partition2 partition = space.createPartition(node);
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
        assertThrows(NullPointerException.class, () -> new Space2(null));
        assertThrows(NullPointerException.class, () -> space.createPartition(node, null));
        assertThrows(NullPointerException.class, () -> new PartitionConfig(null, RemovalPolicy.REQUIRE_EMPTY, 1));
        assertThrows(NullPointerException.class, () -> PartitionConfig.defaults().withRemovalPolicy(null));
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.defaults().withRetainedTicks(0));
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.defaults().withRetainedTicks(-1));
    }
}