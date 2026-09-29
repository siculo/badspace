package badspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import badspace.common.Entity3;
import badspace.common.PartitionId;
import badspace.common.PartitionNode3;
import badspace.common.Point3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Space3Test {

    /** A simple node that keeps the entities of each partition in a map. It does not check the input. */
    private static final class MapNode implements PartitionNode3 {
        final Map<PartitionId, Map<Long, Point3>> partitions = new HashMap<>();
        int calls;

        @Override
        public void createPartition(PartitionId partition) {
            partitions.put(partition, new HashMap<>());
        }

        @Override
        public void insertAll(PartitionId partition, List<Entity3> entities) {
            calls++;
            entities.forEach(e -> partitions.get(partition).put(e.id(), e.position()));
        }

        @Override
        public List<Entity3> getAll(PartitionId partition, long[] entityIds) {
            calls++;
            List<Entity3> result = new ArrayList<>();
            for (long id : entityIds) {
                Point3 p = partitions.get(partition).get(id);
                if (p != null) {
                    result.add(new Entity3(id, p));
                }
            }
            return result;
        }

        @Override
        public void updateAll(PartitionId partition, List<Entity3> entities) {
            calls++;
            entities.forEach(e -> partitions.get(partition).put(e.id(), e.position()));
        }

        @Override
        public void removeAll(PartitionId partition, long[] entityIds) {
            calls++;
            for (long id : entityIds) {
                partitions.get(partition).remove(id);
            }
        }

        @Override
        public int size(PartitionId partition) {
            return partitions.get(partition).size();
        }

        Map<Long, Point3> onlyPartition() {
            return partitions.values().iterator().next();
        }
    }

    @Test
    void partitionsOnDifferentNodesGetDifferentIds() {
        Space3 space = new Space3();
        MapNode nodeA = new MapNode();
        MapNode nodeB = new MapNode();
        space.createPartition(nodeA);
        space.createPartition(nodeB);
        assertNotEquals(nodeA.partitions.keySet(), nodeB.partitions.keySet());
    }

    @Test
    void insertGoesToTheNodeOfThePartition() {
        Space3 space = new Space3();
        MapNode nodeA = new MapNode();
        MapNode nodeB = new MapNode();
        Partition3 pa = space.createPartition(nodeA);
        space.createPartition(nodeB);
        long id = pa.insert(1.0, 2.0, 3.0);
        assertEquals(Map.of(id, new Point3(1.0, 2.0, 3.0)), nodeA.onlyPartition());
        assertEquals(Map.of(), nodeB.onlyPartition());
        assertEquals(1, pa.size());
    }

    @Test
    void insertAllMakesOneCallAndReturnsIdsInOrder() {
        MapNode node = new MapNode();
        Partition3 p = new Space3().createPartition(node);
        List<Point3> positions = List.of(new Point3(1, 1, 1), new Point3(2, 2, 2), new Point3(3, 3, 3));
        long[] ids = p.insertAll(positions);
        assertEquals(1, node.calls);
        for (int i = 0; i < ids.length; i++) {
            assertEquals(positions.get(i), node.onlyPartition().get(ids[i]));
        }
    }

    @Test
    void getUpdateAndRemoveUseTheEntityId() {
        Partition3 p = new Space3().createPartition(new MapNode());
        long[] ids = p.insertAll(List.of(new Point3(1, 1, 1), new Point3(2, 2, 2)));
        assertEquals(Optional.of(new Point3(1, 1, 1)), p.get(ids[0]));

        p.update(ids[0], new Point3(5, 5, 5));
        p.updateAll(List.of(new Entity3(ids[1], new Point3(6, 6, 6))));
        assertEquals(List.of(new Entity3(ids[1], new Point3(6, 6, 6)), new Entity3(ids[0], new Point3(5, 5, 5))),
                p.getAll(new long[] {ids[1], ids[0]}));

        p.remove(ids[0]);
        assertEquals(Optional.empty(), p.get(ids[0]));
        p.removeAll(new long[] {ids[1]});
        assertEquals(0, p.size());
    }

    @Test
    void entityIdsAreUniqueAcrossPartitions() {
        Space3 space = new Space3();
        Partition3 p1 = space.createPartition(new MapNode());
        Partition3 p2 = space.createPartition(new MapNode());
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(p1.insert(i, -i, 2 * i));
            ids.add(p2.insert(i, -i, 2 * i));
        }
        long[] batch = p1.insertAll(List.of(new Point3(0, 0, 0), new Point3(1, 1, 1)));
        ids.add(batch[0]);
        ids.add(batch[1]);
        assertEquals(202, ids.size());
    }
}
