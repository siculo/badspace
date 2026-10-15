package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.partition.IndexConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotCatalog3Test {

    @Test
    void newCatalogHasOnlyTheEmptySnapshot() {
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(2);
        assertEquals(List.of(0L), commitsOf(catalog));
        assertEquals(0, catalog.lastSnapshot().commit());
        assertEquals(0, catalog.lastSnapshot().size());
        assertEquals(2, catalog.retainedPastSnapshots());
    }

    @Test
    void negativeRetainedPastSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotCatalog3.empty(-1));
    }

    @Test
    void withZeroPastSnapshotsOnlyTheLastSnapshotStays() {
        PartitionStorage3 storage = new PartitionStorage3(IndexConfig.linearScan(), 0);
        StorageSnapshot3 first = storage.commit(100);
        assertSame(first, storage.catalog().previous(0));
        assertNull(storage.catalog().previous(1));
        StorageSnapshot3 second = storage.commit(103);
        assertSame(second, storage.catalog().previous(0));
        assertNull(storage.catalog().previous(1));
        assertSame(second, storage.catalog().lastSnapshot());
    }

    @Test
    void withTwoPastSnapshotsTheLastThreePublishedCommitsStay() {
        PartitionStorage3 storage = new PartitionStorage3(IndexConfig.linearScan(), 2);
        storage.commit(100);
        assertEquals(List.of(100L, 0L), commitsOf(storage.catalog()));
        storage.commit(103);
        assertEquals(List.of(103L, 100L, 0L), commitsOf(storage.catalog()));
        storage.commit(107);
        // The skipped numbers make no snapshot: 100 is still in the window.
        assertEquals(List.of(107L, 103L, 100L), commitsOf(storage.catalog()));
        storage.commit(110);
        assertEquals(List.of(110L, 107L, 103L), commitsOf(storage.catalog()));
    }

    @Test
    void atOrBeforeFindsTheGreatestCommitNotAfterTheTick() {
        SnapshotCatalog3 catalog = catalogWithCommits(2, 100, 103, 107);
        assertEquals(103, catalog.atOrBefore(105).commit());
        assertEquals(107, catalog.atOrBefore(107).commit());
        assertEquals(107, catalog.atOrBefore(Long.MAX_VALUE).commit());
        assertEquals(100, catalog.atOrBefore(100).commit());
        // 0 left the window: no snapshot is at tick 99 or before.
        assertNull(catalog.atOrBefore(99));
    }

    @Test
    void exactFindsOnlyThePublishedCommitsInTheWindow() {
        SnapshotCatalog3 catalog = catalogWithCommits(2, 100, 103, 107, 110);
        assertEquals(103, catalog.exact(103).commit());
        assertEquals(110, catalog.exact(110).commit());
        // 105 was never published, 100 left the window.
        assertNull(catalog.exact(105));
        assertNull(catalog.exact(100));
    }

    @Test
    void emptySnapshotIsInTheWindowUntilItLeaves() {
        SnapshotCatalog3 catalog = SnapshotCatalog3.empty(1);
        assertEquals(0, catalog.exact(0).commit());
        assertEquals(0, catalog.atOrBefore(0).commit());
        catalog = catalogWithCommits(1, 5);
        assertEquals(0, catalog.exact(0).commit());
        catalog = catalogWithCommits(1, 5, 6);
        assertNull(catalog.exact(0));
    }

    @Test
    void oldCatalogDoesNotChangeAfterACommit() {
        PartitionStorage3 storage = new PartitionStorage3(IndexConfig.linearScan(), 1);
        storage.commit(100);
        SnapshotCatalog3 before = storage.catalog();
        storage.commit(103);
        assertEquals(List.of(100L, 0L), commitsOf(before));
        assertEquals(List.of(103L, 100L), commitsOf(storage.catalog()));
    }

    private static SnapshotCatalog3 catalogWithCommits(int retainedPastSnapshots, long... commits) {
        PartitionStorage3 storage = new PartitionStorage3(IndexConfig.linearScan(), retainedPastSnapshots);
        for (long commit : commits) {
            storage.commit(commit);
        }
        return storage.catalog();
    }

    /** Returns the commits of the window, from the last one back, read with the offsets. */
    private static List<Long> commitsOf(SnapshotCatalog3 catalog) {
        List<Long> commits = new ArrayList<>();
        for (int offset = 0; catalog.previous(offset) != null; offset++) {
            commits.add(catalog.previous(offset).commit());
        }
        return commits;
    }
}
