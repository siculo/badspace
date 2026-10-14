package badspace.service.partition;

import static badspace.common.partition.AcquireOptions.noTimeout;
import static badspace.common.partition.AcquireOptions.timeout;
import static badspace.common.partition.Entity2.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Circle2;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.VersionNotAvailableException;
import badspace.common.geometry.Point2;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalPartitionNode2Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private LocalPartitionNode2 node;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode2();
        node.createPartition(P1);
    }

    @Test
    void partitionUsesTheIndexChosenAtCreation() {
        node.createPartition(P2, IndexConfig.uniformGrid(1));
        node.insertAll(P2, List.of(entity(1, 1, 2), entity(2, 5, 5)));
        assertEquals(List.of(entity(1, 1, 2)), node.findNearest(P2, new Point2(0, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P2, IndexConfig.linearScan()));
    }

    @Test
    void partitionCanUseTheGridQuadtree() {
        node.createPartition(P2, IndexConfig.gridQuadtree(1, 1));
        node.insertAll(P2, List.of(entity(1, 1, 2), entity(2, 5, 5)));
        assertEquals(List.of(entity(1, 1, 2)), node.findNearest(P2, new Point2(0, 0), 1));
    }

    @Test
    void limitsComeFromTheIndexOfThePartition() {
        node.createPartition(P2, IndexConfig.gridQuadtree(128));
        assertEquals(IndexConfig.linearScan().limits(), node.limits(P1));
        assertEquals(IndexConfig.gridQuadtree(128).limits(), node.limits(P2));
        node.insertAll(P1, List.of(entity(3, 0x1p40, 0)));
        assertThrows(IllegalArgumentException.class, () -> node.insertAll(P2, List.of(entity(3, 0x1p40, 0))));
        assertEquals(0, node.size(P2));
    }

    @Test
    void insertAddsEntitiesToTheirPartitionOnly() {
        node.createPartition(P2);
        node.insertAll(P1, List.of(entity(42, 1, 2), entity(43, 3, 4)));
        assertEquals(2, node.size(P1));
        assertEquals(0, node.size(P2));
    }

    @Test
    void getReturnsFoundEntitiesInOrderAndSkipsUnknownIds() {
        node.insertAll(P1, List.of(entity(1, 1, 2), entity(2, 3, 4)));
        assertEquals(List.of(entity(2, 3, 4), entity(1, 1, 2)), node.getAll(P1, new long[] {2, 99, 1}));
    }

    @Test
    void growsBeyondInitialCapacity() {
        List<Entity2> entities = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entities.add(entity(i, i, -i));
        }
        node.insertAll(P1, entities.subList(0, 10));
        node.insertAll(P1, entities.subList(10, 1000));
        assertEquals(1000, node.size(P1));
        assertEquals(List.of(entity(999, 999, -999)), node.getAll(P1, new long[] {999}));
    }

    @Test
    void insertRejectsDuplicateIdsAndChangesNothing() {
        node.insertAll(P1, List.of(entity(1, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> node.insertAll(P1, List.of(entity(2, 0, 0), entity(1, 0, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> node.insertAll(P1, List.of(entity(3, 0, 0), entity(3, 0, 0))));
        assertEquals(1, node.size(P1));
    }

    @Test
    void updateMovesEntities() {
        node.insertAll(P1, List.of(entity(1, 1, 2), entity(2, 3, 4)));
        node.updateAll(P1, List.of(entity(2, 5, 6), entity(1, 7, 8)));
        assertEquals(List.of(entity(1, 7, 8), entity(2, 5, 6)), node.getAll(P1, new long[] {1, 2}));
    }

    @Test
    void updateRejectsUnknownIdAndChangesNothing() {
        node.insertAll(P1, List.of(entity(1, 1, 2)));
        assertThrows(NoSuchElementException.class,
                () -> node.updateAll(P1, List.of(entity(1, 9, 9), entity(99, 0, 0))));
        assertEquals(List.of(entity(1, 1, 2)), node.getAll(P1, new long[] {1}));
    }

    @Test
    void removeKeepsTheOtherEntities() {
        node.insertAll(P1, List.of(entity(1, 1, 1), entity(2, 2, 2), entity(3, 3, 3), entity(4, 4, 4)));
        node.removeAll(P1, new long[] {1, 3});
        assertEquals(2, node.size(P1));
        assertEquals(List.of(entity(2, 2, 2), entity(4, 4, 4)), node.getAll(P1, new long[] {1, 2, 3, 4}));
        node.removeAll(P1, new long[] {4, 2});
        assertEquals(0, node.size(P1));
    }

    @Test
    void removedIdCanBeUsedAgain() {
        node.insertAll(P1, List.of(entity(1, 1, 1)));
        node.removeAll(P1, new long[] {1});
        node.insertAll(P1, List.of(entity(1, 2, 2)));
        assertEquals(List.of(entity(1, 2, 2)), node.getAll(P1, new long[] {1}));
    }

    @Test
    void removeRejectsBadIdsAndChangesNothing() {
        node.insertAll(P1, List.of(entity(1, 1, 1), entity(2, 2, 2)));
        assertThrows(NoSuchElementException.class, () -> node.removeAll(P1, new long[] {1, 99}));
        assertThrows(IllegalArgumentException.class, () -> node.removeAll(P1, new long[] {1, 1}));
        assertEquals(2, node.size(P1));
    }

    @Test
    void findInRegionWithBoxIncludesTheBorder() {
        node.insertAll(P1, List.of(entity(1, 0, 0), entity(2, 2, 2), entity(3, 1, 1), entity(4, 3, 1)));
        assertEquals(Set.of(entity(1, 0, 0), entity(2, 2, 2), entity(3, 1, 1)),
                new HashSet<>(node.findInRegion(P1, new Box2(new Point2(0, 0), new Point2(2, 2)))));
    }

    @Test
    void findInRegionWithRoundShapeIncludesTheBorder() {
        node.insertAll(P1, List.of(entity(1, 0, 0), entity(2, 3, 0), entity(3, 0, -3), entity(4, 3, 3)));
        assertEquals(Set.of(entity(1, 0, 0), entity(2, 3, 0), entity(3, 0, -3)),
                new HashSet<>(node.findInRegion(P1, new Circle2(new Point2(0, 0), 3))));
    }

    @Test
    void findInRegionSeesUpdatesAndRemovals() {
        node.insertAll(P1, List.of(entity(1, 0, 0), entity(2, 1, 1), entity(3, 9, 9)));
        node.updateAll(P1, List.of(entity(3, 1, 0)));
        node.removeAll(P1, new long[] {2});
        assertEquals(Set.of(entity(1, 0, 0), entity(3, 1, 0)),
                new HashSet<>(node.findInRegion(P1, new Circle2(new Point2(0, 0), 2))));
    }

    @Test
    void findInRegionOfEmptyPartitionIsEmpty() {
        assertEquals(List.of(), node.findInRegion(P1, new Circle2(new Point2(0, 0), 100)));
    }

    @Test
    void findNearestSortsByDistanceThenById() {
        node.insertAll(P1, List.of(entity(5, 4, 0), entity(4, 0, 1), entity(3, -1, 0), entity(2, 0, -2), entity(1, 10, 10)));
        assertEquals(List.of(entity(3, -1, 0), entity(4, 0, 1), entity(2, 0, -2)),
                node.findNearest(P1, new Point2(0, 0), 3));
    }

    @Test
    void findNearestReturnsAllEntitiesWhenCountIsLarger() {
        node.insertAll(P1, List.of(entity(1, 2, 0), entity(2, 1, 0)));
        assertEquals(List.of(entity(2, 1, 0), entity(1, 2, 0)), node.findNearest(P1, new Point2(0, 0), 10));
    }

    @Test
    void findNearestWithZeroCountIsEmptyAndNegativeCountFails() {
        node.insertAll(P1, List.of(entity(1, 0, 0)));
        assertEquals(List.of(), node.findNearest(P1, new Point2(0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> node.findNearest(P1, new Point2(0, 0), -1));
    }

    @Test
    void findNearestKeepsTheNearestAmongManyEntities() {
        List<Entity2> entities = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entities.add(entity(i, i, 0));
        }
        node.insertAll(P1, entities);
        assertEquals(List.of(entity(500, 500, 0), entity(499, 499, 0), entity(501, 501, 0)),
                node.findNearest(P1, new Point2(500, 0), 3));
    }

    @Test
    void queriesRejectUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.findInRegion(P2, new Circle2(new Point2(0, 0), 1)));
        assertThrows(IllegalArgumentException.class, () -> node.findNearest(P2, new Point2(0, 0), 1));
    }

    @Test
    void commitMovesTheCounterAndAllowsGaps() {
        assertEquals(0, node.lastCommit(P1));
        node.commit(P1, 1);
        node.commit(P1, 5);
        assertEquals(5, node.lastCommit(P1));
    }

    @Test
    void commitRejectsNumberNotGreaterThanTheLastAndChangesNothing() {
        assertThrows(IllegalArgumentException.class, () -> node.commit(P1, 0));
        assertThrows(IllegalArgumentException.class, () -> node.commit(P1, -1));
        node.commit(P1, 5);
        assertThrows(IllegalArgumentException.class, () -> node.commit(P1, 5));
        assertThrows(IllegalArgumentException.class, () -> node.commit(P1, 4));
        assertEquals(5, node.lastCommit(P1));
    }

    @Test
    void eachPartitionHasItsOwnCommits() {
        node.createPartition(P2);
        node.commit(P1, 7);
        assertEquals(0, node.lastCommit(P2));
        node.commit(P2, 1);
        assertEquals(7, node.lastCommit(P1));
    }

    @Test
    void acquiredVersionHasOnlyTheCommittedWrites() {
        node.insertAll(P1, List.of(entity(1, 1, 1)));
        Acquisition beforeCommit = node.acquireLatest(P1, noTimeout());
        assertEquals(0, beforeCommit.commit());
        assertEquals(0, node.size(beforeCommit));
        node.commit(P1, 4);
        Acquisition afterCommit = node.acquireLatest(P1, noTimeout());
        assertEquals(4, afterCommit.commit());
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(afterCommit, new long[] {1}));
    }

    @Test
    void acquiredVersionDoesNotSeeLaterWrites() {
        node.insertAll(P1, List.of(entity(1, 1, 1)));
        node.commit(P1, 4);
        Acquisition acquisition = node.acquireLatest(P1, noTimeout());
        node.removeAll(P1, new long[] {1});
        node.commit(P1, 5);
        assertEquals(new Acquisition(P1, acquisition.id(), 4), acquisition);
        assertEquals(1, node.size(acquisition));
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(acquisition, new long[] {1}));
        assertEquals(List.of(entity(1, 1, 1)), node.findNearest(acquisition, new Point2(0, 0), 1));
        assertEquals(0, node.size(P1));
    }

    @Test
    void newPartitionHasTheEmptyVersionAtCommitZero() {
        Acquisition acquisition = node.acquireLatest(P1, noTimeout());
        assertEquals(0, acquisition.commit());
        assertEquals(0, node.size(acquisition));
        assertEquals(0, node.acquireExact(P1, 0, noTimeout()).commit());
        assertEquals(0, node.acquireAtOrBefore(P1, 0, noTimeout()).commit());
    }

    @Test
    void selectionsChooseTheVersionInTheWindow() {
        node.createPartition(P2, IndexConfig.linearScan(), 2);
        node.commit(P2, 100);
        node.commit(P2, 103);
        node.commit(P2, 107);
        assertEquals(107, node.acquireLatest(P2, noTimeout()).commit());
        assertEquals(107, node.acquirePrevious(P2, 0, noTimeout()).commit());
        assertEquals(103, node.acquirePrevious(P2, 1, noTimeout()).commit());
        assertEquals(100, node.acquirePrevious(P2, 2, noTimeout()).commit());
        assertEquals(103, node.acquireAtOrBefore(P2, 105, noTimeout()).commit());
        assertEquals(103, node.acquireExact(P2, 103, noTimeout()).commit());
    }

    @Test
    void versionsOutsideTheWindowAreNotAvailable() {
        node.createPartition(P2, IndexConfig.linearScan(), 2);
        node.commit(P2, 100);
        node.commit(P2, 103);
        node.commit(P2, 107);
        assertThrows(VersionNotAvailableException.class, () -> node.acquirePrevious(P2, 3, noTimeout()));
        assertThrows(VersionNotAvailableException.class, () -> node.acquireAtOrBefore(P2, 99, noTimeout()));
        assertThrows(VersionNotAvailableException.class, () -> node.acquireExact(P2, 105, noTimeout()));
        assertThrows(VersionNotAvailableException.class, () -> node.acquireExact(P2, 0, noTimeout()));
    }

    @Test
    void negativeSelectionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> node.acquirePrevious(P1, -1, noTimeout()));
        assertThrows(IllegalArgumentException.class, () -> node.acquireAtOrBefore(P1, -1, noTimeout()));
        assertThrows(IllegalArgumentException.class, () -> node.acquireExact(P1, -1, noTimeout()));
    }

    @Test
    void acquisitionsRejectUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.acquireLatest(P2, noTimeout()));
        assertThrows(IllegalArgumentException.class, () -> node.acquirePrevious(P2, 0, noTimeout()));
        assertThrows(IllegalArgumentException.class, () -> node.acquireAtOrBefore(P2, 0, noTimeout()));
        assertThrows(IllegalArgumentException.class, () -> node.acquireExact(P2, 0, noTimeout()));
    }

    @Test
    void versionOutsideTheWindowStaysReadableWhileAcquired() {
        node.createPartition(P2, IndexConfig.linearScan(), 2);
        node.insertAll(P2, List.of(entity(1, 1, 1)));
        node.commit(P2, 100);
        Acquisition acquisition = node.acquireExact(P2, 100, noTimeout());
        node.removeAll(P2, new long[] {1});
        node.commit(P2, 103);
        node.commit(P2, 107);
        node.commit(P2, 110);
        assertThrows(VersionNotAvailableException.class, () -> node.acquireExact(P2, 100, noTimeout()));
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(acquisition, new long[] {1}));
        node.release(acquisition);
        assertThrows(IllegalStateException.class, () -> node.size(acquisition));
    }

    @Test
    void twoReadersOfTheSameVersionAreIndependent() {
        node.insertAll(P1, List.of(entity(1, 1, 1)));
        node.commit(P1, 1);
        Acquisition first = node.acquireLatest(P1, noTimeout());
        Acquisition second = node.acquireLatest(P1, noTimeout());
        assertNotEquals(first.id(), second.id());
        node.release(first);
        assertThrows(IllegalStateException.class, () -> node.size(first));
        assertEquals(1, node.size(second));
    }

    @Test
    void failedAcquisitionMakesNoAcquisition() {
        Acquisition before = node.acquireLatest(P1, noTimeout());
        assertThrows(VersionNotAvailableException.class, () -> node.acquirePrevious(P1, 1, noTimeout()));
        Acquisition after = node.acquireLatest(P1, noTimeout());
        // No number was used by the failed acquisition.
        assertEquals(before.id() + 1, after.id());
    }

    @Test
    void releaseAgainDoesNothing() {
        Acquisition acquisition = node.acquireLatest(P1, noTimeout());
        node.release(acquisition);
        node.release(acquisition);
        assertThrows(IllegalStateException.class, () -> node.size(acquisition));
        assertThrows(IllegalStateException.class, () -> node.getAll(acquisition, new long[] {1}));
        assertThrows(IllegalStateException.class,
                () -> node.findInRegion(acquisition, new Circle2(new Point2(0, 0), 1)));
        assertThrows(IllegalStateException.class, () -> node.findNearest(acquisition, new Point2(0, 0), 1));
    }

    @Test
    void unknownAcquisitionIsRejectedAndTheOthersDoNotChange() {
        node.createPartition(P2);
        Acquisition acquisition = node.acquireLatest(P1, noTimeout());
        Acquisition neverMade = new Acquisition(P1, acquisition.id() + 1, 0);
        assertThrows(IllegalArgumentException.class, () -> node.release(neverMade));
        assertThrows(IllegalArgumentException.class, () -> node.size(neverMade));
        Acquisition otherPartition = new Acquisition(P2, acquisition.id(), 0);
        assertThrows(IllegalArgumentException.class, () -> node.release(otherPartition));
        Acquisition wrongCommit = new Acquisition(P1, acquisition.id(), 7);
        assertThrows(IllegalArgumentException.class, () -> node.size(wrongCommit));
        assertEquals(0, node.size(acquisition));
    }

    @Test
    void failedQueryKeepsTheAcquisition() {
        node.insertAll(P1, List.of(entity(1, 1, 1)));
        node.commit(P1, 1);
        Acquisition acquisition = node.acquireLatest(P1, noTimeout());
        assertThrows(IllegalArgumentException.class, () -> node.findNearest(acquisition, new Point2(0, 0), -1));
        assertEquals(1, node.size(acquisition));
        node.release(acquisition);
        assertThrows(IllegalStateException.class, () -> node.size(acquisition));
    }

    @Test
    void writerStillReadsItsOwnWritesWhileAVersionIsAcquired() {
        node.commit(P1, 1);
        Acquisition acquisition = node.acquireLatest(P1, noTimeout());
        node.insertAll(P1, List.of(entity(1, 1, 1)));
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(P1, new long[] {1}));
        assertEquals(1, node.size(P1));
        assertEquals(0, node.size(acquisition));
    }

    @Test
    void queriesGiveBackTheVersionAlsoWhenTheyFail() {
        node.insertAll(P1, List.of(entity(1, 1, 1)));
        node.commit(P1, 1);
        Acquisition acquisition = node.acquireLatest(P1, noTimeout());
        assertEquals(1, node.size(acquisition));
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(acquisition, new long[] {1}));
        assertEquals(1, node.findInRegion(acquisition, new Circle2(new Point2(0, 0), 5)).size());
        assertThrows(IllegalArgumentException.class, () -> node.findNearest(acquisition, new Point2(0, 0), -1));
        Acquisition wrongCommit = new Acquisition(P1, acquisition.id(), 7);
        assertThrows(IllegalArgumentException.class, () -> node.size(wrongCommit));
        node.release(acquisition);
        // With no queries left, the version ends when it leaves the window.
        node.commit(P1, 2);
        assertEquals(1, node.find(P1).liveVersionCount());
    }

    @Test
    void acquisitionsRejectNullOptions() {
        assertThrows(NullPointerException.class, () -> node.acquireLatest(P1, null));
        assertThrows(NullPointerException.class, () -> node.acquirePrevious(P1, 0, null));
        assertThrows(NullPointerException.class, () -> node.acquireAtOrBefore(P1, 0, null));
        assertThrows(NullPointerException.class, () -> node.acquireExact(P1, 0, null));
    }

    @Test
    void acquisitionIsReadableUntilItsTimeout() {
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        Acquisition acquisition = timedNode.acquireLatest(P1, timeout(TIMEOUT));
        clock.advance(TIMEOUT.minusNanos(1));
        assertEquals(1, timedNode.size(acquisition));
        clock.advance(Duration.ofNanos(1));
        assertThrows(IllegalStateException.class, () -> timedNode.size(acquisition));
    }

    @Test
    void expiredAcquisitionFailsBeforeTheFirstQuery() {
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        Acquisition acquisition = timedNode.acquirePrevious(P1, 0, timeout(TIMEOUT));
        clock.advance(TIMEOUT);
        assertThrows(IllegalStateException.class, () -> timedNode.size(acquisition));
        assertThrows(IllegalStateException.class, () -> timedNode.getAll(acquisition, new long[] {1}));
        assertThrows(IllegalStateException.class,
                () -> timedNode.findInRegion(acquisition, new Circle2(new Point2(0, 0), 1)));
        assertThrows(IllegalStateException.class, () -> timedNode.findNearest(acquisition, new Point2(0, 0), 1));
    }

    @Test
    void eachSelectionTakesTheTimeout() {
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        List<Acquisition> acquisitions = List.of(
                timedNode.acquireLatest(P1, timeout(TIMEOUT)),
                timedNode.acquirePrevious(P1, 0, timeout(TIMEOUT)),
                timedNode.acquireAtOrBefore(P1, 1, timeout(TIMEOUT)),
                timedNode.acquireExact(P1, 1, timeout(TIMEOUT)));
        clock.advance(TIMEOUT);
        for (Acquisition acquisition : acquisitions) {
            assertThrows(IllegalStateException.class, () -> timedNode.size(acquisition));
        }
    }

    @Test
    void acquisitionWithoutTimeoutNeverExpires() {
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        Acquisition noTimeout = timedNode.acquireLatest(P1, noTimeout());
        // Too large for the clock: the same as no timeout.
        Acquisition tooLarge = timedNode.acquireLatest(P1, timeout(Duration.ofSeconds(Long.MAX_VALUE)));
        clock.advance(Duration.ofDays(100 * 365));
        timedNode.commit(P1, 2);
        assertEquals(1, timedNode.size(noTimeout));
        assertEquals(1, timedNode.size(tooLarge));
    }

    @Test
    void releaseAfterTheTimeoutDoesNothing() {
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        Acquisition acquisition = timedNode.acquireLatest(P1, timeout(TIMEOUT));
        clock.advance(TIMEOUT);
        timedNode.release(acquisition);
        // The release removed the expired acquisition: releasing it again does nothing too.
        timedNode.release(acquisition);
        assertThrows(IllegalStateException.class, () -> timedNode.size(acquisition));
    }

    @Test
    void newAcquisitionAfterTheTimeoutIsDistinct() {
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        Acquisition expired = timedNode.acquireLatest(P1, timeout(TIMEOUT));
        clock.advance(TIMEOUT);
        Acquisition renewed = timedNode.acquireLatest(P1, timeout(TIMEOUT));
        assertNotEquals(expired.id(), renewed.id());
        assertEquals(expired.commit(), renewed.commit());
        assertEquals(1, timedNode.size(renewed));
        assertThrows(IllegalStateException.class, () -> timedNode.size(expired));
    }

    @Test
    void timeoutDoesNotChangeTheOtherAcquisitions() {
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        Acquisition shortTimeout = timedNode.acquireLatest(P1, timeout(TIMEOUT));
        Acquisition longTimeout = timedNode.acquireLatest(P1, timeout(TIMEOUT.multipliedBy(2)));
        Acquisition noTimeout = timedNode.acquireLatest(P1, noTimeout());
        clock.advance(TIMEOUT);
        assertThrows(IllegalStateException.class, () -> timedNode.size(shortTimeout));
        assertEquals(1, timedNode.size(longTimeout));
        assertEquals(1, timedNode.size(noTimeout));
    }

    @Test
    void commitRejectsUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.commit(P2, 1));
        assertThrows(IllegalArgumentException.class, () -> node.lastCommit(P2));
    }

    @Test
    void removedPartitionIsGoneAndItsIdCanBeUsedAgain() {
        node.commit(P1, 3);
        node.removePartition(P1);
        assertThrows(IllegalArgumentException.class, () -> node.size(P1));
        node.createPartition(P1);
        assertEquals(0, node.size(P1));
        assertEquals(0, node.lastCommit(P1));
    }

    @Test
    void removeRejectsNonEmptyPartitionAndChangesNothing() {
        node.insertAll(P1, List.of(new Entity2(1, new Point2(1, 1))));
        assertThrows(IllegalStateException.class, () -> node.removePartition(P1));
        assertEquals(1, node.size(P1));
    }

    @Test
    void dropRemovesPartitionWithItsEntities() {
        node.insertAll(P1, List.of(new Entity2(1, new Point2(1, 1))));
        node.dropPartition(P1);
        assertThrows(IllegalArgumentException.class, () -> node.size(P1));
    }

    @Test
    void removeAndDropRejectUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.removePartition(P2));
        assertThrows(IllegalArgumentException.class, () -> node.dropPartition(P2));
    }

    @Test
    void rejectsNegativeRetainedPastVersions() {
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P2, IndexConfig.linearScan(), -1));
        assertThrows(IllegalArgumentException.class, () -> node.size(P2));
    }

    @Test
    void rejectsDuplicatePartition() {
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P1));
    }

    @Test
    void rejectsUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.insertAll(P2, List.of(entity(1, 0, 0))));
    }

    /** A node with the given clock and partition P1 with one entity, committed at commit 1. */
    private static LocalPartitionNode2 timedNode(FakeClock clock) {
        LocalPartitionNode2 timedNode = new LocalPartitionNode2(clock);
        timedNode.createPartition(P1);
        timedNode.insertAll(P1, List.of(entity(1, 1, 1)));
        timedNode.commit(P1, 1);
        return timedNode;
    }
}
