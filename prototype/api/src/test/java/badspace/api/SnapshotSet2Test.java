package badspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.UnknownPartitionException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Checks the acquisitions, the failures and the aggregation of the queries with framework mocks of the nodes. */
class SnapshotSet2Test {
    private static final long TICK = 4;
    private static final Point2 ORIGIN = new Point2(0, 0);
    private static final Box2 ALL = new Box2(new Point2(-100, -100), new Point2(100, 100));
    private final AtomicLong entityIds = new AtomicLong(10);
    private final AtomicLong partitionIds = new AtomicLong(100);
    private final Space2 space = new Space2(SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet));
    // One node for each partition, so each mock answers for one partition only.
    private final PartitionNode2 nodeA = mock(PartitionNode2.class);
    private final PartitionNode2 nodeB = mock(PartitionNode2.class);
    private final PartitionNode2 nodeC = mock(PartitionNode2.class);
    private final Partition2 a = space.createPartition(space.createNode(() -> nodeA), new PartitionName("a"));
    private final Partition2 b = space.createPartition(space.createNode(() -> nodeB), new PartitionName("b"));
    private final Partition2 c = space.createPartition(space.createNode(() -> nodeC), new PartitionName("c"));
    private final Acquisition acquisitionA = new Acquisition(new PartitionId(101), 1, TICK);
    private final Acquisition acquisitionB = new Acquisition(new PartitionId(102), 1, TICK);
    private final Acquisition acquisitionC = new Acquisition(new PartitionId(103), 1, TICK);

    @Test
    void acquiresTheTickOnEveryPartitionWithTheOptions() {
        AcquireOptions options = AcquireOptions.timeout(Duration.ofSeconds(5));
        acquirable(nodeA, acquisitionA, options);
        acquirable(nodeB, acquisitionB, options);
        try (SnapshotSet2 set = space.acquire(List.of(a, b), TICK, options)) {
            assertEquals(TICK, set.tick());
            assertTrue(set.isComplete());
            assertEquals(List.of(), set.failures());
            assertEquals(2, set.snapshots().size());
        }
        verify(nodeA).release(acquisitionA);
        verify(nodeB).release(acquisitionB);
    }

    @Test
    void withoutOptionsEachAcquisitionHasTheDefaultTimeout() {
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        try (SnapshotSet2 set = space.acquire(List.of(a), TICK)) {
            assertTrue(set.isComplete());
        }
        verify(nodeA).acquire(acquisitionA.partitionId(), TICK, AcquireOptions.defaultTimeout());
    }

    @Test
    void emptyListGivesAnEmptyCompleteSet() {
        try (SnapshotSet2 set = space.acquire(List.of(), TICK)) {
            assertTrue(set.isComplete());
            assertEquals(List.of(), set.snapshots());
            assertEquals(List.of(), set.findInRegion(ALL));
            assertEquals(List.of(), set.findNearest(ORIGIN, 3));
        }
    }

    @Test
    void snapshotNotAvailableIsAFailureAndTheOtherPartitionsAreAcquired() {
        SnapshotNotAvailableException notAvailable = new SnapshotNotAvailableException(Reason.OUT_OF_RETENTION, "old");
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        when(nodeB.acquire(any(), anyLong(), any())).thenThrow(notAvailable);
        acquirable(nodeC, acquisitionC, AcquireOptions.defaultTimeout());
        try (SnapshotSet2 set = space.acquire(List.of(a, b, c), TICK)) {
            assertFalse(set.isComplete());
            assertEquals(2, set.snapshots().size());
            assertEquals(List.of(new SnapshotSet2.Failure(b, FailureReason.OUT_OF_RETENTION, notAvailable)), set.failures());
        }
    }

    @Test
    void destroyedPartitionIsAFailure() {
        UnknownPartitionException destroyed = new UnknownPartitionException(acquisitionA.partitionId());
        when(nodeA.acquire(any(), anyLong(), any())).thenThrow(destroyed);
        try (SnapshotSet2 set = space.acquire(List.of(a), TICK)) {
            assertSame(destroyed, set.failures().getFirst().error());
            assertEquals(FailureReason.UNKNOWN_PARTITION, set.failures().getFirst().reason());
            assertEquals(List.of(), set.snapshots());
        }
    }

    @Test
    void otherErrorReleasesTheSnapshotsAcquiredBeforeAndIsThrown() {
        IllegalStateException bug = new IllegalStateException("bug");
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        when(nodeB.acquire(any(), anyLong(), any())).thenThrow(bug);
        assertSame(bug, assertThrows(IllegalStateException.class, () -> space.acquire(List.of(a, b, c), TICK)));
        verify(nodeA).release(acquisitionA);
        verify(nodeC, never()).acquire(any(), anyLong(), any());
    }

    @Test
    void wrongArgumentsFailBeforeAnyAcquisition() {
        assertThrows(NullPointerException.class, () -> space.acquire(null, TICK));
        assertThrows(NullPointerException.class, () -> space.acquire(Arrays.asList(a, null), TICK));
        assertThrows(NullPointerException.class, () -> space.acquire(List.of(a), TICK, null));
        assertThrows(IllegalArgumentException.class, () -> space.acquire(List.of(a), -1));
        assertThrows(IllegalArgumentException.class, () -> space.acquire(List.of(a, b, a), TICK));
        verify(nodeA, never()).acquire(any(), anyLong(), any());
        verify(nodeB, never()).acquire(any(), anyLong(), any());
    }

    @Test
    void partitionOfAnotherSpaceFailsBeforeAnyAcquisition() {
        Space2 other = new Space2(SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet));
        PartitionNode2 otherNode = mock(PartitionNode2.class);
        Partition2 foreign = other.createPartition(other.createNode(() -> otherNode), new PartitionName("foreign"));
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        assertThrows(IllegalArgumentException.class, () -> space.acquire(List.of(foreign, a), TICK));
        assertThrows(IllegalArgumentException.class, () -> space.acquire(List.of(a, foreign), TICK));
        verify(nodeA, never()).acquire(any(), anyLong(), any());
        verify(nodeA, never()).release(any(Acquisition.class));
        verify(otherNode, never()).acquire(any(), anyLong(), any());
    }

    @Test
    void closeReleasesTheOtherSnapshotsWhenAReleaseFailsAndThrowsTheError() {
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        acquirable(nodeB, acquisitionB, AcquireOptions.defaultTimeout());
        IllegalStateException failure = new IllegalStateException("release");
        doThrow(failure).when(nodeA).release(acquisitionA);
        SnapshotSet2 set = space.acquire(List.of(a, b), TICK);
        assertSame(failure, assertThrows(IllegalStateException.class, set::close));
        verify(nodeB).release(acquisitionB);
    }

    @Test
    void findInRegionJoinsTheResultsOfTheSnapshots() {
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        acquirable(nodeB, acquisitionB, AcquireOptions.defaultTimeout());
        when(nodeA.findInRegion(acquisitionA, ALL)).thenReturn(List.of(Entity2.entity(1, 1, 1)));
        when(nodeB.findInRegion(acquisitionB, ALL)).thenReturn(List.of(Entity2.entity(2, 2, 2), Entity2.entity(3, 3, 3)));
        try (SnapshotSet2 set = space.acquire(List.of(a, b), TICK)) {
            assertEquals(List.of(Entity2.entity(1, 1, 1), Entity2.entity(2, 2, 2), Entity2.entity(3, 3, 3)),
                    set.findInRegion(ALL));
        }
    }

    @Test
    void findNearestKeepsTheNearestOfAllTheSnapshotsByDistanceAndThenById() {
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        acquirable(nodeB, acquisitionB, AcquireOptions.defaultTimeout());
        when(nodeA.findNearest(acquisitionA, ORIGIN, 3))
                .thenReturn(List.of(Entity2.entity(7, 1, 0), Entity2.entity(1, 3, 0), Entity2.entity(2, 4, 0)));
        when(nodeB.findNearest(acquisitionB, ORIGIN, 3))
                .thenReturn(List.of(Entity2.entity(5, 0, 1), Entity2.entity(6, 0, 2), Entity2.entity(8, 0, 5)));
        try (SnapshotSet2 set = space.acquire(List.of(a, b), TICK)) {
            // 5 and 7 are at the same distance: the lower ID comes first.
            assertEquals(List.of(Entity2.entity(5, 0, 1), Entity2.entity(7, 1, 0), Entity2.entity(6, 0, 2)),
                    set.findNearest(ORIGIN, 3));
        }
    }

    @Test
    void findNearestWithANegativeCountFailsAlsoWithoutSnapshots() {
        try (SnapshotSet2 set = space.acquire(List.of(), TICK)) {
            assertThrows(IllegalArgumentException.class, () -> set.findNearest(ORIGIN, -1));
        }
    }

    /** Makes the node give the acquisition for the tick with the options. */
    private static void acquirable(PartitionNode2 node, Acquisition acquisition, AcquireOptions options) {
        when(node.acquire(acquisition.partitionId(), TICK, options)).thenReturn(acquisition);
    }
}
