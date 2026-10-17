package badspace.service.partition;

import static badspace.service.partition.Owners.owner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import badspace.common.geometry.Point3;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.service.index.SpatialIndex3;
import badspace.service.index.SpatialIndexes;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks the storage-index protocol with a recording facade over a real index. */
class IndexNotifications3Test {
    private static final Point3 A = new Point3(1, 2, 0);
    private static final Point3 B = new Point3(3, 4, 0);
    private static final Point3 C = new Point3(5, 6, 0);
    private SpatialIndex3 index;
    private PartitionStorage3 storage;

    @BeforeEach
    void setUp() {
        storage = new PartitionStorage3(new CoordinateLimits(-10, 10), slots -> {
            SpatialIndex3 real = SpatialIndexes.create(IndexConfig.uniformGrid(4), slots);
            index = mock(SpatialIndex3.class, delegatesTo(real));
            return index;
        });
    }

    @Test
    void insertionAndMovementNotifyTheAffectedSlots() {
        storage.insertAll(owner(storage), List.of(new Entity3(10, A), new Entity3(20, B)));
        storage.updateAll(owner(storage), List.of(new Entity3(20, C)));
        var order = inOrder(index);
        order.verify(index).inserted(0, A);
        order.verify(index).inserted(1, B);
        order.verify(index).moved(1, B, C);
        order.verifyNoMoreInteractions();
        assertEquals(List.of(new Entity3(20, C)), storage.commit(owner(storage)).findNearest(C, 1));
    }

    @Test
    void removalNotifiesBeforeRelocationAndHandlesTheNextRemovedId() {
        storage.insertAll(owner(storage), List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)));
        clearInvocations(index);
        storage.removeAll(owner(storage), new long[] {10, 30});
        var order = inOrder(index);
        order.verify(index).removed(0, A);
        order.verify(index).relocated(2, 0, C);
        order.verify(index).removed(0, C);
        order.verify(index).relocated(1, 0, B);
        order.verifyNoMoreInteractions();
        assertEquals(List.of(new Entity3(20, B)), storage.commit(owner(storage)).findNearest(A, 3));
    }

    @Test
    void removalOfTheLastSlotDoesNotRelocate() {
        storage.insertAll(owner(storage), List.of(new Entity3(10, A), new Entity3(20, B)));
        clearInvocations(index);
        storage.removeAll(owner(storage), new long[] {20});
        verify(index).removed(1, B);
        verifyNoMoreInteractions(index);
    }

    @Test
    void failedBatchesLeaveBothSlotsAndIndexUnchanged() {
        storage.insertAll(owner(storage), List.of(new Entity3(10, A)));
        clearInvocations(index);
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(20, B), new Entity3(10, C))));
        verifyNoInteractions(index);
        assertThrows(NoSuchElementException.class,
                () -> storage.updateAll(owner(storage), List.of(new Entity3(10, B), new Entity3(99, C))));
        verifyNoInteractions(index);
        assertThrows(NoSuchElementException.class, () -> storage.removeAll(owner(storage), new long[] {10, 99}));
        verifyNoInteractions(index);
        Point3 outside = new Point3(11, 0, 0);
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(owner(storage), List.of(new Entity3(20, B), new Entity3(30, outside))));
        verifyNoInteractions(index);
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateAll(owner(storage), List.of(new Entity3(10, B), new Entity3(10, outside))));
        verifyNoInteractions(index);
        List<Entity3> expected = List.of(new Entity3(10, A));
        StorageSnapshot3 snapshot = storage.commit(owner(storage));
        assertEquals(expected, snapshot.getAll(new long[] {10, 20, 30}));
        assertEquals(expected, snapshot.findNearest(B, 3));
    }

    @Test
    void eachCommitAdvancesTheIndexSequenceEvenWithinOneTick() {
        storage.commit(owner(storage));
        storage.commit(owner(storage));
        storage.setTick(1);
        storage.commit(owner(storage));
        var order = inOrder(index);
        order.verify(index).commit(1);
        order.verify(index).commit(2);
        order.verify(index).commit(3);
        order.verifyNoMoreInteractions();
    }
}
