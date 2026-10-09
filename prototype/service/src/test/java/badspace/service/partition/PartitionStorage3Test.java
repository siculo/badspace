package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box3;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.service.index.IndexVersion3;
import badspace.service.index.SlotView3;
import badspace.service.index.SpatialIndex3;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks the calls that the storage makes to its index. */
class PartitionStorage3Test {

    private static final Point3 A = new Point3(1, 2, 3);
    private static final Point3 B = new Point3(3, 4, 5);
    private static final Point3 C = new Point3(5, 6, 7);
    private static final Point3 D = new Point3(7, 8, 9);

    private static final Point3 OUTSIDE = new Point3(0, 0, -11);
    private static final CoordinateLimits LIMITS = new CoordinateLimits(-10, 10);
    private static final Point3 LIMITS_MIN = new Point3(-10, -10, -10);
    private static final Point3 LIMITS_MAX = new Point3(10, 10, 10);
    private static final long[] ALL_IDS = {10, 20, 30, 40};

    private RecordingIndex index;
    private PartitionStorage3 storage;

    @BeforeEach
    void setUp() {
        storage = new PartitionStorage3(LIMITS, s -> index = new RecordingIndex(s));
    }

    @Test
    void factoryGetsTheStorage() {
        assertEquals(storage, index.storage);
    }

