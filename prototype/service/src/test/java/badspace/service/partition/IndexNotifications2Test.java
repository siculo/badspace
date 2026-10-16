package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import badspace.common.geometry.Point2;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.service.index.SpatialIndex2;
import badspace.service.index.SpatialIndexes;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks the storage-index protocol with a recording facade over a real index. */
class IndexNotifications2Test {
    private static final Point2 A = new Point2(1, 2);
    private static final Point2 B = new Point2(3, 4);
    private static final Point2 C = new Point2(5, 6);
    private SpatialIndex2 index;
    private PartitionStorage2 storage;

    @BeforeEach
    void setUp() {
        storage = new PartitionStorage2(new CoordinateLimits(-10, 10), slots -> {
            SpatialIndex2 real = SpatialIndexes.create(IndexConfig.uniformGrid(4), slots);
            index = mock(SpatialIndex2.class, delegatesTo(real));
            return index;
        });
    }

    @Test
    void insertionAndMovementNotifyTheAffectedSlots() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        storage.updateAll(List.of(new Entity2(20, C)));
        var order = inOrder(index);
        order.verify(index).inserted(0, A);
        order.verify(index).inserted(1, B);
        order.verify(index).moved(1, B, C);
        order.verifyNoMoreInteractions();
        assertEquals(List.of(new Entity2(20, C)), storage.findNearest(C, 1));
    }

    @Test
    void removalNotifiesBeforeRelocationAndHandlesTheNextRemovedId() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C)));
        clearInvocations(index);
        storage.removeAll(new long[] {10, 30});
        var order = inOrder(index);
        order.verify(index).removed(0, A);
        order.verify(index).relocated(2, 0, C);
        order.verify(index).removed(0, C);
        order.verify(index).relocated(1, 0, B);
        order.verifyNoMoreInteractions();
        assertEquals(List.of(new Entity2(20, B)), storage.findNearest(A, 3));
    }

    @Test
    void removalOfTheLastSlotDoesNotRelocate() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        clearInvocations(index);
        storage.removeAll(new long[] {20});
        verify(index).removed(1, B);
        verifyNoMoreInteractions(index);
    }

    @Test
    void failedBatchesLeaveBothSlotsAndIndexUnchanged() {
        storage.insertAll(List.of(new Entity2(10, A)));
        clearInvocations(index);
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity2(20, B), new Entity2(10, C))));
        verifyNoInteractions(index);
        assertThrows(NoSuchElementException.class,
                () -> storage.updateAll(List.of(new Entity2(10, B), new Entity2(99, C))));
        verifyNoInteractions(index);
        assertThrows(NoSuchElementException.class, () -> storage.removeAll(new long[] {10, 99}));
        verifyNoInteractions(index);
        Point2 outside = new Point2(11, 0);
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity2(20, B), new Entity2(30, outside))));
        verifyNoInteractions(index);
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateAll(List.of(new Entity2(10, B), new Entity2(10, outside))));
        verifyNoInteractions(index);
        List<Entity2> expected = List.of(new Entity2(10, A));
        assertEquals(expected, storage.getAll(new long[] {10, 20, 30}));
        assertEquals(expected, storage.findNearest(B, 3));
        StorageSnapshot2 snapshot = storage.commit();
        assertEquals(expected, snapshot.findNearest(B, 3));
    }

    @Test
    void eachCommitAdvancesTheIndexSequenceEvenWithinOneTick() {
        storage.commit();
        storage.commit();
        storage.setTick(1);
        storage.commit();
        var order = inOrder(index);
        order.verify(index).commit(1);
        order.verify(index).commit(2);
        order.verify(index).commit(3);
        order.verifyNoMoreInteractions();
    }

    @Test
    void zeroNearestCountReturnsEmptyWithoutCallingTheIndex() {
        storage.insertAll(List.of(new Entity2(10, A)));
        clearInvocations(index);
        assertEquals(List.of(), storage.findNearest(A, 0));
        verifyNoInteractions(index);
    }
}