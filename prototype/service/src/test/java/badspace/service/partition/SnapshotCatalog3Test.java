package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.partition.SnapshotNotAvailableException.Reason;
import org.junit.jupiter.api.Test;

class SnapshotCatalog3Test {

    @Test
    void newCatalogIsAtTheCreationTickWithoutSnapshots() {
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(5, 2);
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
        assertThrows(IllegalArgumentException.class, () -> SnapshotCatalog3.empty(0, 0));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCatalog3.empty(0, -1));
    }

    @Test
    void moveWithACommitPublishesItsSnapshotForTheTickThatEnds() {
        StorageSnapshot3 first = StorageSnapshot3.empty();
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(0, 3).advance(1, first);
        assertEquals(1, catalog.tick());
        assertSame(first, catalog.at(0));
        assertSame(first, catalog.latest());
        assertEquals(0, catalog.firstTickAt(0));
        assertNull(catalog.at(1));
    }

    @Test
    void ticksWithoutACommitShareTheSnapshotBefore() {
        StorageSnapshot3 first = StorageSnapshot3.empty();
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(0, 3).advance(1, first).advance(2, null).advance(4, null);
        assertEquals(1, catalog.size());
        for (long tick = 1; tick <= 3; tick++) {
            assertSame(first, catalog.at(tick));
        }
    }

    @Test
    void skippedTicksShareTheSnapshotOfTheCommitBeforeThem() {
        StorageSnapshot3 empty = StorageSnapshot3.empty();
        StorageSnapshot3 committed = StorageSnapshot3.empty();
        // Created at 100, at tick 102 with a commit, then a direct move to 105.
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(100, 5).advance(102, empty).advance(105, committed);
        assertSame(empty, catalog.at(100));
        assertSame(empty, catalog.at(101));
        assertSame(committed, catalog.at(102));
        assertSame(committed, catalog.at(103));
        assertSame(committed, catalog.at(104));
        assertNull(catalog.at(105));
    }

    @Test
    void windowHasTheLastKTicks() {
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(100, 5);
        StorageSnapshot3[] byTick = new StorageSnapshot3[110];
        for (int tick = 100; tick < 110; tick++) {
            byTick[tick] = StorageSnapshot3.empty();
            catalog = catalog.advance(tick + 1, byTick[tick]);
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
        StorageSnapshot3 first = StorageSnapshot3.empty();
        StorageSnapshot3 second = StorageSnapshot3.empty();
        // first is valid for the ticks from 0 to 4.
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(0, 2).advance(1, first).advance(5, null);
        catalog = catalog.advance(6, second);
        // The window has the ticks 4 and 5: first is still in it for tick 4.
        assertEquals(2, catalog.size());
        assertSame(first, catalog.at(4));
        catalog = catalog.advance(7, null);
        assertEquals(1, catalog.size());
        assertSame(second, catalog.snapshotAt(0));
        assertSame(second, catalog.at(5));
        assertSame(second, catalog.at(6));
    }

    @Test
    void jumpLongerThanTheWindowLeavesOnlyTheLastSnapshot() {
        StorageSnapshot3 first = StorageSnapshot3.empty();
        StorageSnapshot3 second = StorageSnapshot3.empty();
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(0, 1).advance(1, first).advance(10, second);
        assertEquals(1, catalog.size());
        assertSame(second, catalog.at(9));
        assertNull(catalog.at(8));
    }

    @Test
    void reasonSaysWhyATickIsNotInTheWindow() {
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(10, 5).advance(20, StorageSnapshot3.empty());
        assertEquals(Reason.NOT_YET_PUBLISHED, catalog.reasonNotAvailable(20));
        assertEquals(Reason.NOT_YET_PUBLISHED, catalog.reasonNotAvailable(25));
        assertEquals(Reason.OUT_OF_RETENTION, catalog.reasonNotAvailable(14));
        assertEquals(Reason.BEFORE_CREATION, catalog.reasonNotAvailable(9));
        assertNull(catalog.at(14));
        assertSame(catalog.latest(), catalog.at(15));
    }

    @Test
    void noTickBeforeTheCreationIsInTheWindow() {
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(10, 5).advance(11, StorageSnapshot3.empty());
        assertSame(catalog.latest(), catalog.at(10));
        assertNull(catalog.at(9));
        assertEquals(Reason.BEFORE_CREATION, catalog.reasonNotAvailable(9));
    }

    @Test
    void oldCatalogDoesNotChangeAfterAMove() {
        StorageSnapshot3 first = StorageSnapshot3.empty();
        SnapshotCatalog3 before = SnapshotCatalog3.empty(0, 1).advance(1, first);
        SnapshotCatalog3 after = before.advance(2, StorageSnapshot3.empty());
        assertEquals(1, before.tick());
        assertSame(first, before.at(0));
        assertNull(after.at(0));
    }

    @Test
    void closedCatalogHasNoSnapshotsAndKeepsTheTick() {
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(0, 3).advance(4, StorageSnapshot3.empty()).closed();
        assertEquals(4, catalog.tick());
        assertEquals(0, catalog.size());
        assertNull(catalog.at(3));
        assertEquals(0, catalog.advance(6, null).size());
    }
}
