package badspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.PartitionId;
import org.junit.jupiter.api.Test;

class LocalPartitionNode2Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);

    @Test
    void insertAddsEntityToItsPartitionOnly() {
        LocalPartitionNode2 node = new LocalPartitionNode2();
        node.createPartition(P1);
        node.createPartition(P2);
        node.insert(P1, 42, 1.0, 2.0);
        assertEquals(1, node.size(P1));
        assertEquals(0, node.size(P2));
    }

    @Test
    void growsBeyondInitialCapacity() {
        LocalPartitionNode2 node = new LocalPartitionNode2();
        node.createPartition(P1);
        for (int i = 0; i < 1000; i++) {
            node.insert(P1, i, i, -i);
        }
        assertEquals(1000, node.size(P1));
    }

    @Test
    void rejectsDuplicatePartition() {
        LocalPartitionNode2 node = new LocalPartitionNode2();
        node.createPartition(P1);
        assertThrows(IllegalArgumentException.class, () -> node.createPartition(P1));
    }

    @Test
    void rejectsUnknownPartition() {
        LocalPartitionNode2 node = new LocalPartitionNode2();
        assertThrows(IllegalArgumentException.class, () -> node.insert(P1, 1, 0, 0));
    }
}
