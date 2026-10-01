package badspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.Box3;
import badspace.common.Entity3;
import badspace.common.IndexConfig;
import badspace.common.PartitionId;
import badspace.common.Point3;
import badspace.common.Sphere3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalPartitionNode3Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);

    private LocalPartitionNode3 node;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode3();
        node.createPartition(P1);
    }

    private static Entity3 entity(long id, double x, double y, double z) {
        return new Entity3(id, new Point3(x, y, z));
    }

    @Test
    void partitionUsesTheIndexChosenAtCreation() {
        node.createPartition(P2, IndexConfig.uniformGrid(1));
        node.insertAll(P2, List.of(entity(1, 1, 2, 3), entity(2, 5, 5, 5)));
        assertEquals(List.of(entity(1, 1, 2, 3)), node.findNearest(P2, new Point3(0, 0, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P2, IndexConfig.linearScan()));
    }

    @Test
    void insertAddsEntitiesToTheirPartitionOnly() {
        node.createPartition(P2);
        node.insertAll(P1, List.of(entity(42, 1, 2, 11), entity(43, 3, 4, 13)));
        assertEquals(2, node.size(P1));
        assertEquals(0, node.size(P2));
    }

    @Test
    void getReturnsFoundEntitiesInOrderAndSkipsUnknownIds() {
        node.insertAll(P1, List.of(entity(1, 1, 2, 11), entity(2, 3, 4, 13)));
        assertEquals(List.of(entity(2, 3, 4, 13), entity(1, 1, 2, 11)), node.getAll(P1, new long[] {2, 99, 1}));
    }

    @Test
    void growsBeyondInitialCapacity() {
        List<Entity3> entities = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entities.add(entity(i, i, -i, i + 10));
        }
        node.insertAll(P1, entities.subList(0, 10));
        node.insertAll(P1, entities.subList(10, 1000));
        assertEquals(1000, node.size(P1));
        assertEquals(List.of(entity(999, 999, -999, 1009)), node.getAll(P1, new long[] {999}));
    }

    @Test
    void insertRejectsDuplicateIdsAndChangesNothing() {
        node.insertAll(P1, List.of(entity(1, 0, 0, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> node.insertAll(P1, List.of(entity(2, 0, 0, 10), entity(1, 0, 0, 10))));
        assertThrows(IllegalArgumentException.class,
                () -> node.insertAll(P1, List.of(entity(3, 0, 0, 10), entity(3, 0, 0, 10))));
        assertEquals(1, node.size(P1));
    }

    @Test
    void updateMovesEntities() {
        node.insertAll(P1, List.of(entity(1, 1, 2, 11), entity(2, 3, 4, 13)));
        node.updateAll(P1, List.of(entity(2, 5, 6, 15), entity(1, 7, 8, 17)));
        assertEquals(List.of(entity(1, 7, 8, 17), entity(2, 5, 6, 15)), node.getAll(P1, new long[] {1, 2}));
    }

    @Test
    void updateRejectsUnknownIdAndChangesNothing() {
        node.insertAll(P1, List.of(entity(1, 1, 2, 11)));
        assertThrows(NoSuchElementException.class,
                () -> node.updateAll(P1, List.of(entity(1, 9, 9, 19), entity(99, 0, 0, 10))));
        assertEquals(List.of(entity(1, 1, 2, 11)), node.getAll(P1, new long[] {1}));
    }

    @Test
    void removeKeepsTheOtherEntities() {
        node.insertAll(P1, List.of(entity(1, 1, 1, 11), entity(2, 2, 2, 12), entity(3, 3, 3, 13), entity(4, 4, 4, 14)));
        node.removeAll(P1, new long[] {1, 3});
        assertEquals(2, node.size(P1));
        assertEquals(List.of(entity(2, 2, 2, 12), entity(4, 4, 4, 14)), node.getAll(P1, new long[] {1, 2, 3, 4}));
        node.removeAll(P1, new long[] {4, 2});
        assertEquals(0, node.size(P1));
    }

    @Test
    void removedIdCanBeUsedAgain() {
        node.insertAll(P1, List.of(entity(1, 1, 1, 11)));
        node.removeAll(P1, new long[] {1});
        node.insertAll(P1, List.of(entity(1, 2, 2, 12)));
        assertEquals(List.of(entity(1, 2, 2, 12)), node.getAll(P1, new long[] {1}));
    }

    @Test
    void removeRejectsBadIdsAndChangesNothing() {
        node.insertAll(P1, List.of(entity(1, 1, 1, 11), entity(2, 2, 2, 12)));
        assertThrows(NoSuchElementException.class, () -> node.removeAll(P1, new long[] {1, 99}));
        assertThrows(IllegalArgumentException.class, () -> node.removeAll(P1, new long[] {1, 1}));
        assertEquals(2, node.size(P1));
    }

    @Test
    void findInRegionWithBoxIncludesTheBorder() {
        node.insertAll(P1, List.of(entity(1, 0, 0, 0), entity(2, 2, 2, 2), entity(3, 1, 1, 1), entity(4, 3, 1, 1)));
        assertEquals(Set.of(entity(1, 0, 0, 0), entity(2, 2, 2, 2), entity(3, 1, 1, 1)),
                new HashSet<>(node.findInRegion(P1, new Box3(new Point3(0, 0, 0), new Point3(2, 2, 2)))));
    }

    @Test
    void findInRegionWithRoundShapeIncludesTheBorder() {
        node.insertAll(P1, List.of(entity(1, 0, 0, 0), entity(2, 3, 0, 0), entity(3, 0, -3, 0), entity(4, 3, 3, 0)));
        assertEquals(Set.of(entity(1, 0, 0, 0), entity(2, 3, 0, 0), entity(3, 0, -3, 0)),
                new HashSet<>(node.findInRegion(P1, new Sphere3(new Point3(0, 0, 0), 3))));
    }

    @Test
    void findInRegionSeesUpdatesAndRemovals() {
        node.insertAll(P1, List.of(entity(1, 0, 0, 0), entity(2, 1, 1, 0), entity(3, 9, 9, 0)));
        node.updateAll(P1, List.of(entity(3, 1, 0, 0)));
        node.removeAll(P1, new long[] {2});
        assertEquals(Set.of(entity(1, 0, 0, 0), entity(3, 1, 0, 0)),
                new HashSet<>(node.findInRegion(P1, new Sphere3(new Point3(0, 0, 0), 2))));
    }

    @Test
    void findInRegionOfEmptyPartitionIsEmpty() {
        assertEquals(List.of(), node.findInRegion(P1, new Sphere3(new Point3(0, 0, 0), 100)));
    }

    @Test
    void findNearestSortsByDistanceThenById() {
        node.insertAll(P1, List.of(entity(5, 4, 0, 0), entity(4, 0, 1, 0), entity(3, -1, 0, 0), entity(2, 0, -2, 0), entity(1, 10, 10, 0)));
        assertEquals(List.of(entity(3, -1, 0, 0), entity(4, 0, 1, 0), entity(2, 0, -2, 0)),
                node.findNearest(P1, new Point3(0, 0, 0), 3));
    }

    @Test
    void findNearestReturnsAllEntitiesWhenCountIsLarger() {
        node.insertAll(P1, List.of(entity(1, 2, 0, 0), entity(2, 1, 0, 0)));
        assertEquals(List.of(entity(2, 1, 0, 0), entity(1, 2, 0, 0)), node.findNearest(P1, new Point3(0, 0, 0), 10));
    }

    @Test
    void findNearestWithZeroCountIsEmptyAndNegativeCountFails() {
        node.insertAll(P1, List.of(entity(1, 0, 0, 0)));
        assertEquals(List.of(), node.findNearest(P1, new Point3(0, 0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> node.findNearest(P1, new Point3(0, 0, 0), -1));
    }

    @Test
    void findNearestKeepsTheNearestAmongManyEntities() {
        List<Entity3> entities = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entities.add(entity(i, i, 0, 0));
        }
        node.insertAll(P1, entities);
        assertEquals(List.of(entity(500, 500, 0, 0), entity(499, 499, 0, 0), entity(501, 501, 0, 0)),
                node.findNearest(P1, new Point3(500, 0, 0), 3));
    }

    @Test
    void queriesRejectUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.findInRegion(P2, new Sphere3(new Point3(0, 0, 0), 1)));
        assertThrows(IllegalArgumentException.class, () -> node.findNearest(P2, new Point3(0, 0, 0), 1));
    }

    @Test
    void removedPartitionIsGoneAndItsIdCanBeUsedAgain() {
        node.removePartition(P1);
        assertThrows(IllegalArgumentException.class, () -> node.size(P1));
        node.createPartition(P1);
        assertEquals(0, node.size(P1));
    }

    @Test
    void removeRejectsNonEmptyPartitionAndChangesNothing() {
        node.insertAll(P1, List.of(new Entity3(1, new Point3(1, 1, 1))));
        assertThrows(IllegalStateException.class, () -> node.removePartition(P1));
        assertEquals(1, node.size(P1));
    }

    @Test
    void dropRemovesPartitionWithItsEntities() {
        node.insertAll(P1, List.of(new Entity3(1, new Point3(1, 1, 1))));
        node.dropPartition(P1);
        assertThrows(IllegalArgumentException.class, () -> node.size(P1));
    }

    @Test
    void removeAndDropRejectUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.removePartition(P2));
        assertThrows(IllegalArgumentException.class, () -> node.dropPartition(P2));
    }

    @Test
    void rejectsDuplicatePartition() {
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P1));
    }

    @Test
    void rejectsUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.insertAll(P2, List.of(entity(1, 0, 0, 10))));
    }
}
