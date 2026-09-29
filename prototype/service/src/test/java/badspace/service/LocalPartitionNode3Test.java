package badspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.Entity3;
import badspace.common.PartitionId;
import badspace.common.Point3;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
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
    void rejectsDuplicatePartition() {
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P1));
    }

    @Test
    void rejectsUnknownPartition() {
        assertThrows(IllegalArgumentException.class, () -> node.insertAll(P2, List.of(entity(1, 0, 0, 10))));
    }
}
