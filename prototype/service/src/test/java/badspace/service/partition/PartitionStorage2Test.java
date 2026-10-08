package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box2;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.service.index.IndexVersion2;
import badspace.service.index.SlotView2;
import badspace.service.index.SpatialIndex2;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks the calls that the storage makes to its index. */
class PartitionStorage2Test {

    private static final Point2 A = new Point2(1, 2);
    private static final Point2 B = new Point2(3, 4);
    private static final Point2 C = new Point2(5, 6);
    private static final Point2 D = new Point2(7, 8);

    private static final Point2 OUTSIDE = new Point2(11, 0);
    private static final CoordinateLimits LIMITS = new CoordinateLimits(-10, 10);

    private RecordingIndex index;
    private PartitionStorage2 storage;

    @BeforeEach
    void setUp() {
        storage = new PartitionStorage2(LIMITS, s -> index = new RecordingIndex(s));
    }

    @Test
    void factoryGetsTheStorage() {
        assertEquals(storage, index.storage);
    }

    @Test
    void insertNotifiesEachNewSlot() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        assertEquals(List.of("inserted 0 " + A + " id=10", "inserted 1 " + B + " id=20"), index.calls);
    }

    @Test
    void updateNotifiesOldAndNewPosition() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        index.calls.clear();
        storage.updateAll(List.of(new Entity2(20, C)));
        assertEquals(List.of("moved 1 " + B + " -> " + C + " now=" + C), index.calls);
    }

    @Test
    void removeOfLastSlotNotifiesOnlyTheRemoval() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B)));
        index.calls.clear();
        storage.removeAll(new long[] {20});
        assertEquals(List.of("removed 1 " + B + " size=1"), index.calls);
    }

    @Test
    void removeOfOtherSlotNotifiesTheRemovalAndThenTheRelocation() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C)));
        index.calls.clear();
        storage.removeAll(new long[] {10});
        assertEquals(List.of("removed 0 " + A + " size=2", "relocated 2 -> 0 " + C + " id=30"), index.calls);
    }

    @Test
    void removeOfManyNotifiesInOrder() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C), new Entity2(40, D)));
        index.calls.clear();
        storage.removeAll(new long[] {20, 40});
        assertEquals(List.of(
                "removed 1 " + B + " size=3", "relocated 3 -> 1 " + D + " id=40",
                "removed 1 " + D + " size=2", "relocated 2 -> 1 " + C + " id=30"), index.calls);
    }

    @Test
    void failedWritesNotifyNothing() {
        storage.insertAll(List.of(new Entity2(10, A)));
        index.calls.clear();
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity2(20, B), new Entity2(10, C))));
        assertThrows(NoSuchElementException.class,
                () -> storage.updateAll(List.of(new Entity2(10, B), new Entity2(99, C))));
        assertThrows(NoSuchElementException.class, () -> storage.removeAll(new long[] {10, 99}));
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity2(20, B), new Entity2(30, OUTSIDE))));
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateAll(List.of(new Entity2(10, B), new Entity2(10, OUTSIDE))));
        assertEquals(List.of(), index.calls);
        assertEquals(List.of(new Entity2(10, A)), storage.getAll(new long[] {10, 20, 30}));
    }

    @Test
    void writesOnTheLimitsAreAccepted() {
        storage.insertAll(List.of(new Entity2(10, new Point2(-10, -10)), new Entity2(20, A)));
        storage.updateAll(List.of(new Entity2(20, new Point2(10, 10))));
        assertEquals(2, storage.size());
        assertEquals(LIMITS, storage.limits());
    }

    @Test
    void storageWithAnIndexConfigUsesTheLimitsOfTheIndex() {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4, 2))) {
            PartitionStorage2 configured = new PartitionStorage2(config);
            double max = config.limits().max();
            configured.insertAll(List.of(new Entity2(10, new Point2(max, max))));
            assertThrows(IllegalArgumentException.class,
                    () -> configured.insertAll(List.of(new Entity2(20, new Point2(Math.nextUp(max), 0)))));
            assertEquals(1, configured.size());
            assertEquals(config.limits(), configured.limits());
        }
    }

    @Test
    void queriesReturnTheEntitiesOfTheSlotsInTheGivenOrder() {
        storage.insertAll(List.of(new Entity2(10, A), new Entity2(20, B), new Entity2(30, C)));
        index.result = new int[] {2, 0};
        List<Entity2> expected = List.of(new Entity2(30, C), new Entity2(10, A));
        assertEquals(expected, storage.findInRegion(new Box2(A, B)));
        assertEquals(expected, storage.findNearest(A, 2));
    }

    @Test
    void commitGoesToTheIndexOnlyWhenTheNumberIsValid() {
        storage.commit(3);
        assertThrows(IllegalArgumentException.class, () -> storage.commit(3));
        storage.commit(7);
        assertEquals(List.of("commit 3", "commit 7"), index.calls);
        assertEquals(7, storage.lastCommit());
    }

    @Test
    void findNearestWithZeroCountDoesNotCallTheIndex() {
        storage.insertAll(List.of(new Entity2(10, A)));
        index.calls.clear();
        assertEquals(List.of(), storage.findNearest(A, 0));
        assertEquals(List.of(), index.calls);
    }

    /**
     * Index that records each call as a string, with some state of the
     * storage at that moment, and gives a fixed result to the queries.
     */
    private static final class RecordingIndex implements SpatialIndex2 {

        final SlotView2 storage;
        final List<String> calls = new ArrayList<>();
        int[] result = new int[0];

        RecordingIndex(SlotView2 storage) {
            this.storage = storage;
        }

        @Override
        public void inserted(int slot, Point2 position) {
            calls.add("inserted " + slot + " " + position + " id=" + storage.idAt(slot));
        }

        @Override
        public void moved(int slot, Point2 from, Point2 to) {
            calls.add("moved " + slot + " " + from + " -> " + to + " now=" + storage.positionAt(slot));
        }

        @Override
        public void removed(int slot, Point2 position) {
            calls.add("removed " + slot + " " + position + " size=" + storage.size());
        }

        @Override
        public void relocated(int from, int to, Point2 position) {
            calls.add("relocated " + from + " -> " + to + " " + position + " id=" + storage.idAt(to));
        }

        @Override
        public IndexVersion2 commit(long n) {
            calls.add("commit " + n);
            // The storage does not use the version yet.
            return null;
        }

        @Override
        public int[] findInRegion(Region2 region) {
            calls.add("findInRegion");
            return result;
        }

        @Override
        public int[] findNearest(Point2 point, int count) {
            calls.add("findNearest");
            return result;
        }
    }
}
