package badspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.Box2;
import badspace.common.Circle2;
import badspace.common.Entity2;
import badspace.common.IndexType;
import badspace.common.PartitionId;
import badspace.common.Point2;
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

    private LocalPartitionNode2 node;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode2();
        node.createPartition(P1);
    }

    private static Entity2 entity(long id, double x, double y) {
        return new Entity2(id, new Point2(x, y));
    }

    @Test
    void partitionUsesTheIndexChosenAtCreation() {
        node.createPartition(P2, IndexType.LINEAR_SCAN);
        node.insertAll(P2, List.of(entity(1, 1, 2), entity(2, 5, 5)));
        assertEquals(List.of(entity(1, 1, 2)), node.findNearest(P2, new Point2(0, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P2, IndexType.LINEAR_SCAN));
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
    void removedPartitionIsGoneAndItsIdCanBeUsedAgain() {
        node.removePartition(P1);
        assertThrows(IllegalArgumentException.class, () -> node.size(P1));
        node.createPartition(P1);
        assertEquals(0, node.size(P1));
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
    void rejectsDuplicatePartition() {
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P1));
    }

    @Test
    void rejectsUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.insertAll(P2, List.of(entity(1, 0, 0))));
    }
}
