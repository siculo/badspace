package badspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.Box2;
import badspace.common.Circle2;
import badspace.common.Entity2;
import badspace.common.PartitionId;
import badspace.common.PartitionNode2;
import badspace.common.Point2;
import badspace.common.Region2;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Space2Test {

    /**
     * A simple node that keeps the entities of each partition in a map. It does not check the input,
     * except that a removed partition must be empty.
     */
    private static final class MapNode implements PartitionNode2 {
        final Map<PartitionId, Map<Long, Point2>> partitions = new HashMap<>();
        int calls;

        @Override
        public void createPartition(PartitionId partition) {
            partitions.put(partition, new HashMap<>());
        }

        @Override
        public void removePartition(PartitionId partition) {
            if (!partitions.get(partition).isEmpty()) {
                throw new IllegalStateException("Partition is not empty");
            }
            partitions.remove(partition);
        }

        @Override
        public void dropPartition(PartitionId partition) {
            partitions.remove(partition);
        }

        @Override
        public void insertAll(PartitionId partition, List<Entity2> entities) {
            calls++;
            entities.forEach(e -> partitions.get(partition).put(e.id(), e.position()));
        }

        @Override
        public List<Entity2> getAll(PartitionId partition, long[] entityIds) {
            calls++;
            List<Entity2> result = new ArrayList<>();
            for (long id : entityIds) {
                Point2 p = partitions.get(partition).get(id);
                if (p != null) {
                    result.add(new Entity2(id, p));
                }
            }
            return result;
        }

        @Override
        public void updateAll(PartitionId partition, List<Entity2> entities) {
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
        public List<Entity2> findInRegion(PartitionId partition, Region2 region) {
            calls++;
            return partitions.get(partition).entrySet().stream()
                    .filter(e -> region.contains(e.getValue()))
                    .map(e -> new Entity2(e.getKey(), e.getValue()))
                    .toList();
        }

        @Override
        public List<Entity2> findNearest(PartitionId partition, Point2 point, int count) {
            calls++;
            return partitions.get(partition).entrySet().stream()
                    .sorted(Comparator.comparingDouble((Map.Entry<Long, Point2> e) -> {
                        double dx = e.getValue().x() - point.x();
                        double dy = e.getValue().y() - point.y();
                        return dx * dx + dy * dy;
                    }).thenComparing(Map.Entry::getKey))
                    .limit(count)
                    .map(e -> new Entity2(e.getKey(), e.getValue()))
                    .toList();
        }

        @Override
        public int size(PartitionId partition) {
            return partitions.get(partition).size();
        }

        Map<Long, Point2> onlyPartition() {
            return partitions.values().iterator().next();
        }
    }

    // Short drift limit, so the constructor waits only a little
    private static final SnowflakeIdGenerator IDS = new SnowflakeIdGenerator(0, 10);

    private static Space2 newSpace() {
        return new Space2(IDS);
    }

    @Test
    void partitionsOnDifferentNodesGetDifferentIds() {
        Space2 space = newSpace();
        MapNode nodeA = new MapNode();
        MapNode nodeB = new MapNode();
        space.createPartition(nodeA);
        space.createPartition(nodeB);
        assertNotEquals(nodeA.partitions.keySet(), nodeB.partitions.keySet());
    }

    @Test
    void insertGoesToTheNodeOfThePartition() {
        Space2 space = newSpace();
        MapNode nodeA = new MapNode();
        MapNode nodeB = new MapNode();
        Partition2 pa = space.createPartition(nodeA);
        space.createPartition(nodeB);
        long id = pa.insert(1.0, 2.0);
        assertEquals(Map.of(id, new Point2(1.0, 2.0)), nodeA.onlyPartition());
        assertEquals(Map.of(), nodeB.onlyPartition());
        assertEquals(1, pa.size());
    }

    @Test
    void insertAllMakesOneCallAndReturnsIdsInOrder() {
        MapNode node = new MapNode();
        Partition2 p = newSpace().createPartition(node);
        List<Point2> positions = List.of(new Point2(1, 1), new Point2(2, 2), new Point2(3, 3));
        long[] ids = p.insertAll(positions);
        assertEquals(1, node.calls);
        for (int i = 0; i < ids.length; i++) {
            assertEquals(positions.get(i), node.onlyPartition().get(ids[i]));
        }
    }

    @Test
    void getUpdateAndRemoveUseTheEntityId() {
        Partition2 p = newSpace().createPartition(new MapNode());
        long[] ids = p.insertAll(List.of(new Point2(1, 1), new Point2(2, 2)));
        assertEquals(Optional.of(new Point2(1, 1)), p.get(ids[0]));

        p.update(ids[0], new Point2(5, 5));
        p.updateAll(List.of(new Entity2(ids[1], new Point2(6, 6))));
        assertEquals(List.of(new Entity2(ids[1], new Point2(6, 6)), new Entity2(ids[0], new Point2(5, 5))),
                p.getAll(new long[] {ids[1], ids[0]}));

        p.remove(ids[0]);
        assertEquals(Optional.empty(), p.get(ids[0]));
        p.removeAll(new long[] {ids[1]});
        assertEquals(0, p.size());
    }

    @Test
    void queriesGoToTheNodeOfThePartition() {
        MapNode node = new MapNode();
        Partition2 p = newSpace().createPartition(node);
        long[] ids = p.insertAll(List.of(new Point2(1, 1), new Point2(5, 5), new Point2(9, 9)));
        node.calls = 0;
        assertEquals(List.of(new Entity2(ids[1], new Point2(5, 5))),
                p.findInRegion(new Box2(new Point2(4, 4), new Point2(6, 6))));
        assertEquals(List.of(new Entity2(ids[2], new Point2(9, 9)), new Entity2(ids[1], new Point2(5, 5))),
                p.findNearest(new Point2(10, 10), 2));
        assertEquals(2, node.calls);
    }

    @Test
    void defaultRemovalPolicyRequiresEmptyPartition() {
        Partition2 p = newSpace().createPartition(new MapNode());
        assertEquals(RemovalPolicy.REQUIRE_EMPTY, p.removalPolicy());
    }

    @Test
    void removeTakesThePartitionOffItsNode() {
        Space2 space = newSpace();
        MapNode node = new MapNode();
        Partition2 empty = space.createPartition(node, RemovalPolicy.REQUIRE_EMPTY);
        Partition2 full = space.createPartition(node, RemovalPolicy.DISCARD_ENTITIES);
        full.insert(new Point2(1, 1));
        space.removePartition(empty);
        space.removePartition(full);
        assertEquals(Map.of(), node.partitions);
    }

    @Test
    void removeOfNonEmptyPartitionThatRequiresEmptyKeepsItUsable() {
        Space2 space = newSpace();
        Partition2 p = space.createPartition(new MapNode(), RemovalPolicy.REQUIRE_EMPTY);
        long id = p.insert(new Point2(1, 1));
        assertThrows(IllegalStateException.class, () -> space.removePartition(p));
        p.remove(id);
        space.removePartition(p);
    }

    @Test
    void removedPartitionCannotBeUsed() {
        Space2 space = newSpace();
        MapNode node = new MapNode();
        Partition2 p = space.createPartition(node);
        space.removePartition(p);
        node.calls = 0;
        assertThrows(IllegalStateException.class, () -> p.insert(new Point2(1, 1)));
        assertThrows(IllegalStateException.class, () -> p.get(1));
        assertThrows(IllegalStateException.class, () -> p.getAll(new long[] {1}));
        assertThrows(IllegalStateException.class, () -> p.update(1, new Point2(1, 1)));
        assertThrows(IllegalStateException.class, () -> p.remove(1));
        assertThrows(IllegalStateException.class, p::size);
        assertThrows(IllegalStateException.class, () -> p.findInRegion(new Circle2(new Point2(0, 0), 1)));
        assertThrows(IllegalStateException.class, () -> p.findNearest(new Point2(0, 0), 1));
        assertThrows(IllegalStateException.class, () -> space.removePartition(p));
        assertEquals(0, node.calls);
    }

    @Test
    void rejectsPartitionOfAnotherSpace() {
        MapNode node = new MapNode();
        Partition2 p = newSpace().createPartition(node);
        Space2 other = newSpace();
        assertThrows(IllegalArgumentException.class, () -> other.removePartition(p));
        assertEquals(1, node.partitions.size());
    }

    @Test
    void entityIdsAreUniqueAcrossPartitions() {
        Space2 space = newSpace();
        Partition2 p1 = space.createPartition(new MapNode());
        Partition2 p2 = space.createPartition(new MapNode());
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(p1.insert(i, -i));
            ids.add(p2.insert(i, -i));
        }
        long[] batch = p1.insertAll(List.of(new Point2(0, 0), new Point2(1, 1)));
        ids.add(batch[0]);
        ids.add(batch[1]);
        assertEquals(202, ids.size());
    }
}
