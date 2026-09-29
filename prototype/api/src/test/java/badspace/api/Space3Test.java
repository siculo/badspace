package badspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import badspace.common.PartitionId;
import badspace.common.PartitionNode3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Space3Test {

    /** A node that only records the calls it receives. */
    private static final class RecordingNode implements PartitionNode3 {
        final Map<PartitionId, List<Long>> entities = new HashMap<>();

        @Override
        public void createPartition(PartitionId partition) {
            entities.put(partition, new ArrayList<>());
        }

        @Override
        public void insert(PartitionId partition, long entityId, double x, double y, double z) {
            entities.get(partition).add(entityId);
        }

        @Override
        public int size(PartitionId partition) {
            return entities.get(partition).size();
        }
    }

    @Test
    void partitionsOnDifferentNodesGetDifferentIds() {
        Space3 space = new Space3();
        RecordingNode nodeA = new RecordingNode();
        RecordingNode nodeB = new RecordingNode();
        space.createPartition(nodeA);
        space.createPartition(nodeB);
        assertNotEquals(nodeA.entities.keySet(), nodeB.entities.keySet());
    }

    @Test
    void insertGoesToTheNodeOfThePartition() {
        Space3 space = new Space3();
        RecordingNode nodeA = new RecordingNode();
        RecordingNode nodeB = new RecordingNode();
        Partition3 pa = space.createPartition(nodeA);
        space.createPartition(nodeB);
        long id = pa.insert(1.0, 2.0, 3.0);
        assertEquals(List.of(id), nodeA.entities.values().iterator().next());
        assertEquals(List.of(), nodeB.entities.values().iterator().next());
        assertEquals(1, pa.size());
    }

    @Test
    void entityIdsAreUniqueAcrossPartitions() {
        Space3 space = new Space3();
        Partition3 p1 = space.createPartition(new RecordingNode());
        Partition3 p2 = space.createPartition(new RecordingNode());
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(p1.insert(i, -i, 2 * i));
            ids.add(p2.insert(i, -i, 2 * i));
        }
        assertEquals(200, ids.size());
    }
}
