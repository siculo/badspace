package badspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.PartitionId;
import org.junit.jupiter.api.Test;

class LocalPartitionNode3Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);

    @Test
    void insertAddsEntityToItsPartitionOnly() {
        LocalPartitionNode3 node = new LocalPartitionNode3();
        node.createPartition(P1);
        node.createPartition(P2);
        node.insert(P1, 42, 1.0, 2.0, 3.0);
        assertEquals(1, node.size(P1));
        assertEquals(0, node.size(P2));
    }

    @Test
    void growsBeyondInitialCapacity() {
        LocalPartitionNode3 node = new LocalPartitionNode3();
        node.createPartition(P1);
        for (int i = 0; i < 1000; i++) {
            node.insert(P1, i, i, -i, 2 * i);
        }
        assertEquals(1000, node.size(P1));
    }

    @Test
    void rejectsDuplicatePartition() {
        LocalPartitionNode3 node = new LocalPartitionNode3();
        node.createPartition(P1);
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P1));
    }

    @Test
    void rejectsUnknownPartition() {
        LocalPartitionNode3 node = new LocalPartitionNode3();
        assertThrows(IllegalArgumentException.class, () -> node.insert(P1, 1, 0, 0, 0));
    }
}