    @Test
    void insertNotifiesEachNewSlot() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
        assertEquals(List.of("inserted 0 " + A + " id=10", "inserted 1 " + B + " id=20"), index.calls);
    }

    @Test
    void updateNotifiesOldAndNewPosition() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
        index.calls.clear();
        storage.updateAll(List.of(new Entity3(20, C)));
        assertEquals(List.of("moved 1 " + B + " -> " + C + " now=" + C), index.calls);
    }

    @Test
    void removeOfLastSlotNotifiesOnlyTheRemoval() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
        index.calls.clear();
        storage.removeAll(new long[] {20});
        assertEquals(List.of("removed 1 " + B + " size=1"), index.calls);
    }

    @Test
    void removeOfOtherSlotNotifiesTheRemovalAndThenTheRelocation() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)));
        index.calls.clear();
        storage.removeAll(new long[] {10});
        assertEquals(List.of("removed 0 " + A + " size=2", "relocated 2 -> 0 " + C + " id=30"), index.calls);
    }

    @Test
    void removeOfManyNotifiesInOrder() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C), new Entity3(40, D)));
        index.calls.clear();
        storage.removeAll(new long[] {20, 40});
        assertEquals(List.of(
                "removed 1 " + B + " size=3", "relocated 3 -> 1 " + D + " id=40",
                "removed 1 " + D + " size=2", "relocated 2 -> 1 " + C + " id=30"), index.calls);
    }

    @Test
    void failedWritesNotifyNothing() {
        storage.insertAll(List.of(new Entity3(10, A)));
        index.calls.clear();
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity3(20, B), new Entity3(10, C))));
        assertThrows(NoSuchElementException.class,
                () -> storage.updateAll(List.of(new Entity3(10, B), new Entity3(99, C))));
        assertThrows(NoSuchElementException.class, () -> storage.removeAll(new long[] {10, 99}));
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(new Entity3(20, B), new Entity3(30, OUTSIDE))));
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateAll(List.of(new Entity3(10, B), new Entity3(10, OUTSIDE))));
        assertEquals(List.of(), index.calls);
        assertEquals(List.of(new Entity3(10, A)), storage.getAll(new long[] {10, 20, 30}));
    }

    @Test
    void writesOnTheLimitsAreAccepted() {
        storage.insertAll(List.of(new Entity3(10, new Point3(-10, -10, -10)), new Entity3(20, A)));
        storage.updateAll(List.of(new Entity3(20, new Point3(10, 10, 10))));
        assertEquals(2, storage.size());
        assertEquals(LIMITS, storage.limits());
    }

    @Test
    void storageWithAnIndexConfigUsesTheLimitsOfTheIndex() {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4, 2))) {
            PartitionStorage3 configured = new PartitionStorage3(config);
            double max = config.limits().max();
            configured.insertAll(List.of(new Entity3(10, new Point3(max, max, max))));
            assertThrows(IllegalArgumentException.class,
                    () -> configured.insertAll(List.of(new Entity3(20, new Point3(Math.nextUp(max), 0, 0)))));
            assertEquals(1, configured.size());
            assertEquals(config.limits(), configured.limits());
        }
    }

    @Test
    void queriesReturnTheEntitiesOfTheSlotsInTheGivenOrder() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)));
        index.result = new int[] {2, 0};
        List<Entity3> expected = List.of(new Entity3(30, C), new Entity3(10, A));
        assertEquals(expected, storage.findInRegion(new Box3(A, B)));
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
    void versionOfACommitDoesNotSeeLaterWrites() {
        for (IndexConfig config : List.of(
                IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4, 2))) {
            PartitionStorage3 configured = new PartitionStorage3(config);
            configured.insertAll(List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)));
            PartitionVersion3 first = configured.commit(1);
            // The removal of 10 moves 30 into its slot.
            configured.updateAll(List.of(new Entity3(30, D)));
            configured.removeAll(new long[] {10});
            configured.insertAll(List.of(new Entity3(40, A)));
            PartitionVersion3 second = configured.commit(2);
            configured.removeAll(new long[] {20});
            configured.updateAll(List.of(new Entity3(40, B)));

            String where = config.toString();
            checkVersion(first, 1, List.of(new Entity3(10, A), new Entity3(20, B), new Entity3(30, C)), where);
            checkVersion(second, 2, List.of(new Entity3(40, A), new Entity3(20, B), new Entity3(30, D)), where);
            assertEquals(List.of(new Entity3(30, D), new Entity3(40, B)), configured.getAll(ALL_IDS), where);
        }
    }

    @Test
    void commitWithoutInsertionsOrRemovalsSharesTheIds() {
        storage.insertAll(List.of(new Entity3(10, A), new Entity3(20, B)));
        SlotSnapshot3 first = storage.commit(1).slots();
        storage.updateAll(List.of(new Entity3(20, C)));
        SlotSnapshot3 moved = storage.commit(2).slots();
        assertSame(first.ids, moved.ids);
        assertSame(first.slotById, moved.slotById);
        assertNotSame(first.coords, moved.coords);
        assertEquals(B, first.positionAt(1));
        assertEquals(C, moved.positionAt(1));

        storage.removeAll(new long[] {10});
        SlotSnapshot3 removed = storage.commit(3).slots();
        assertNotSame(moved.ids, removed.ids);
        assertNotSame(moved.slotById, removed.slotById);
        storage.insertAll(List.of(new Entity3(30, D)));
        SlotSnapshot3 inserted = storage.commit(4).slots();
        assertNotSame(removed.ids, inserted.ids);
        assertNotSame(removed.slotById, inserted.slotById);
        assertEquals(2, moved.size());
        assertEquals(1, removed.size());
        assertEquals(2, inserted.size());
    }

    @Test
    void findNearestWithZeroCountDoesNotCallTheIndex() {
        storage.insertAll(List.of(new Entity3(10, A)));
        index.calls.clear();
        assertEquals(List.of(), storage.findNearest(A, 0));
        assertEquals(List.of(), index.calls);
    }

    /** Checks all the reads of the version, which must find exactly the expected entities, nearest to A first. */
    private static void checkVersion(PartitionVersion3 version, long commit, List<Entity3> expected, String where) {
        assertEquals(commit, version.commit(), where);
        assertEquals(Set.copyOf(expected), Set.copyOf(version.getAll(ALL_IDS)), where);
        assertEquals(expected.size(), version.getAll(ALL_IDS).size(), where);
        List<Entity3> inRegion = version.findInRegion(new Box3(LIMITS_MIN, LIMITS_MAX));
        assertEquals(Set.copyOf(expected), Set.copyOf(inRegion), where);
        assertEquals(expected.size(), inRegion.size(), where);
        assertEquals(expected, version.findNearest(A, ALL_IDS.length), where);
        assertEquals(List.of(), version.findNearest(A, 0), where);
    }

    /**
     * Index that records each call as a string, with some state of the
     * storage at that moment, and gives a fixed result to the queries.
     */
    private static final class RecordingIndex implements SpatialIndex3 {

        final SlotView3 storage;
        final List<String> calls = new ArrayList<>();
        int[] result = new int[0];

        RecordingIndex(SlotView3 storage) {
            this.storage = storage;
        }

        @Override
        public void inserted(int slot, Point3 position) {
            calls.add("inserted " + slot + " " + position + " id=" + storage.idAt(slot));
        }

        @Override
        public void moved(int slot, Point3 from, Point3 to) {
            calls.add("moved " + slot + " " + from + " -> " + to + " now=" + storage.positionAt(slot));
        }

        @Override
        public void removed(int slot, Point3 position) {
            calls.add("removed " + slot + " " + position + " size=" + storage.size());
        }

        @Override
        public void relocated(int from, int to, Point3 position) {
            calls.add("relocated " + from + " -> " + to + " " + position + " id=" + storage.idAt(to));
        }

        @Override
        public IndexVersion3 commit(long n) {
            calls.add("commit " + n);
            // These tests do not read the version of the index.
            return null;
        }

        @Override
        public int[] findInRegion(Region3 region) {
            calls.add("findInRegion");
            return result;
        }

        @Override
        public int[] findNearest(Point3 point, int count) {
            calls.add("findNearest");
            return result;
        }
    }
}
