package badspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import badspace.common.PartitionId;
import badspace.common.PartitionNode2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Space2Test {

    /** A node that only records the calls it receives. */
    private static final class RecordingNode implements PartitionNode2 {
        final Map<PartitionId, List<Long>> entities = new HashMap<>();

        @Override
        public void createPartition(PartitionId partition) {
            entities.put(partition, new ArrayList<>());
        }

        @Override
        public void insert(PartitionId partition, long entityId, double x, double y) {
            entities.get(partition).add(entityId);
        }

        @Override
        public int size(PartitionId partition) {
            return entities.get(partition).size();
        }
    }

    @Test
    void partitionsOnDifferentNodesGetDifferentIds() {
        Space2 space = new Space2();
        RecordingNode nodeA = new RecordingNode();
        RecordingNode nodeB = new RecordingNode();
        space.createPartition(nodeA);
        space.createPartition(nodeB);
        assertNotEquals(nodeA.entities.keySet(), nodeB.entities.keySet());
    }

    @Test
    void insertGoesToTheNodeOfThePartition() {
        Space2 space = new Space2();
        RecordingNode nodeA = new RecordingNode();
        RecordingNode nodeB = new RecordingNode();
        Partition2 pa = space.createPartition(nodeA);
        space.createPartition(nodeB);
        long id = pa.insert(1.0, 2.0);
        assertEquals(List.of(id), nodeA.entities.values().iterator().next());
        assertEquals(List.of(), nodeB.entities.values().iterator().next());
        assertEquals(1, pa.size());
    }

    @Test
    void entityIdsAreUniqueAcrossPartitions() {
        Space2 space = new Space2();
        Partition2 p1 = space.createPartition(new RecordingNode());
        Partition2 p2 = space.createPartition(new RecordingNode());
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(p1.insert(i, -i));
            ids.add(p2.insert(i, -i));
        }
        assertEquals(200, ids.size());
    }
}
