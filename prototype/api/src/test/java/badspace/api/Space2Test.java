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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.Entity2;
import badspace.common.partition.EntityWrite2;
import badspace.common.partition.FailureOutcome;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.Ownership;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionComponent;
import badspace.common.partition.PartitionFailure;
import badspace.common.partition.PartitionFailureException;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.PartitionOperation;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.WriteMode;
import badspace.common.partition.WriteToken;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Checks API decisions and delegation without implementing the node contract again. */
class Space2Test {
    private static final PartitionId PARTITION_ID = new PartitionId(101);
    /** A token of the partition, as the node gives it to the first owner. */
    private static final WriteToken TOKEN = new WriteToken(PARTITION_ID, 1);
    private static final PartitionName NAME = new PartitionName("p");
    private final AtomicLong entityIds = new AtomicLong(10);
    private final AtomicLong partitionIds = new AtomicLong(100);
    private final SpaceConfig config = SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet);
    private final Space2 space = new Space2(config);
    // The writes of the tests go to the node in one batch at the commit, like with a remote node.
    private final PartitionNode2 node = buffered(mock(PartitionNode2.class));
    private final Node2 handle = space.createNode(() -> node);

    private static final Point2 A = new Point2(1, 2);
    private static final Point2 B = new Point2(5, 6);

    @Test
    void partitionCreationPassesTheGeneratedIdAndConfiguration() {
        PartitionConfig chosen = PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(4)).withRetainedTicks(3);
        Partition2 partition = space.createPartition(handle, NAME, chosen);
        assertEquals(chosen, partition.config());
        var order = inOrder(node);
        order.verify(node).setTick(0);
        order.verify(node).createPartition(PARTITION_ID, NAME, chosen.index(), 3);
        order.verifyNoMoreInteractions();
    }

    @Test
    void loadPassesTheSaveTheGeneratedIdAndTheConfigurationAndKeepsTheNameOfTheSave() {
        space.setTick(5);
        PartitionConfig chosen = PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(4)).withRetainedTicks(3);
        SavedPartition saved = new SavedPartition(new PartitionName("north"), 2);
        Partition2 partition = space.loadPartition(handle, chosen, saved);
        assertEquals(new PartitionName("north"), partition.name());
        assertEquals(chosen, partition.config());
        var order = inOrder(node);
        order.verify(node).setTick(0);
        order.verify(node).setTick(5);
        order.verify(node).loadPartition(PARTITION_ID, saved, chosen.index(), 3);
        order.verifyNoMoreInteractions();
    }

    @Test
    void moveOfTheTickDoesNotWaitForALoad() throws Exception {
        SavedPartition saved = new SavedPartition(new PartitionName("north"), 2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return null;
        }).when(node).loadPartition(PARTITION_ID, saved, IndexConfig.linearScan(), 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Partition2> load = executor.submit(() -> space.loadPartition(handle, PartitionConfig.defaults(), saved));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            space.setTick(5);
            verify(node).setTick(5);
            finish.countDown();
            assertEquals(new PartitionName("north"), load.get(10, TimeUnit.SECONDS).name());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nameIsPassedToTheNodeAndKeptByThePartition() {
        Partition2 partition = space.createPartition(handle, new PartitionName("north"));
        assertEquals(new PartitionName("north"), partition.name());
        verify(node).createPartition(PARTITION_ID, new PartitionName("north"), IndexConfig.linearScan(), 1);
    }

    @Test
    void defaultConfigurationIsPassedToTheNode() {
        Partition2 partition = space.createPartition(handle, NAME);
        assertEquals(PartitionConfig.defaults(), partition.config());
        verify(node).createPartition(PARTITION_ID, NAME, IndexConfig.linearScan(), 1);
    }

    @Test
    void writesMakeNoCallsAndTheCommitSendsOneBatchWithTheGeneratedIds() {
        PartitionWriter2 writer = space.createPartition(handle, NAME).writer(TOKEN);
        clearInvocations(node);
        assertArrayEquals(new long[] {11, 12}, writer.insertAll(List.of(A, B)));
        writer.set(11, B);
        writer.putMetadata(12, "version", 1);
        verifyNoInteractions(node);
        writer.commit();
        verify(node).commit(TOKEN, List.of(new EntityWrite2(11, false, B, List.of(), List.of()),
                new EntityWrite2(12, false, B, List.of(), List.of(MetadataWrite.put(12, "version", 1)))));
        verifyNoMoreInteractions(node);
    }

    @Test
    void directWritesGoToTheNodeAtOnceAndTheCommitConfirmsThem() {
        PartitionNode2 direct = mock(PartitionNode2.class);
        when(direct.writeMode()).thenReturn(WriteMode.DIRECT);
        PartitionWriter2 writer = space.createPartition(space.createNode(() -> direct), NAME).writer(TOKEN);
        long entityId = writer.insert(A);
        writer.set(entityId, B);
        writer.putMetadata(entityId, "a", 1);
        writer.updateMetadataAll(List.of(MetadataWrite.put(entityId, "b", 2), MetadataWrite.delete(entityId, "a"),
                MetadataWrite.put(entityId + 1, "c", 3)));
        writer.remove(entityId);
        writer.removeAll(new long[0]);
        writer.commit();
        writer.rollback();
        writer.close();
        var order = inOrder(direct);
        order.verify(direct).write(TOKEN, List.of(EntityWrite2.set(new Entity2(entityId, A))));
        order.verify(direct).write(TOKEN, List.of(EntityWrite2.set(new Entity2(entityId, B))));
        order.verify(direct).write(TOKEN, List.of(EntityWrite2.metadataOf(entityId, List.of(MetadataWrite.put(entityId, "a", 1)))));
        order.verify(direct).write(TOKEN, List.of(
                EntityWrite2.metadataOf(entityId, List.of(MetadataWrite.put(entityId, "b", 2), MetadataWrite.delete(entityId, "a"))),
                EntityWrite2.metadataOf(entityId + 1, List.of(MetadataWrite.put(entityId + 1, "c", 3)))));
        order.verify(direct).write(TOKEN, List.of(EntityWrite2.removal(entityId)));
        // An empty write makes no call, like with the buffer.
        order.verify(direct).commit(TOKEN, List.of());
        order.verify(direct).rollback(TOKEN);
        order.verify(direct).release(TOKEN);
        verify(direct, times(5)).write(any(), any());
    }

    @Test
    void directWriteThatTheNodeRejectsFailsAtOnce() {
        PartitionNode2 direct = mock(PartitionNode2.class);
        when(direct.writeMode()).thenReturn(WriteMode.DIRECT);
        PartitionWriter2 writer = space.createPartition(space.createNode(() -> direct), NAME).writer(TOKEN);
        doThrow(new OwnershipChangedException("Token is not current")).when(direct).write(any(), any());
        assertThrows(OwnershipChangedException.class, () -> writer.insert(A));
        writer.commit();
        verify(direct).commit(TOKEN, List.of());
    }

    @Test
    void successfulCommitEmptiesTheWriterAndARejectedOneKeepsItsWrites() {
        PartitionWriter2 writer = space.createPartition(handle, NAME).writer(TOKEN);
        long entityId = writer.insert(A);
        List<EntityWrite2> batch = List.of(new EntityWrite2(entityId, false, A, List.of(), List.of()));
        doThrow(new IllegalStateException("Recovery required")).when(node).commit(TOKEN, batch);
        assertThrows(IllegalStateException.class, writer::commit);
        doNothing().when(node).commit(TOKEN, batch);
        writer.commit();
        writer.commit();
        var order = inOrder(node);
        order.verify(node, times(2)).commit(TOKEN, batch);
        order.verify(node).commit(TOKEN, List.of());
    }

    @Test
    void failedCommitDiscardsTheWritesOfTheWriter() {
        PartitionWriter2 writer = space.createPartition(handle, NAME).writer(TOKEN);
        long entityId = writer.insert(A);
        List<EntityWrite2> batch = List.of(new EntityWrite2(entityId, false, A, List.of(), List.of()));
        PartitionFailure report = new PartitionFailure(PartitionOperation.COMMIT, PartitionComponent.INDEX,
                FailureOutcome.WRITES_DISCARDED, 0, new IllegalStateException("Index failure"), null);
        doThrow(new PartitionFailureException(report)).when(node).commit(TOKEN, batch);
        assertThrows(PartitionFailureException.class, writer::commit);
        writer.insert(B);
        OutOfMemoryError error = new OutOfMemoryError("Memory failure");
        doThrow(error).when(node).commit(TOKEN, List.of(new EntityWrite2(entityId + 1, false, B, List.of(), List.of())));
        assertSame(error, assertThrows(OutOfMemoryError.class, writer::commit));
        writer.commit();
        verify(node).commit(TOKEN, List.of());
    }

    @Test
    void rollbackAndCloseDiscardTheWritesOfTheWriter() {
        PartitionWriter2 writer = space.createPartition(handle, NAME).writer(TOKEN);
        writer.insert(A);
        writer.rollback();
        writer.commit();
        writer.insert(B);
        writer.close();
        var order = inOrder(node);
        order.verify(node).rollback(TOKEN);
        order.verify(node).commit(TOKEN, List.of());
        order.verify(node).release(TOKEN);
        verify(node, times(1)).commit(any(), any());
    }

    @Test
    void positionOutsideTheLimitsIsRejectedAtTheWriteAndNothingIsKept() {
        PartitionWriter2 writer = space.createPartition(handle, NAME,
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(1))).writer(TOKEN);
        assertThrows(IllegalArgumentException.class, () -> writer.insertAll(List.of(A, new Point2(2e9, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> writer.setAll(List.of(new Entity2(7, A), new Entity2(8, new Point2(2e9, 0)))));
        writer.commit();
        verify(node).commit(TOKEN, List.of());
    }

    @Test
    void removalUsesTheConfiguredPolicy() {
        for (RemovalPolicy policy : RemovalPolicy.values()) {
            Partition2 partition = space.createPartition(handle, NAME, PartitionConfig.defaults().withRemovalPolicy(policy));
            PartitionId id = new PartitionId(partitionIds.get());
            PartitionWriter2 writer = partition.writer(new WriteToken(id, 1));
            clearInvocations(node);
            writer.removePartition();
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
        PartitionWriter2 writer = space.createPartition(handle, NAME).writer(TOKEN);
        doThrow(new IllegalStateException("Partition is not empty")).when(node).removePartition(TOKEN);
        assertThrows(IllegalStateException.class, writer::removePartition);
        writer.insert(A);
        writer.commit();
        verify(node).commit(TOKEN, List.of(new EntityWrite2(11, false, A, List.of(), List.of())));
    }



    @Test
    void acquisitionsPassDefaultAndExplicitTimeouts() {
        Partition2 partition = space.createPartition(handle, NAME);
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
        PartitionNode2 other = mock(PartitionNode2.class);
        Node2 otherHandle = space.createNode(() -> other);
        space.createPartition(handle, NAME);
        space.createPartition(handle, NAME);
        space.createPartition(otherHandle, NAME);
        clearInvocations(node, other);
        space.setTick(5);
        assertEquals(5, space.tick());
        verify(node).setTick(5);
        verify(other).setTick(5);
        verifyNoMoreInteractions(node, other);
    }

    @Test
    void earlierTickIsRejectedWithoutCallingTheNode() {
        space.createPartition(handle, NAME);
        space.setTick(5);
        clearInvocations(node);
        assertThrows(IllegalArgumentException.class, () -> space.setTick(4));
        assertEquals(5, space.tick());
        verifyNoInteractions(node);
    }

    @Test
    void createdNodeStartsAtTheTickOfTheSpaceAndBelongsToIt() {
        assertSame(space, handle.space());
        assertSame(node, handle.node());
        verify(node).setTick(0);
        PartitionNode2 later = mock(PartitionNode2.class);
        space.setTick(5);
        space.createNode(() -> later);
        verify(later).setTick(5);
        verifyNoMoreInteractions(later);
    }

    @Test
    void createdNodeWithoutPartitionsReceivesEveryMoveOfTheTick() {
        space.setTick(3);
        space.setTick(7);
        var order = inOrder(node);
        order.verify(node).setTick(0);
        order.verify(node).setTick(3);
        order.verify(node).setTick(7);
        order.verifyNoMoreInteractions();
    }

    @Test
    void firstPartitionDoesNotMoveACreatedNodeAgain() {
        space.setTick(5);
        space.createPartition(handle, NAME);
        var order = inOrder(node);
        order.verify(node).setTick(0);
        order.verify(node).setTick(5);
        order.verify(node).createPartition(PARTITION_ID, NAME, IndexConfig.linearScan(), 1);
        order.verifyNoMoreInteractions();
    }

    @Test
    void moveOfTheTickWhileTheFactoryRunsReachesTheNewNode() throws Exception {
        PartitionNode2 created = mock(PartitionNode2.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        PartitionNodeFactory2 factory = () -> {
            entered.countDown();
            await(finish);
            return created;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Node2> creation = executor.submit(() -> space.createNode(factory));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            space.setTick(5);
            finish.countDown();
            creation.get(10, TimeUnit.SECONDS);
            verify(created).setTick(5);
            verifyNoMoreInteractions(created);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void moveOfTheTickWaitsForTheAdditionOfANode() throws Exception {
        PartitionNode2 created = mock(PartitionNode2.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            await(finish);
            return null;
        }).when(created).setTick(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Node2> creation = executor.submit(() -> space.createNode(() -> created));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            CountDownLatch moving = new CountDownLatch(1);
            Future<?> move = executor.submit(() -> {
                moving.countDown();
                space.setTick(5);
            });
            assertTrue(moving.await(10, TimeUnit.SECONDS));
            // The node is moving to tick 0 while the space holds the lock: the move waits for the
            // lock and does not end. Without the lock it would end at once, without the new node.
            assertThrows(TimeoutException.class, () -> move.get(200, TimeUnit.MILLISECONDS));
            finish.countDown();
            creation.get(10, TimeUnit.SECONDS);
            move.get(10, TimeUnit.SECONDS);
            var order = inOrder(created);
            order.verify(created).setTick(0);
            order.verify(created).setTick(5);
            order.verifyNoMoreInteractions();
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void errorOfTheFactoryIsThrown() {
        IllegalStateException error = new IllegalStateException("No save directory");
        assertSame(error, assertThrows(IllegalStateException.class, () -> space.createNode(() -> {
            throw error;
        })));
    }

    @Test
    void nodeThatFailsToMoveToTheTickIsNotAdded() {
        space.setTick(4);
        PartitionNode2 failing = mock(PartitionNode2.class);
        RuntimeException error = new RuntimeException("Node down");
        doThrow(error).when(failing).setTick(4);
        assertSame(error, assertThrows(RuntimeException.class, () -> space.createNode(() -> failing)));
        space.setTick(6);
        verify(failing, never()).setTick(6);
    }

    @Test
    void factoryThatReturnsNoNodeIsRejected() {
        assertThrows(NullPointerException.class, () -> space.createNode(null));
        assertThrows(IllegalArgumentException.class, () -> space.createNode(() -> null));
    }

    @Test
    void factoryThatReturnsANodeOfTheSpaceAgainIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> space.createNode(() -> node));
        space.setTick(3);
        verify(node).setTick(0);
        verify(node).setTick(3);
        verifyNoMoreInteractions(node);
    }

    @Test
    void limitsPassThePartitionId() {
        Partition2 partition = space.createPartition(handle, NAME);
        when(node.limits(PARTITION_ID)).thenReturn(IndexConfig.uniformGrid(4).limits());
        assertEquals(IndexConfig.uniformGrid(4).limits(), partition.limits());
    }

    @Test
    void newPartitionIsNotClaimedByTheSpace() {
        space.createPartition(handle, NAME);
        verify(node, never()).claim(any(), anyLong());
    }

    @Test
    void ownershipAndClaimPassThePartitionId() {
        Partition2 partition = space.createPartition(handle, NAME);
        when(node.ownership(PARTITION_ID)).thenReturn(new Ownership(3, true));
        when(node.claim(PARTITION_ID, 3)).thenReturn(Optional.of(new WriteToken(PARTITION_ID, 4)));
        assertEquals(new Ownership(3, true), partition.ownership());
        assertEquals(Optional.of(new WriteToken(PARTITION_ID, 4)), partition.claim(3));
    }

    @Test
    void writerPassesItsTokenInEveryOperation() {
        PartitionWriter2 writer = space.createPartition(handle, NAME).writer(TOKEN);
        clearInvocations(node);
        assertSame(TOKEN, writer.token());
        long entityId = writer.insert(A);
        writer.set(entityId, B);
        writer.putMetadata(entityId, "version", 2);
        writer.deleteMetadata(entityId, "owner");
        List<MetadataWrite> writes = List.of(MetadataWrite.put(entityId, "a", 1), MetadataWrite.delete(entityId, "b"));
        writer.updateMetadataAll(writes);
        writer.remove(entityId);
        writer.commit();
        writer.removePartition();
        writer.close();
        var order = inOrder(node);
        order.verify(node).commit(TOKEN, List.of(new EntityWrite2(entityId, true, null, List.of(), List.of())));
        order.verify(node).removePartition(TOKEN);
        order.verify(node).release(TOKEN);
        order.verifyNoMoreInteractions();
    }

    @Test
    void writerOfATokenOfAnotherPartitionIsRejectedWithoutCallingTheNode() {
        Partition2 partition = space.createPartition(handle, NAME);
        clearInvocations(node);
        assertThrows(IllegalArgumentException.class, () -> partition.writer(new WriteToken(new PartitionId(999), 1)));
        verifyNoInteractions(node);
    }

    @Test
    void savesGoToTheNodeWithThePartitionIdAndReturnItsReference() {
        Partition2 partition = space.createPartition(handle, NAME);
        when(node.save(PARTITION_ID, 2)).thenReturn(new SavedPartition(NAME, 2));
        when(node.saveLatest(PARTITION_ID)).thenReturn(new SavedPartition(NAME, 4));
        assertEquals(new SavedPartition(NAME, 2), partition.save(2));
        assertEquals(new SavedPartition(NAME, 4), partition.saveLatest());
    }

    @Test
    void savesAndTheirDeletionGoToANodeWithoutPartitions() {
        List<SavedPartition> saves = List.of(new SavedPartition(NAME, 1), new SavedPartition(NAME, 2));
        when(node.saves()).thenReturn(saves);
        when(node.saves(NAME)).thenReturn(saves);
        assertEquals(saves, space.saves(handle));
        assertEquals(saves, space.saves(handle, NAME));
        space.deleteSaves(handle, saves);
        verify(node).setTick(0);
        verify(node).saves();
        verify(node).saves(NAME);
        verify(node).deleteSaves(saves);
        verifyNoMoreInteractions(node);
    }

    @Test
    void handleOfAnotherSpaceIsRejectedBeforeGeneratingIdsOrCallingTheNode() {
        Space2 other = new Space2(config);
        PartitionNode2 otherNode = mock(PartitionNode2.class);
        Node2 foreign = other.createNode(() -> otherNode);
        clearInvocations(otherNode);
        long partitionId = partitionIds.get();
        SavedPartition saved = new SavedPartition(NAME, 1);
        assertThrows(IllegalArgumentException.class, () -> space.createPartition(foreign, NAME));
        assertThrows(IllegalArgumentException.class, () -> space.createPartition(foreign, NAME, PartitionConfig.defaults()));
        assertThrows(IllegalArgumentException.class, () -> space.loadPartition(foreign, PartitionConfig.defaults(), saved));
        assertThrows(IllegalArgumentException.class, () -> space.saves(foreign));
        assertThrows(IllegalArgumentException.class, () -> space.saves(foreign, NAME));
        assertThrows(IllegalArgumentException.class, () -> space.deleteSaves(foreign, List.of(saved)));
        assertEquals(partitionId, partitionIds.get());
        verifyNoInteractions(otherNode);
    }

    @Test
    void savesOfAPartitionAreTheSavesOfItsName() {
        Partition2 partition = space.createPartition(handle, NAME);
        List<SavedPartition> saves = List.of(new SavedPartition(NAME, 3));
        when(node.saves(NAME)).thenReturn(saves);
        assertEquals(saves, partition.saves());
    }

    @Test
    void saveErrorsKeepTheirReasonThroughTheProxy() {
        Partition2 partition = space.createPartition(handle, NAME);
        var failure = new SnapshotNotAvailableException(
                SnapshotNotAvailableException.Reason.NOT_YET_PUBLISHED, "Tick is not published yet");
        when(node.saveLatest(PARTITION_ID)).thenThrow(failure);
        assertSame(failure, assertThrows(SnapshotNotAvailableException.class, partition::saveLatest));
    }

    @Test
    void acquisitionErrorsKeepTheirReasonThroughTheProxy() {
        Partition2 partition = space.createPartition(handle, NAME);
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
        assertThrows(NullPointerException.class, () -> space.createPartition(handle, NAME, null));
        assertThrows(NullPointerException.class, () -> space.createPartition(handle, null, PartitionConfig.defaults()));
        assertThrows(NullPointerException.class, () -> space.createPartition(null, NAME));
        assertThrows(NullPointerException.class, () -> space.loadPartition(null, PartitionConfig.defaults(), new SavedPartition(NAME, 1)));
        assertThrows(NullPointerException.class, () -> new PartitionConfig(null, RemovalPolicy.REQUIRE_EMPTY, 1));
        assertThrows(NullPointerException.class, () -> PartitionConfig.defaults().withRemovalPolicy(null));
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.defaults().withRetainedTicks(0));
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.defaults().withRetainedTicks(-1));
    }

    /** Waits for the latch in code that cannot throw InterruptedException, like a factory. */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Returns the node, in the buffered mode. */
    private static PartitionNode2 buffered(PartitionNode2 node) {
        when(node.writeMode()).thenReturn(WriteMode.BUFFERED);
        return node;
    }
}
