package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.partition.SnapshotNotAvailableException.Reason;
import org.junit.jupiter.api.Test;

class SnapshotCatalog2Test {

    @Test
    void newCatalogIsAtTheCreationTickWithoutSnapshots() {
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(5, 2);
        assertEquals(5, catalog.tick());
        assertEquals(5, catalog.creationTick());
        assertEquals(2, catalog.retainedTicks());
        assertEquals(0, catalog.size());
        assertNull(catalog.latest());
        assertNull(catalog.at(4));
        assertNull(catalog.at(5));
    }

    @Test
    void retainedTicksLessThanOneAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotCatalog2.empty(0, 0));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCatalog2.empty(0, -1));
    }

    @Test
    void moveWithACommitPublishesItsSnapshotForTheTickThatEnds() {
        StorageSnapshot2 first = StorageSnapshot2.empty();
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(0, 3).advance(1, first, false);
        assertEquals(1, catalog.tick());
        assertSame(first, catalog.at(0));
        assertSame(first, catalog.latest());
        assertEquals(0, catalog.firstTickAt(0));
        assertNull(catalog.at(1));
    }

    @Test
    void ticksWithoutACommitShareTheSnapshotBefore() {
        StorageSnapshot2 first = StorageSnapshot2.empty();
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(0, 3).advance(1, first, false).advance(2, null, false).advance(4, null, false);
        assertEquals(1, catalog.size());
        for (long tick = 1; tick <= 3; tick++) {
            assertSame(first, catalog.at(tick));
        }
    }

    @Test
    void skippedTicksShareTheSnapshotOfTheCommitBeforeThem() {
        StorageSnapshot2 empty = StorageSnapshot2.empty();
        StorageSnapshot2 committed = StorageSnapshot2.empty();
        // Created at 100, at tick 102 with a commit, then a direct move to 105.
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(100, 5).advance(102, empty, false).advance(105, committed, false);
        assertSame(empty, catalog.at(100));
        assertSame(empty, catalog.at(101));
        assertSame(committed, catalog.at(102));
        assertSame(committed, catalog.at(103));
        assertSame(committed, catalog.at(104));
        assertNull(catalog.at(105));
    }

    @Test
    void windowHasTheLastKTicks() {
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(100, 5);
        StorageSnapshot2[] byTick = new StorageSnapshot2[110];
        for (int tick = 100; tick < 110; tick++) {
            byTick[tick] = StorageSnapshot2.empty();
            catalog = catalog.advance(tick + 1, byTick[tick], false);
        }
        assertEquals(110, catalog.tick());
        for (int tick = 105; tick < 110; tick++) {
            assertSame(byTick[tick], catalog.at(tick));
        }
        assertNull(catalog.at(104));
        assertNull(catalog.at(110));
        assertEquals(5, catalog.size());
    }

    @Test
    void snapshotLeavesTheWindowWhenAllItsTicksAreBeforeIt() {
        StorageSnapshot2 first = StorageSnapshot2.empty();
        StorageSnapshot2 second = StorageSnapshot2.empty();
        // first is valid for the ticks from 0 to 4.
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(0, 2).advance(1, first, false).advance(5, null, false);
        catalog = catalog.advance(6, second, false);
        // The window has the ticks 4 and 5: first is still in it for tick 4.
        assertEquals(2, catalog.size());
        assertSame(first, catalog.at(4));
        catalog = catalog.advance(7, null, false);
        assertEquals(1, catalog.size());
        assertSame(second, catalog.snapshotAt(0));
        assertSame(second, catalog.at(5));
        assertSame(second, catalog.at(6));
    }

    @Test
    void jumpLongerThanTheWindowLeavesOnlyTheLastSnapshot() {
        StorageSnapshot2 first = StorageSnapshot2.empty();
        StorageSnapshot2 second = StorageSnapshot2.empty();
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(0, 1).advance(1, first, false).advance(10, second, false);
        assertEquals(1, catalog.size());
        assertSame(second, catalog.at(9));
        assertNull(catalog.at(8));
    }

    @Test
    void reasonSaysWhyATickIsNotInTheWindow() {
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(10, 5).advance(20, StorageSnapshot2.empty(), false);
        assertEquals(Reason.NOT_YET_PUBLISHED, catalog.reasonNotAvailable(20));
        assertEquals(Reason.NOT_YET_PUBLISHED, catalog.reasonNotAvailable(25));
        assertEquals(Reason.OUT_OF_RETENTION, catalog.reasonNotAvailable(14));
        assertEquals(Reason.BEFORE_CREATION, catalog.reasonNotAvailable(9));
        assertNull(catalog.at(14));
        assertSame(catalog.latest(), catalog.at(15));
    }

    @Test
    void noTickBeforeTheCreationIsInTheWindow() {
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(10, 5).advance(11, StorageSnapshot2.empty(), false);
        assertSame(catalog.latest(), catalog.at(10));
        assertNull(catalog.at(9));
        assertEquals(Reason.BEFORE_CREATION, catalog.reasonNotAvailable(9));
    }

    @Test
    void oldCatalogDoesNotChangeAfterAMove() {
        StorageSnapshot2 first = StorageSnapshot2.empty();
        SnapshotCatalog2 before = SnapshotCatalog2.empty(0, 1).advance(1, first, false);
        SnapshotCatalog2 after = before.advance(2, StorageSnapshot2.empty(), false);
        assertEquals(1, before.tick());
        assertSame(first, before.at(0));
        assertNull(after.at(0));
    }

    @Test
    void retiredCatalogHasNoTickFromTheRemovalOn() {
        StorageSnapshot2 last = StorageSnapshot2.empty();
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(0, 5).advance(1, StorageSnapshot2.empty(), false)
                .advance(3, last, true);
        assertEquals(3, catalog.removalTick());
        assertSame(last, catalog.at(2));
        catalog = catalog.advance(6, null, false);
        assertSame(last, catalog.at(2));
        assertNull(catalog.at(3));
        assertNull(catalog.at(5));
        assertEquals(Reason.AFTER_REMOVAL, catalog.reasonNotAvailable(3));
        assertEquals(Reason.AFTER_REMOVAL, catalog.reasonNotAvailable(6));
    }

    @Test
    void ticksOfARemovedPartitionLeaveTheWindowOneAfterTheOther() {
        // k = 5 and removal tick 105, as in the bundle.
        SnapshotCatalog2 catalog = SnapshotCatalog2.empty(100, 5);
        for (int tick = 100; tick < 104; tick++) {
            catalog = catalog.advance(tick + 1, StorageSnapshot2.empty(), false);
        }
        StorageSnapshot2 last = StorageSnapshot2.empty();
        catalog = catalog.advance(105, last, true);
        for (long tick = 100; tick < 105; tick++) {
            assertTrue(catalog.at(tick) != null);
        }
        catalog = catalog.advance(109, null, false);
        assertSame(last, catalog.at(104));
        assertNull(catalog.at(103));
        assertEquals(1, catalog.size());
        catalog = catalog.advance(110, null, false);
        assertEquals(0, catalog.size());
        assertNull(catalog.latest());
        assertEquals(Reason.OUT_OF_RETENTION, catalog.reasonNotAvailable(104));
    }

    @Test
    void notRemovedCatalogHasNoRemovalTick() {
        assertEquals(SnapshotCatalog2.NOT_REMOVED, SnapshotCatalog2.empty(0, 1).removalTick());
    }
}
