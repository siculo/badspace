package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.partition.IndexConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionCatalog3Test {

    @Test
    void newCatalogHasOnlyTheEmptyVersion() {
        VersionCatalog3 catalog = VersionCatalog3.empty(2);
        assertEquals(List.of(0L), commitsOf(catalog));
        assertEquals(0, catalog.lastVersion().commit());
        assertEquals(0, catalog.lastVersion().size());
        assertEquals(2, catalog.retainedPastVersions());
    }

    @Test
    void negativeRetainedPastVersionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> VersionCatalog3.empty(-1));
    }

    @Test
    void withZeroPastVersionsOnlyTheLastVersionStays() {
        PartitionStorage3 storage = new PartitionStorage3(IndexConfig.linearScan(), 0);
        StorageVersion3 first = storage.commit(100);
        assertSame(first, storage.catalog().previous(0));
        assertNull(storage.catalog().previous(1));
        StorageVersion3 second = storage.commit(103);
        assertSame(second, storage.catalog().previous(0));
        assertNull(storage.catalog().previous(1));
        assertSame(second, storage.catalog().lastVersion());
    }

    @Test
    void withTwoPastVersionsTheLastThreePublishedCommitsStay() {
        PartitionStorage3 storage = new PartitionStorage3(IndexConfig.linearScan(), 2);
        storage.commit(100);
        assertEquals(List.of(100L, 0L), commitsOf(storage.catalog()));
        storage.commit(103);
        assertEquals(List.of(103L, 100L, 0L), commitsOf(storage.catalog()));
        storage.commit(107);
        // The skipped numbers make no version: 100 is still in the window.
        assertEquals(List.of(107L, 103L, 100L), commitsOf(storage.catalog()));
        storage.commit(110);
        assertEquals(List.of(110L, 107L, 103L), commitsOf(storage.catalog()));
    }

    @Test
    void atOrBeforeFindsTheGreatestCommitNotAfterTheTick() {
        VersionCatalog3 catalog = catalogWithCommits(2, 100, 103, 107);
        assertEquals(103, catalog.atOrBefore(105).commit());
        assertEquals(107, catalog.atOrBefore(107).commit());
        assertEquals(107, catalog.atOrBefore(Long.MAX_VALUE).commit());
        assertEquals(100, catalog.atOrBefore(100).commit());
        // 0 left the window: no version is at tick 99 or before.
        assertNull(catalog.atOrBefore(99));
    }

    @Test
    void exactFindsOnlyThePublishedCommitsInTheWindow() {
        VersionCatalog3 catalog = catalogWithCommits(2, 100, 103, 107, 110);
        assertEquals(103, catalog.exact(103).commit());
        assertEquals(110, catalog.exact(110).commit());
        // 105 was never published, 100 left the window.
        assertNull(catalog.exact(105));
        assertNull(catalog.exact(100));
    }

    @Test
    void emptyVersionIsInTheWindowUntilItLeaves() {
        VersionCatalog3 catalog = VersionCatalog3.empty(1);
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
        VersionCatalog3 before = storage.catalog();
        storage.commit(103);
        assertEquals(List.of(100L, 0L), commitsOf(before));
        assertEquals(List.of(103L, 100L), commitsOf(storage.catalog()));
    }

    private static VersionCatalog3 catalogWithCommits(int retainedPastVersions, long... commits) {
        PartitionStorage3 storage = new PartitionStorage3(IndexConfig.linearScan(), retainedPastVersions);
        for (long commit : commits) {
            storage.commit(commit);
        }
        return storage.catalog();
    }

    /** Returns the commits of the window, from the last one back, read with the offsets. */
    private static List<Long> commitsOf(VersionCatalog3 catalog) {
        List<Long> commits = new ArrayList<>();
        for (int offset = 0; catalog.previous(offset) != null; offset++) {
            commits.add(catalog.previous(offset).commit());
        }
        return commits;
    }
}
