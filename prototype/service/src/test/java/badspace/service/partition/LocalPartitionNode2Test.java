package badspace.service.partition;

import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.common.partition.AcquireOptions.timeout;
import static badspace.common.partition.Entity2.entity;
import static badspace.common.partition.PartitionName.partitionName;
import static badspace.service.partition.Names.nameOf;
import static badspace.service.partition.Owners.owner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Circle2;
import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.WriteToken;
import badspace.service.partition.snapshot.MonotonicClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class LocalPartitionNode2Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private LocalPartitionNode2 node;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode2();
        node.createPartition(P1, nameOf(P1));
    }

    @Test
    void partitionUsesTheIndexChosenAtCreation() {
        node.createPartition(P2, nameOf(P2), IndexConfig.uniformGrid(1));
        node.insertAll(owner(node, P2), List.of(entity(1, 1, 2), entity(2, 5, 5)));
        assertEquals(List.of(entity(1, 1, 2)), node.findNearest(publish(P2), new Point2(0, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P2, nameOf(P2), IndexConfig.linearScan()));
    }

    @Test
    void partitionCanUseTheGridQuadtree() {
        node.createPartition(P2, nameOf(P2), IndexConfig.gridQuadtree(1, 1));
        node.insertAll(owner(node, P2), List.of(entity(1, 1, 2), entity(2, 5, 5)));
        assertEquals(List.of(entity(1, 1, 2)), node.findNearest(publish(P2), new Point2(0, 0), 1));
    }

    @Test
    void limitsComeFromTheIndexOfThePartition() {
        node.createPartition(P2, nameOf(P2), IndexConfig.gridQuadtree(128));
        assertEquals(IndexConfig.linearScan().limits(), node.limits(P1));
        assertEquals(IndexConfig.gridQuadtree(128).limits(), node.limits(P2));
        node.insertAll(owner(node, P1), List.of(entity(3, 0x1p40, 0)));
        assertThrows(IllegalArgumentException.class, () -> node.insertAll(owner(node, P2), List.of(entity(3, 0x1p40, 0))));
        assertEquals(0, node.size(publish(P2)));
    }

    @Test
    void insertAddsEntitiesToTheirPartitionOnly() {
        node.createPartition(P2, nameOf(P2));
        node.insertAll(owner(node, P1), List.of(entity(42, 1, 2), entity(43, 3, 4)));
        assertEquals(2, node.size(publish(P1)));
        assertEquals(0, node.size(publish(P2)));
    }

    @Test
    void getReturnsFoundEntitiesInOrderAndSkipsUnknownIds() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 2), entity(2, 3, 4)));
        assertEquals(List.of(entity(2, 3, 4), entity(1, 1, 2)), node.getAll(publish(P1), new long[] {2, 99, 1}));
    }

    @Test
    void growsBeyondInitialCapacity() {
        List<Entity2> entities = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entities.add(entity(i, i, -i));
        }
        node.insertAll(owner(node, P1), entities.subList(0, 10));
        node.insertAll(owner(node, P1), entities.subList(10, 1000));
        assertEquals(1000, node.size(publish(P1)));
        assertEquals(List.of(entity(999, 999, -999)), node.getAll(publish(P1), new long[] {999}));
    }

    @Test
    void insertRejectsDuplicateIdsAndChangesNothing() {
        node.insertAll(owner(node, P1), List.of(entity(1, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> node.insertAll(owner(node, P1), List.of(entity(2, 0, 0), entity(1, 0, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> node.insertAll(owner(node, P1), List.of(entity(3, 0, 0), entity(3, 0, 0))));
        assertEquals(1, node.size(publish(P1)));
    }

    @Test
    void updateMovesEntities() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 2), entity(2, 3, 4)));
        node.updateAll(owner(node, P1), List.of(entity(2, 5, 6), entity(1, 7, 8)));
        assertEquals(List.of(entity(1, 7, 8), entity(2, 5, 6)), node.getAll(publish(P1), new long[] {1, 2}));
    }

    @Test
    void updateRejectsUnknownIdAndChangesNothing() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 2)));
        assertThrows(NoSuchElementException.class,
                () -> node.updateAll(owner(node, P1), List.of(entity(1, 9, 9), entity(99, 0, 0))));
        assertEquals(List.of(entity(1, 1, 2)), node.getAll(publish(P1), new long[] {1}));
    }

    @Test
    void removeKeepsTheOtherEntities() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1), entity(2, 2, 2), entity(3, 3, 3), entity(4, 4, 4)));
        node.removeAll(owner(node, P1), new long[] {1, 3});
        assertEquals(2, node.size(publish(P1)));
        assertEquals(List.of(entity(2, 2, 2), entity(4, 4, 4)), node.getAll(publish(P1), new long[] {1, 2, 3, 4}));
        node.removeAll(owner(node, P1), new long[] {4, 2});
        assertEquals(0, node.size(publish(P1)));
    }

    @Test
    void removedIdCanBeUsedAgain() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.removeAll(owner(node, P1), new long[] {1});
        node.insertAll(owner(node, P1), List.of(entity(1, 2, 2)));
        assertEquals(List.of(entity(1, 2, 2)), node.getAll(publish(P1), new long[] {1}));
    }

    @Test
    void removeRejectsBadIdsAndChangesNothing() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1), entity(2, 2, 2)));
        assertThrows(NoSuchElementException.class, () -> node.removeAll(owner(node, P1), new long[] {1, 99}));
        assertThrows(IllegalArgumentException.class, () -> node.removeAll(owner(node, P1), new long[] {1, 1}));
        assertEquals(2, node.size(publish(P1)));
    }

    @Test
    void findInRegionWithBoxIncludesTheBorder() {
        node.insertAll(owner(node, P1), List.of(entity(1, 0, 0), entity(2, 2, 2), entity(3, 1, 1), entity(4, 3, 1)));
        assertEquals(Set.of(entity(1, 0, 0), entity(2, 2, 2), entity(3, 1, 1)),
                new HashSet<>(node.findInRegion(publish(P1), new Box2(new Point2(0, 0), new Point2(2, 2)))));
    }

    @Test
    void findInRegionWithRoundShapeIncludesTheBorder() {
        node.insertAll(owner(node, P1), List.of(entity(1, 0, 0), entity(2, 3, 0), entity(3, 0, -3), entity(4, 3, 3)));
        assertEquals(Set.of(entity(1, 0, 0), entity(2, 3, 0), entity(3, 0, -3)),
                new HashSet<>(node.findInRegion(publish(P1), new Circle2(new Point2(0, 0), 3))));
    }

    @Test
    void findInRegionSeesUpdatesAndRemovals() {
        node.insertAll(owner(node, P1), List.of(entity(1, 0, 0), entity(2, 1, 1), entity(3, 9, 9)));
        node.updateAll(owner(node, P1), List.of(entity(3, 1, 0)));
        node.removeAll(owner(node, P1), new long[] {2});
        assertEquals(Set.of(entity(1, 0, 0), entity(3, 1, 0)),
                new HashSet<>(node.findInRegion(publish(P1), new Circle2(new Point2(0, 0), 2))));
    }

    @Test
    void findInRegionOfEmptyPartitionIsEmpty() {
        assertEquals(List.of(), node.findInRegion(publish(P1), new Circle2(new Point2(0, 0), 100)));
    }

    @Test
    void findNearestSortsByDistanceThenById() {
        node.insertAll(owner(node, P1), List.of(entity(5, 4, 0), entity(4, 0, 1), entity(3, -1, 0), entity(2, 0, -2), entity(1, 10, 10)));
        assertEquals(List.of(entity(3, -1, 0), entity(4, 0, 1), entity(2, 0, -2)),
                node.findNearest(publish(P1), new Point2(0, 0), 3));
    }

    @Test
    void findNearestReturnsAllEntitiesWhenCountIsLarger() {
        node.insertAll(owner(node, P1), List.of(entity(1, 2, 0), entity(2, 1, 0)));
        assertEquals(List.of(entity(2, 1, 0), entity(1, 2, 0)), node.findNearest(publish(P1), new Point2(0, 0), 10));
    }

    @Test
    void findNearestWithZeroCountIsEmptyAndNegativeCountFails() {
        node.insertAll(owner(node, P1), List.of(entity(1, 0, 0)));
        assertEquals(List.of(), node.findNearest(publish(P1), new Point2(0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> node.findNearest(publish(P1), new Point2(0, 0), -1));
    }

    @Test
    void findNearestKeepsTheNearestAmongManyEntities() {
        List<Entity2> entities = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entities.add(entity(i, i, 0));
        }
        node.insertAll(owner(node, P1), entities);
        assertEquals(List.of(entity(500, 500, 0), entity(499, 499, 0), entity(501, 501, 0)),
                node.findNearest(publish(P1), new Point2(500, 0), 3));
    }


    @Test
    void commitIsPublishedAtTheMoveOfTheTick() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P1));
        assertReason(Reason.NOT_YET_PUBLISHED, () -> node.acquireLatest(P1, defaultTimeout()));
        node.setTick(1);
        Acquisition acquisition = node.acquireLatest(P1, defaultTimeout());
        assertEquals(0, acquisition.tick());
        assertEquals(1, node.size(acquisition));
    }

    @Test
    void eachPartitionHasItsOwnCommits() {
        node.createPartition(P2, nameOf(P2));
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P1));
        node.setTick(1);
        assertEquals(1, node.size(node.acquireLatest(P1, defaultTimeout())));
        assertEquals(0, node.size(node.acquireLatest(P2, defaultTimeout())));
    }

    @Test
    void newNodeAndItsPartitionsAreAtTickZero() {
        assertEquals(0, node.tick());
        assertEquals(0, node.tick(P1));
        assertEquals(0, node.creationTick(P1));
    }

    @Test
    void setTickMovesTheNodeAndItsPartitionsAndAllowsGaps() {
        node.createPartition(P2, nameOf(P2));
        node.setTick(1);
        node.setTick(5);
        assertEquals(5, node.tick());
        assertEquals(5, node.tick(P1));
        assertEquals(5, node.tick(P2));
    }

    @Test
    void setTickWithTheSameTickDoesNothing() {
        node.setTick(3);
        node.setTick(3);
        assertEquals(3, node.tick());
        assertEquals(3, node.tick(P1));
    }

    @Test
    void setTickRejectsAnEarlierTickAndChangesNothing() {
        node.setTick(3);
        assertThrows(IllegalArgumentException.class, () -> node.setTick(2));
        assertThrows(IllegalArgumentException.class, () -> node.setTick(-1));
        assertEquals(3, node.tick());
        assertEquals(3, node.tick(P1));
    }

    @Test
    void partitionIsCreatedAtTheTickOfTheNode() {
        node.setTick(7);
        node.createPartition(P2, nameOf(P2));
        assertEquals(7, node.creationTick(P2));
        assertEquals(7, node.tick(P2));
        assertEquals(0, node.creationTick(P1));
    }

    @Test
    void ticksRejectUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.tick(P2));
        assertThrows(IllegalArgumentException.class, () -> node.creationTick(P2));
    }

    @Test
    void partitionCreatedWhileTheNodeMovesDoesNotStayBehind() throws InterruptedException {
        int ticks = 2000;
        int created = 200;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread mover = new Thread(() -> {
            try {
                start.await();
                for (int t = 1; t <= ticks; t++) {
                    node.setTick(t);
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        mover.start();
        start.countDown();
        for (int i = 0; i < created; i++) {
            node.createPartition(new PartitionId(100 + i), nameOf(new PartitionId(100 + i)));
        }
        assertTimeoutPreemptively(TIMEOUT, () -> mover.join());
        assertNull(failure.get());
        for (int i = 0; i < created; i++) {
            PartitionId partitionId = new PartitionId(100 + i);
            assertEquals(ticks, node.tick(partitionId));
            assertTrue(node.creationTick(partitionId) <= ticks);
        }
    }

    @Test
    void acquiredSnapshotHasOnlyTheCommittedWrites() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.setTick(1);
        Acquisition beforeCommit = node.acquireLatest(P1, defaultTimeout());
        assertEquals(0, beforeCommit.tick());
        assertEquals(0, node.size(beforeCommit));
        node.commit(owner(node, P1));
        node.setTick(2);
        Acquisition afterCommit = node.acquireLatest(P1, defaultTimeout());
        assertEquals(1, afterCommit.tick());
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(afterCommit, new long[] {1}));
    }

    @Test
    void acquiredSnapshotDoesNotSeeLaterWrites() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P1));
        node.setTick(1);
        Acquisition acquisition = node.acquireLatest(P1, defaultTimeout());
        node.removeAll(owner(node, P1), new long[] {1});
        node.commit(owner(node, P1));
        node.setTick(2);
        assertEquals(new Acquisition(P1, acquisition.id(), 0), acquisition);
        assertEquals(1, node.size(acquisition));
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(acquisition, new long[] {1}));
        assertEquals(List.of(entity(1, 1, 1)), node.findNearest(acquisition, new Point2(0, 0), 1));
        assertEquals(0, node.size(node.acquireLatest(P1, defaultTimeout())));
    }

    @Test
    void newPartitionPublishesTheEmptySnapshotOfItsCreationTick() {
        assertReason(Reason.NOT_YET_PUBLISHED, () -> node.acquireLatest(P1, defaultTimeout()));
        assertReason(Reason.NOT_YET_PUBLISHED, () -> node.acquire(P1, 0, defaultTimeout()));
        node.setTick(1);
        Acquisition acquisition = node.acquireLatest(P1, defaultTimeout());
        assertEquals(0, acquisition.tick());
        assertEquals(0, node.size(acquisition));
        assertEquals(0, node.acquire(P1, 0, defaultTimeout()).tick());
    }

    @Test
    void acquisitionAtATickFindsItsSnapshotInTheWindow() {
        node.setTick(100);
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 5);
        for (int tick = 100; tick < 110; tick++) {
            node.insertAll(owner(node, P2), List.of(entity(tick, tick, 0)));
            node.commit(owner(node, P2));
            node.setTick(tick + 1);
        }
        // T = 110 and k = 5: the snapshots of the ticks from 105 to 109.
        for (long tick = 105; tick < 110; tick++) {
            Acquisition acquisition = node.acquire(P2, tick, defaultTimeout());
            assertEquals(tick, acquisition.tick());
            assertEquals(tick - 99, node.size(acquisition));
        }
        assertReason(Reason.OUT_OF_RETENTION, () -> node.acquire(P2, 104, defaultTimeout()));
        assertReason(Reason.NOT_YET_PUBLISHED, () -> node.acquire(P2, 110, defaultTimeout()));
        assertEquals(109, node.acquireLatest(P2, defaultTimeout()).tick());
    }

    @Test
    void skippedTicksHaveTheSnapshotOfTheLastCommitBeforeThem() {
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 5);
        node.setTick(102);
        node.insertAll(owner(node, P2), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P2));
        node.setTick(105);
        for (long tick = 102; tick < 105; tick++) {
            assertEquals(1, node.size(node.acquire(P2, tick, defaultTimeout())));
        }
        // The ticks before the commit have the empty snapshot.
        assertEquals(0, node.size(node.acquire(P2, 101, defaultTimeout())));
    }

    @Test
    void unavailableSnapshotsSayWhy() {
        node.setTick(10);
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 2);
        node.setTick(20);
        assertReason(Reason.NOT_YET_PUBLISHED, () -> node.acquire(P2, 20, defaultTimeout()));
        assertReason(Reason.NOT_YET_PUBLISHED, () -> node.acquire(P2, 25, defaultTimeout()));
        assertReason(Reason.OUT_OF_RETENTION, () -> node.acquire(P2, 17, defaultTimeout()));
        assertReason(Reason.BEFORE_CREATION, () -> node.acquire(P2, 9, defaultTimeout()));
        assertEquals(18, node.acquire(P2, 18, defaultTimeout()).tick());
    }

    @Test
    void negativeTickIsRejected() {
        node.setTick(1);
        assertThrows(IllegalArgumentException.class, () -> node.acquire(P1, -1, defaultTimeout()));
    }

    @Test
    void acquisitionsRejectUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.acquireLatest(P2, defaultTimeout()));
        assertThrows(IllegalArgumentException.class, () -> node.acquire(P2, 0, defaultTimeout()));
    }

    @Test
    void snapshotOutsideTheWindowStaysReadableWhileAcquired() {
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 2);
        node.insertAll(owner(node, P2), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P2));
        node.setTick(1);
        Acquisition acquisition = node.acquire(P2, 0, defaultTimeout());
        node.removeAll(owner(node, P2), new long[] {1});
        node.commit(owner(node, P2));
        node.setTick(2);
        node.setTick(3);
        assertReason(Reason.OUT_OF_RETENTION, () -> node.acquire(P2, 0, defaultTimeout()));
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(acquisition, new long[] {1}));
        node.release(acquisition);
        assertThrows(IllegalStateException.class, () -> node.size(acquisition));
    }

    @Test
    void twoReadersOfTheSameSnapshotAreIndependent() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P1));
        node.setTick(1);
        Acquisition first = node.acquireLatest(P1, defaultTimeout());
        Acquisition second = node.acquire(P1, 0, defaultTimeout());
        assertNotEquals(first.id(), second.id());
        node.release(first);
        assertThrows(IllegalStateException.class, () -> node.size(first));
        assertEquals(1, node.size(second));
    }

    @Test
    void failedAcquisitionDoesNotKeepThePartitionAlive() {
        node.setTick(1);
        Acquisition before = node.acquireLatest(P1, defaultTimeout());
        assertReason(Reason.NOT_YET_PUBLISHED, () -> node.acquire(P1, 1, defaultTimeout()));
        Acquisition after = node.acquireLatest(P1, defaultTimeout());
        node.release(before);
        node.release(after);
        node.dropPartition(owner(node, P1));
        node.setTick(2);
        node.setTick(3);
        assertThrows(IllegalArgumentException.class, () -> node.tick(P1));
    }

    @Test
    void releaseAgainDoesNothing() {
        node.setTick(1);
        Acquisition acquisition = node.acquireLatest(P1, defaultTimeout());
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
        node.createPartition(P2, nameOf(P2));
        node.setTick(1);
        Acquisition acquisition = node.acquireLatest(P1, defaultTimeout());
        Acquisition neverMade = new Acquisition(P1, acquisition.id() + 1, 0);
        assertThrows(IllegalArgumentException.class, () -> node.release(neverMade));
        assertThrows(IllegalArgumentException.class, () -> node.size(neverMade));
        Acquisition otherPartition = new Acquisition(P2, acquisition.id(), 0);
        assertThrows(IllegalArgumentException.class, () -> node.release(otherPartition));
        Acquisition wrongTick = new Acquisition(P1, acquisition.id(), 7);
        assertThrows(IllegalArgumentException.class, () -> node.size(wrongTick));
        assertEquals(0, node.size(acquisition));
    }

    @Test
    void failedQueryKeepsTheAcquisition() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P1));
        node.setTick(1);
        Acquisition acquisition = node.acquireLatest(P1, defaultTimeout());
        assertThrows(IllegalArgumentException.class, () -> node.findNearest(acquisition, new Point2(0, 0), -1));
        assertEquals(1, node.size(acquisition));
        node.release(acquisition);
        assertThrows(IllegalStateException.class, () -> node.size(acquisition));
    }


    @Test
    void queriesGiveBackTheSnapshotAlsoWhenTheyFail() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P1));
        node.setTick(1);
        Acquisition acquisition = node.acquireLatest(P1, defaultTimeout());
        assertEquals(1, node.size(acquisition));
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(acquisition, new long[] {1}));
        assertEquals(1, node.findInRegion(acquisition, new Circle2(new Point2(0, 0), 5)).size());
        assertThrows(IllegalArgumentException.class, () -> node.findNearest(acquisition, new Point2(0, 0), -1));
        Acquisition wrongTick = new Acquisition(P1, acquisition.id(), 7);
        assertThrows(IllegalArgumentException.class, () -> node.size(wrongTick));
        node.release(acquisition);
        // With no queries left, the snapshot ends when it leaves the window.
        node.commit(owner(node, P1));
        node.setTick(2);
        node.dropPartition(owner(node, P1));
        node.setTick(3);
        node.setTick(4);
        assertThrows(IllegalArgumentException.class, () -> node.tick(P1));
    }

    @Test
    void acquisitionsRejectNullOptions() {
        node.setTick(1);
        assertThrows(NullPointerException.class, () -> node.acquireLatest(P1, null));
        assertThrows(NullPointerException.class, () -> node.acquire(P1, 0, null));
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
        Acquisition acquisition = timedNode.acquire(P1, 0, timeout(TIMEOUT));
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
                timedNode.acquire(P1, 0, timeout(TIMEOUT)));
        clock.advance(TIMEOUT);
        for (Acquisition acquisition : acquisitions) {
            assertThrows(IllegalStateException.class, () -> timedNode.size(acquisition));
        }
    }

    @Test
    void defaultAndMaxTimeoutsExpireToo() {
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        Acquisition defaultTimeout = timedNode.acquireLatest(P1, defaultTimeout());
        Acquisition maxTimeout = timedNode.acquireLatest(P1, timeout(AcquireOptions.MAX_TIMEOUT));
        clock.advance(AcquireOptions.DEFAULT_TIMEOUT);
        assertThrows(IllegalStateException.class, () -> timedNode.size(defaultTimeout));
        assertEquals(1, timedNode.size(maxTimeout));
        clock.advance(AcquireOptions.MAX_TIMEOUT.minus(AcquireOptions.DEFAULT_TIMEOUT));
        assertThrows(IllegalStateException.class, () -> timedNode.size(maxTimeout));
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
        assertEquals(expired.tick(), renewed.tick());
        assertEquals(1, timedNode.size(renewed));
        assertThrows(IllegalStateException.class, () -> timedNode.size(expired));
    }

    @Test
    void timeoutDoesNotChangeTheOtherAcquisitions() {
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        Acquisition shortTimeout = timedNode.acquireLatest(P1, timeout(TIMEOUT));
        Acquisition longTimeout = timedNode.acquireLatest(P1, timeout(TIMEOUT.multipliedBy(2)));
        clock.advance(TIMEOUT);
        assertThrows(IllegalStateException.class, () -> timedNode.size(shortTimeout));
        assertEquals(1, timedNode.size(longTimeout));
    }

    @Test
    void commitRejectsUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.commit(owner(node, P2)));
    }

    @Test
    void removedPartitionIsDestroyedWhenItsTicksLeaveTheWindow() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P1));
        node.setTick(1);
        node.dropPartition(owner(node, P1));
        node.setTick(2);
        assertEquals(2, node.tick(P1));
        // Removal tick 2, k = 1: tick 1 is still in the window.
        Acquisition kept = node.acquire(P1, 1, defaultTimeout());
        assertEquals(1, node.size(kept));
        node.setTick(3);
        assertEquals(3, node.tick(P1));
        node.release(kept);
        assertThrows(IllegalArgumentException.class, () -> node.tick(P1));
        assertThrows(IllegalArgumentException.class, () -> node.acquireLatest(P1, defaultTimeout()));
    }

    @Test
    void removedPartitionWithoutAcquisitionsIsDestroyedByTheMoveOfTheTick() {
        node.setTick(1);
        node.removePartition(owner(node, P1));
        node.setTick(2);
        assertEquals(2, node.tick(P1));
        node.setTick(3);
        assertThrows(IllegalArgumentException.class, () -> node.tick(P1));
        assertThrows(IllegalArgumentException.class, () -> node.acquireLatest(P1, defaultTimeout()));
    }

    @Test
    void removedPartitionRejectsTheWriterButGivesItsSnapshots() {
        node.setTick(1);
        node.dropPartition(owner(node, P1));
        assertEquals(1, node.tick(P1));
        assertThrows(IllegalStateException.class, () -> node.insertAll(owner(node, P1), List.of(entity(1, 1, 1))));
        assertThrows(IllegalStateException.class, () -> node.commit(owner(node, P1)));
        assertThrows(IllegalStateException.class, () -> node.removePartition(owner(node, P1)));
        assertThrows(IllegalStateException.class, () -> node.dropPartition(owner(node, P1)));
        assertEquals(1, node.tick(P1));
        assertEquals(0, node.creationTick(P1));
        assertEquals(0, node.size(node.acquireLatest(P1, defaultTimeout())));
        assertEquals(0, node.size(node.acquire(P1, 0, defaultTimeout())));
    }

    @Test
    void removedPartitionGivesTheTicksBeforeTheRemovalWhileTheyAreInTheWindow() {
        // The example of the bundle: k = 5, removal requested at tick 102, next move to 105.
        node.setTick(100);
        node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 5);
        for (int tick = 100; tick <= 102; tick++) {
            node.setTick(tick);
            node.insertAll(owner(node, P2), List.of(entity(tick, tick, 0)));
            node.commit(owner(node, P2));
        }
        node.dropPartition(owner(node, P2));
        node.setTick(105);
        assertEquals(3, readSize(P2, 102));
        assertEquals(3, readSize(P2, 103));
        assertEquals(3, readSize(P2, 104));
        assertEquals(1, readSize(P2, 100));
        assertReason(Reason.AFTER_REMOVAL, () -> node.acquire(P2, 105, defaultTimeout()));
        Acquisition latest = node.acquireLatest(P2, defaultTimeout());
        assertEquals(104, latest.tick());
        node.release(latest);
        node.setTick(109);
        Acquisition last = node.acquire(P2, 104, defaultTimeout());
        assertEquals(104, last.tick());
        node.release(last);
        assertReason(Reason.AFTER_REMOVAL, () -> node.acquireLatest(P2, defaultTimeout()));
        assertReason(Reason.OUT_OF_RETENTION, () -> node.acquire(P2, 103, defaultTimeout()));
        node.setTick(110);
        assertThrows(IllegalArgumentException.class, () -> node.tick(P2));
    }

    @Test
    void removalDiscardsTheWritesNotCommitted() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P1));
        node.insertAll(owner(node, P1), List.of(entity(2, 2, 2)));
        node.dropPartition(owner(node, P1));
        node.setTick(1);
        assertEquals(List.of(entity(1, 1, 1)),
                node.getAll(node.acquireLatest(P1, defaultTimeout()), new long[] {1, 2}));
    }

    @Test
    void setTickWithTheSameTickDoesNotMakeTheRemovalEffective() {
        node.setTick(1);
        node.dropPartition(owner(node, P1));
        node.setTick(1);
        assertEquals(1, node.tick(P1));
        node.setTick(3);
        assertEquals(0, readSize(P1, 2));
        assertReason(Reason.AFTER_REMOVAL, () -> node.acquire(P1, 3, defaultTimeout()));
    }

    @Test
    void acquisitionStaysReadableAfterTheRemovalUntilItsRelease() {
        node.insertAll(owner(node, P1), List.of(entity(1, 1, 1)));
        node.commit(owner(node, P1));
        node.setTick(1);
        Acquisition acquisition = node.acquireLatest(P1, defaultTimeout());
        node.dropPartition(owner(node, P1));
        // Removal tick 5; at tick 6 no tick of the partition is in the window.
        node.setTick(5);
        node.setTick(6);
        assertEquals(6, node.tick(P1));
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(acquisition, new long[] {1}));
        node.release(acquisition);
        assertThrows(IllegalArgumentException.class, () -> node.tick(P1));
        assertThrows(IllegalStateException.class, () -> node.size(acquisition));
        // A release after the destruction does nothing, like the close of the view.
        node.release(acquisition);
    }

    @Test
    void destructionWaitsForTheLastAcquisition() {
        node.setTick(1);
        Acquisition first = node.acquireLatest(P1, defaultTimeout());
        Acquisition second = node.acquireLatest(P1, defaultTimeout());
        node.removePartition(owner(node, P1));
        node.setTick(2);
        node.setTick(3);
        node.release(first);
        assertEquals(3, node.tick(P1));
        assertEquals(0, node.size(second));
        node.release(second);
        assertThrows(IllegalArgumentException.class, () -> node.tick(P1));
    }

    @Test
    void removedPartitionKeepsItsIdUntilItIsDestroyed() {
        node.removePartition(owner(node, P1));
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P1, nameOf(P1)));
        node.setTick(1);
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P1, nameOf(P1)));
        node.setTick(2);
        node.createPartition(P1, nameOf(P1));
    }

    @Test
    void removedPartitionKeepsItsNameUntilItIsDestroyed() {
        node.removePartition(owner(node, P1));
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P2, nameOf(P1)));
        node.setTick(1);
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P2, nameOf(P1)));
        node.setTick(2);
        // Destroyed: a new partition can take the name, and continues its saves.
        node.createPartition(P2, nameOf(P1));
    }

    @Test
    void expiryFoundByTheMoveOfTheTickEndsTheRemoval() {
        // A reader that dies without releasing: the timeout ends the removal anyway.
        FakeClock clock = new FakeClock();
        LocalPartitionNode2 timedNode = timedNode(clock);
        Acquisition acquisition = timedNode.acquireLatest(P1, timeout(TIMEOUT));
        timedNode.dropPartition(owner(timedNode, P1));
        timedNode.setTick(2);
        timedNode.setTick(3);
        clock.advance(TIMEOUT);
        // The expiry alone is not an event.
        assertEquals(3, timedNode.tick(P1));
        timedNode.setTick(4);
        assertThrows(IllegalArgumentException.class, () -> timedNode.tick(P1));
        assertThrows(IllegalStateException.class, () -> timedNode.size(acquisition));
        timedNode.release(acquisition);
    }

    @Test
    void acquisitionOfAnUnknownPartitionIsLikeAReleasedOne() {
        Acquisition unknown = new Acquisition(P2, 1, 0);
        node.release(unknown);
        assertThrows(IllegalStateException.class, () -> node.size(unknown));
    }

    @Test
    void removeRejectsAPartitionWithCommittedEntitiesAndChangesNothing() {
        node.insertAll(owner(node, P1), List.of(new Entity2(1, new Point2(1, 1))));
        node.commit(owner(node, P1));
        assertThrows(IllegalStateException.class, () -> node.removePartition(owner(node, P1)));
        assertEquals(1, node.size(publish(P1)));
        node.insertAll(owner(node, P1), List.of(new Entity2(2, new Point2(2, 2))));
    }

    @Test
    void removeLooksAtTheLastCommitNotAtTheWritesAfterIt() {
        node.insertAll(owner(node, P1), List.of(new Entity2(1, new Point2(1, 1))));
        node.removePartition(owner(node, P1));
        assertThrows(IllegalStateException.class, () -> node.commit(owner(node, P1)));
    }

    @Test
    void dropRemovesPartitionWithItsEntities() {
        node.insertAll(owner(node, P1), List.of(new Entity2(1, new Point2(1, 1))));
        node.commit(owner(node, P1));
        node.dropPartition(owner(node, P1));
        node.setTick(1);
        assertEquals(1, node.size(node.acquireLatest(P1, defaultTimeout())));
    }

    @Test
    void removeAndDropRejectUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.removePartition(owner(node, P2)));
        assertThrows(IllegalArgumentException.class, () -> node.dropPartition(owner(node, P2)));
    }

    @Test
    void rejectsRetainedTicksLessThanOne() {
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P2, nameOf(P2), IndexConfig.linearScan(), 0));
        assertThrows(IllegalArgumentException.class, () -> node.tick(P2));
    }

    @Test
    void rejectsTheIdOfAPartitionOnTheNode() {
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P1, partitionName("other")));
        // The name was not taken by the failed creation.
        node.createPartition(P2, partitionName("other"));
    }

    @Test
    void rejectsTheNameOfAPartitionOnTheNode() {
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P2, nameOf(P1)));
        assertThrows(IllegalArgumentException.class, () -> node.tick(P2));
    }

    @Test
    void rejectsUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.insertAll(owner(node, P2), List.of(entity(1, 0, 0))));
    }

    /**
     * A node with the given clock and partition P1 with one entity, committed at tick 0 and
     * published by the move to tick 1.
     */
    private static LocalPartitionNode2 timedNode(FakeClock clock) {
        LocalPartitionNode2 timedNode = new LocalPartitionNode2(clock);
        timedNode.createPartition(P1, nameOf(P1));
        timedNode.insertAll(owner(timedNode, P1), List.of(entity(1, 1, 1)));
        timedNode.commit(owner(timedNode, P1));
        timedNode.setTick(1);
        return timedNode;
    }

    /** Reads through a real acquisition and releases exactly that acquisition. */
    private int readSize(PartitionId partitionId, long tick) {
        Acquisition acquisition = node.acquire(partitionId, tick, defaultTimeout());
        try {
            return node.size(acquisition);
        } finally {
            node.release(acquisition);
        }
    }

    /** Checks that the acquisition fails as not available, for the given reason. */
    private static void assertReason(Reason reason, Executable acquisition) {
        assertEquals(reason, assertThrows(SnapshotNotAvailableException.class, acquisition).reason());
    }

    @Test
    void expiryFoundByTheScanAfterAnAcquisitionDestroysTheRetiredPartition() {
        SteppingClock clock = new SteppingClock();
        PartitionStorage2[] storage = new PartitionStorage2[1];
        LocalPartitionNode2 timed = nodeWithClock(clock, storage);
        timed.createPartition(P1, nameOf(P1), IndexConfig.linearScan(), 1);
        WriteToken token = timed.claim(P1, 0).orElseThrow();
        timed.setTick(1);
        timed.removePartition(token);
        // Retired at tick 2: the snapshot of tick 1 is still in the window.
        timed.setTick(2);
        // The acquisition reads the clock to register, then the node reads it to remove the
        // expired acquisitions. Between the two readings the tick moves, so the snapshot leaves
        // the window and only the new acquisition keeps it; then the acquisition expires.
        clock.atReading(2, () -> {
            timed.setTick(3);
            clock.set(10);
        });
        Acquisition acquisition = timed.acquire(P1, 1, AcquireOptions.timeout(Duration.ofNanos(1)));
        assertDestroyed(timed, storage[0], acquisition);
    }

    @Test
    void expiryFoundByTheScanAfterACommitDestroysThePartitionRetiredInTheMeantime() {
        SteppingClock clock = new SteppingClock();
        PartitionStorage2[] storage = new PartitionStorage2[1];
        LocalPartitionNode2 timed = nodeWithClock(clock, storage);
        timed.createPartition(P1, nameOf(P1), IndexConfig.linearScan(), 1);
        WriteToken first = timed.claim(P1, 0).orElseThrow();
        timed.insertAll(first, List.of(entity(1, 1, 1)));
        timed.commit(first);
        timed.setTick(1);
        Acquisition acquisition = timed.acquire(P1, 0, AcquireOptions.timeout(Duration.ofNanos(1)));
        // The commit releases the lock of the partition, then the node reads the clock to
        // remove the expired acquisitions. Between the two, another owner takes the partition
        // and removes it, and the ticks move until no snapshot is in the window: only the
        // acquisition keeps one, and then it expires.
        clock.atReading(1, () -> {
            WriteToken second = timed.claim(P1, first.generation()).orElseThrow();
            timed.dropPartition(second);
            timed.setTick(2);
            timed.setTick(3);
            clock.set(10);
        });
        timed.commit(first);
        assertDestroyed(timed, storage[0], acquisition);
    }

    /** Returns a node with the given clock, which puts the storage of its partition in storage[0]. */
    private static LocalPartitionNode2 nodeWithClock(MonotonicClock clock, PartitionStorage2[] storage) {
        return new LocalPartitionNode2(clock, (index, retainedTicks, partitionClock, creationTick) ->
                storage[0] = new PartitionStorage2(index, retainedTicks, partitionClock, creationTick));
    }

    /**
     * Checks that the end of the last snapshot destroyed the partition: the storage has no
     * snapshots, acquisitions or buffers left, and the node no longer knows the partition.
     */
    private static void assertDestroyed(LocalPartitionNode2 node, PartitionStorage2 storage, Acquisition acquisition) {
        assertEquals(PartitionState.DESTROYED, storage.state());
        assertEquals(0, storage.liveSnapshotCount());
        assertEquals(0, storage.acquisitionCount());
        assertEquals(0, storage.pooledBufferCount());
        assertThrows(IllegalArgumentException.class, () -> node.tick(P1));
        assertThrows(IllegalStateException.class, () -> node.size(acquisition));
        node.setTick(node.tick() + 1);
    }

    /**
     * Commits the writes of a partition, moves the tick of the node by one and acquires the
     * snapshot just published, so a test can read what the writer wrote.
     */
    private Acquisition publish(PartitionId partitionId) {
        node.commit(owner(node, partitionId));
        node.setTick(node.tick() + 1);
        return node.acquireLatest(partitionId, defaultTimeout());
    }
}
