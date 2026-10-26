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

import badspace.common.geometry.Box3;
import badspace.common.geometry.Point3;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity3;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.UnknownPartitionException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Checks the acquisitions, the failures and the aggregation of the queries with framework mocks of the nodes. */
class SnapshotSet3Test {
    private static final long TICK = 4;
    private static final Point3 ORIGIN = new Point3(0, 0, 0);
    private static final Box3 ALL = new Box3(new Point3(-100, -100, -100), new Point3(100, 100, 100));
    private final AtomicLong entityIds = new AtomicLong(10);
    private final AtomicLong partitionIds = new AtomicLong(100);
    private final Space3 space = new Space3(SpaceConfig.of(entityIds::incrementAndGet, partitionIds::incrementAndGet));
    // One node for each partition, so each mock answers for one partition only.
    private final PartitionNode3 nodeA = mock(PartitionNode3.class);
    private final PartitionNode3 nodeB = mock(PartitionNode3.class);
    private final PartitionNode3 nodeC = mock(PartitionNode3.class);
    private final Partition3 a = space.createPartition(nodeA, new PartitionName("a"));
    private final Partition3 b = space.createPartition(nodeB, new PartitionName("b"));
    private final Partition3 c = space.createPartition(nodeC, new PartitionName("c"));
    private final Acquisition acquisitionA = new Acquisition(new PartitionId(101), 1, TICK);
    private final Acquisition acquisitionB = new Acquisition(new PartitionId(102), 1, TICK);
    private final Acquisition acquisitionC = new Acquisition(new PartitionId(103), 1, TICK);

    @Test
    void acquiresTheTickOnEveryPartitionWithTheOptions() {
        AcquireOptions options = AcquireOptions.timeout(Duration.ofSeconds(5));
        acquirable(nodeA, acquisitionA, options);
        acquirable(nodeB, acquisitionB, options);
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(a, b), TICK, options)) {
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
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(a), TICK)) {
            assertTrue(set.isComplete());
        }
        verify(nodeA).acquire(acquisitionA.partitionId(), TICK, AcquireOptions.defaultTimeout());
    }

    @Test
    void emptyListGivesAnEmptyCompleteSet() {
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(), TICK)) {
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
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(a, b, c), TICK)) {
            assertFalse(set.isComplete());
            assertEquals(2, set.snapshots().size());
            assertEquals(List.of(new SnapshotSet3.Failure(b, notAvailable)), set.failures());
            assertEquals(Optional.of(Reason.OUT_OF_RETENTION), set.failures().getFirst().reason());
        }
    }

    @Test
    void destroyedPartitionIsAFailureWithoutReason() {
        UnknownPartitionException destroyed = new UnknownPartitionException(acquisitionA.partitionId());
        when(nodeA.acquire(any(), anyLong(), any())).thenThrow(destroyed);
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(a), TICK)) {
            assertSame(destroyed, set.failures().getFirst().error());
            assertEquals(Optional.empty(), set.failures().getFirst().reason());
            assertEquals(List.of(), set.snapshots());
        }
    }

    @Test
    void otherErrorReleasesTheSnapshotsAcquiredBeforeAndIsThrown() {
        IllegalStateException bug = new IllegalStateException("bug");
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        when(nodeB.acquire(any(), anyLong(), any())).thenThrow(bug);
        assertSame(bug, assertThrows(IllegalStateException.class, () -> SnapshotSet3.acquire(List.of(a, b, c), TICK)));
        verify(nodeA).release(acquisitionA);
        verify(nodeC, never()).acquire(any(), anyLong(), any());
    }

    @Test
    void wrongArgumentsFailBeforeAnyAcquisition() {
        assertThrows(NullPointerException.class, () -> SnapshotSet3.acquire(null, TICK));
        assertThrows(NullPointerException.class, () -> SnapshotSet3.acquire(Arrays.asList(a, null), TICK));
        assertThrows(NullPointerException.class, () -> SnapshotSet3.acquire(List.of(a), TICK, null));
        assertThrows(IllegalArgumentException.class, () -> SnapshotSet3.acquire(List.of(a), -1));
        assertThrows(IllegalArgumentException.class, () -> SnapshotSet3.acquire(List.of(a, b, a), TICK));
        verify(nodeA, never()).acquire(any(), anyLong(), any());
        verify(nodeB, never()).acquire(any(), anyLong(), any());
    }

    @Test
    void closeReleasesTheOtherSnapshotsWhenAReleaseFailsAndThrowsTheError() {
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        acquirable(nodeB, acquisitionB, AcquireOptions.defaultTimeout());
        IllegalStateException failure = new IllegalStateException("release");
        doThrow(failure).when(nodeA).release(acquisitionA);
        SnapshotSet3 set = SnapshotSet3.acquire(List.of(a, b), TICK);
        assertSame(failure, assertThrows(IllegalStateException.class, set::close));
        verify(nodeB).release(acquisitionB);
    }

    @Test
    void findInRegionJoinsTheResultsOfTheSnapshots() {
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        acquirable(nodeB, acquisitionB, AcquireOptions.defaultTimeout());
        when(nodeA.findInRegion(acquisitionA, ALL)).thenReturn(List.of(Entity3.entity(1, 1, 1, 0)));
        when(nodeB.findInRegion(acquisitionB, ALL)).thenReturn(List.of(Entity3.entity(2, 2, 2, 0), Entity3.entity(3, 3, 3, 0)));
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(a, b), TICK)) {
            assertEquals(List.of(Entity3.entity(1, 1, 1, 0), Entity3.entity(2, 2, 2, 0), Entity3.entity(3, 3, 3, 0)),
                    set.findInRegion(ALL));
        }
    }

    @Test
    void findNearestKeepsTheNearestOfAllTheSnapshotsByDistanceAndThenById() {
        acquirable(nodeA, acquisitionA, AcquireOptions.defaultTimeout());
        acquirable(nodeB, acquisitionB, AcquireOptions.defaultTimeout());
        when(nodeA.findNearest(acquisitionA, ORIGIN, 3))
                .thenReturn(List.of(Entity3.entity(7, 1, 0, 0), Entity3.entity(1, 3, 0, 0), Entity3.entity(2, 4, 0, 0)));
        when(nodeB.findNearest(acquisitionB, ORIGIN, 3))
                .thenReturn(List.of(Entity3.entity(5, 0, 1, 0), Entity3.entity(6, 0, 2, 0), Entity3.entity(8, 0, 5, 0)));
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(a, b), TICK)) {
            // 5 and 7 are at the same distance: the lower ID comes first.
            assertEquals(List.of(Entity3.entity(5, 0, 1, 0), Entity3.entity(7, 1, 0, 0), Entity3.entity(6, 0, 2, 0)),
                    set.findNearest(ORIGIN, 3));
        }
    }

    @Test
    void findNearestWithANegativeCountFailsAlsoWithoutSnapshots() {
        try (SnapshotSet3 set = SnapshotSet3.acquire(List.of(), TICK)) {
            assertThrows(IllegalArgumentException.class, () -> set.findNearest(ORIGIN, -1));
        }
    }

    /** Makes the node give the acquisition for the tick with the options. */
    private static void acquirable(PartitionNode3 node, Acquisition acquisition, AcquireOptions options) {
        when(node.acquire(acquisition.partitionId(), TICK, options)).thenReturn(acquisition);
    }
}
