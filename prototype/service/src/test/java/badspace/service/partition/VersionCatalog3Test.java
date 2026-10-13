package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.partition.IndexConfig;
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
        assertEquals(List.of(first), storage.catalog().versions());
        StorageVersion3 second = storage.commit(103);
        assertEquals(List.of(second), storage.catalog().versions());
        assertSame(second, storage.catalog().lastVersion());
    }

    @Test
    void withTwoPastVersionsTheLastThreePublishedCommitsStay() {
        PartitionStorage3 storage = new PartitionStorage3(IndexConfig.linearScan(), 2);
        storage.commit(100);
        assertEquals(List.of(0L, 100L), commitsOf(storage.catalog()));
        storage.commit(103);
        assertEquals(List.of(0L, 100L, 103L), commitsOf(storage.catalog()));
        storage.commit(107);
        // The skipped numbers make no version: 100 is still in the window.
        assertEquals(List.of(100L, 103L, 107L), commitsOf(storage.catalog()));
        storage.commit(110);
        assertEquals(List.of(103L, 107L, 110L), commitsOf(storage.catalog()));
    }

    @Test
    void oldCatalogDoesNotChangeAfterACommit() {
        PartitionStorage3 storage = new PartitionStorage3(IndexConfig.linearScan(), 1);
        storage.commit(100);
        VersionCatalog3 before = storage.catalog();
        storage.commit(103);
        assertEquals(List.of(0L, 100L), commitsOf(before));
        assertEquals(List.of(100L, 103L), commitsOf(storage.catalog()));
    }

    private static List<Long> commitsOf(VersionCatalog3 catalog) {
        return catalog.versions().stream().map(StorageVersion3::commit).toList();
    }
}
