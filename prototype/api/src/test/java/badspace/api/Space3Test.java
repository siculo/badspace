package badspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box3;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.PartitionVersion3;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.geometry.Sphere3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Space3Test {

    /** A version of {@link MapNode}: only the commit and the size, the reads are not needed. */
    private record MapVersion(long commit, int size) implements PartitionVersion3 {

        @Override
        public List<Entity3> getAll(long[] entityIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Entity3> findInRegion(Region3 region) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Entity3> findNearest(Point3 point, int count) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A simple node that keeps the entities of each partition in a map. It does not check the input,
     * except that a removed partition must be empty.
     */
    private static final class MapNode implements PartitionNode3 {
        final Map<PartitionId, Map<Long, Point3>> partitions = new HashMap<>();
        final Map<PartitionId, IndexConfig> indices = new HashMap<>();
        final Map<PartitionId, Long> commits = new HashMap<>();
        final Map<PartitionId, MapVersion> versions = new HashMap<>();
        int calls;

        @Override
        public void createPartition(PartitionId partitionId, IndexConfig index) {
            partitions.put(partitionId, new HashMap<>());
            indices.put(partitionId, index);
        }

        @Override
        public void removePartition(PartitionId partitionId) {
            if (!partitions.get(partitionId).isEmpty()) {
                throw new IllegalStateException("Partition is not empty");
            }
            partitions.remove(partitionId);
        }

        @Override
        public void dropPartition(PartitionId partitionId) {
            partitions.remove(partitionId);
        }

        @Override
        public void insertAll(PartitionId partitionId, List<Entity3> entities) {
            calls++;
            entities.forEach(e -> partitions.get(partitionId).put(e.id(), e.position()));
        }

        @Override
        public List<Entity3> getAll(PartitionId partitionId, long[] entityIds) {
            calls++;
            List<Entity3> result = new ArrayList<>();
            for (long id : entityIds) {
                Point3 p = partitions.get(partitionId).get(id);
                if (p != null) {
                    result.add(new Entity3(id, p));
                }
            }
            return result;
        }

        @Override
        public void updateAll(PartitionId partitionId, List<Entity3> entities) {
            calls++;
            entities.forEach(e -> partitions.get(partitionId).put(e.id(), e.position()));
        }

        @Override
        public void removeAll(PartitionId partitionId, long[] entityIds) {
            calls++;
            for (long id : entityIds) {
                partitions.get(partitionId).remove(id);
            }
        }

        @Override
        public List<Entity3> findInRegion(PartitionId partitionId, Region3 region) {
            calls++;
            return partitions.get(partitionId).entrySet().stream()
                    .filter(e -> region.contains(e.getValue()))
                    .map(e -> new Entity3(e.getKey(), e.getValue()))
                    .toList();
        }

        @Override
        public List<Entity3> findNearest(PartitionId partitionId, Point3 point, int count) {
            calls++;
            return partitions.get(partitionId).entrySet().stream()
                    .sorted(Comparator.comparingDouble((Map.Entry<Long, Point3> e) -> {
                        double dx = e.getValue().x() - point.x();
                        double dy = e.getValue().y() - point.y();
                        double dz = e.getValue().z() - point.z();
                        return dx * dx + dy * dy + dz * dz;
                    }).thenComparing(Map.Entry::getKey))
                    .limit(count)
                    .map(e -> new Entity3(e.getKey(), e.getValue()))
                    .toList();
        }

        @Override
        public void commit(PartitionId partitionId, long commit) {
            calls++;
            commits.put(partitionId, commit);
            versions.put(partitionId, new MapVersion(commit, partitions.get(partitionId).size()));
        }

        @Override
        public PartitionVersion3 lastVersion(PartitionId partitionId) {
            calls++;
            return versions.computeIfAbsent(partitionId, p -> new MapVersion(0, 0));
        }

        @Override
        public long lastCommit(PartitionId partitionId) {
            calls++;
            return commits.getOrDefault(partitionId, 0L);
        }

        @Override
        public int size(PartitionId partitionId) {
            return partitions.get(partitionId).size();
        }

        @Override
        public CoordinateLimits limits(PartitionId partitionId) {
            calls++;
            return indices.get(partitionId).limits();
        }

        Map<Long, Point3> onlyPartition() {
            return partitions.values().iterator().next();
        }
    }

    // Short drift limit, so the constructor waits only a little
    private static final SnowflakeIdGenerator IDS = new SnowflakeIdGenerator(0, 10);

    private static Space3 newSpace() {
        return new Space3(IDS);
    }

    @Test
    void limitsComeFromTheIndexOfThePartition() {
        MapNode node = new MapNode();
        Partition3 p = newSpace().createPartition(node);
        assertEquals(IndexConfig.linearScan().limits(), p.limits());
        assertEquals(1, node.calls);
    }

    @Test
    void partitionsOnDifferentNodesGetDifferentIds() {
        Space3 space = newSpace();
        MapNode nodeA = new MapNode();
        MapNode nodeB = new MapNode();
        space.createPartition(nodeA);
        space.createPartition(nodeB);
        assertNotEquals(nodeA.partitions.keySet(), nodeB.partitions.keySet());
    }

    @Test
    void insertGoesToTheNodeOfThePartition() {
        Space3 space = newSpace();
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
        Partition3 p = newSpace().createPartition(node);
        List<Point3> positions = List.of(new Point3(1, 1, 1), new Point3(2, 2, 2), new Point3(3, 3, 3));
        long[] ids = p.insertAll(positions);
        assertEquals(1, node.calls);
        for (int i = 0; i < ids.length; i++) {
            assertEquals(positions.get(i), node.onlyPartition().get(ids[i]));
        }
    }

    @Test
    void getUpdateAndRemoveUseTheEntityId() {
        Partition3 p = newSpace().createPartition(new MapNode());
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
    void queriesGoToTheNodeOfThePartition() {
        MapNode node = new MapNode();
        Partition3 p = newSpace().createPartition(node);
        long[] ids = p.insertAll(List.of(new Point3(1, 1, 1), new Point3(5, 5, 5), new Point3(9, 9, 9)));
        node.calls = 0;
        assertEquals(List.of(new Entity3(ids[1], new Point3(5, 5, 5))),
                p.findInRegion(new Box3(new Point3(4, 4, 4), new Point3(6, 6, 6))));
        assertEquals(List.of(new Entity3(ids[2], new Point3(9, 9, 9)), new Entity3(ids[1], new Point3(5, 5, 5))),
                p.findNearest(new Point3(10, 10, 10), 2));
        assertEquals(2, node.calls);
    }

    @Test
    void defaultConfigUsesLinearScanAndRequiresEmptyPartition() {
        MapNode node = new MapNode();
        Partition3 p = newSpace().createPartition(node);
        assertEquals(new PartitionConfig(IndexConfig.linearScan(), RemovalPolicy.REQUIRE_EMPTY), p.config());
        assertEquals(IndexConfig.linearScan(), node.indices.values().iterator().next());
    }

    @Test
    void chosenIndexGoesToTheNode() {
        MapNode node = new MapNode();
        IndexConfig index = IndexConfig.uniformGrid(2);
        Partition3 p = newSpace().createPartition(node, PartitionConfig.defaults().withIndex(index));
        assertEquals(index, p.config().index());
        assertEquals(RemovalPolicy.REQUIRE_EMPTY, p.config().removalPolicy());
        assertEquals(index, node.indices.values().iterator().next());
        assertEquals(index.limits(), p.limits());
    }

    @Test
    void configRejectsNull() {
        assertThrows(NullPointerException.class, () -> new PartitionConfig(null, RemovalPolicy.REQUIRE_EMPTY));
        assertThrows(NullPointerException.class, () -> PartitionConfig.defaults().withRemovalPolicy(null));
        assertThrows(NullPointerException.class, () -> newSpace().createPartition(new MapNode(), null));
    }

    @Test
    void removeTakesThePartitionOffItsNode() {
        Space3 space = newSpace();
        MapNode node = new MapNode();
        Partition3 empty = space.createPartition(node, PartitionConfig.defaults().withRemovalPolicy(RemovalPolicy.REQUIRE_EMPTY));
        Partition3 full = space.createPartition(node, PartitionConfig.defaults().withRemovalPolicy(RemovalPolicy.DISCARD_ENTITIES));
        full.insert(new Point3(1, 1, 1));
        space.removePartition(empty);
        space.removePartition(full);
        assertEquals(Map.of(), node.partitions);
    }

    @Test
    void removeOfNonEmptyPartitionThatRequiresEmptyKeepsItUsable() {
        Space3 space = newSpace();
        Partition3 p = space.createPartition(new MapNode(), PartitionConfig.defaults().withRemovalPolicy(RemovalPolicy.REQUIRE_EMPTY));
        long id = p.insert(new Point3(1, 1, 1));
        assertThrows(IllegalStateException.class, () -> space.removePartition(p));
        p.remove(id);
        space.removePartition(p);
    }

    @Test
    void removedPartitionCannotBeUsed() {
        Space3 space = newSpace();
        MapNode node = new MapNode();
        Partition3 p = space.createPartition(node);
        space.removePartition(p);
        node.calls = 0;
        assertThrows(IllegalStateException.class, () -> p.insert(new Point3(1, 1, 1)));
        assertThrows(IllegalStateException.class, () -> p.get(1));
        assertThrows(IllegalStateException.class, () -> p.getAll(new long[] {1}));
        assertThrows(IllegalStateException.class, () -> p.update(1, new Point3(1, 1, 1)));
        assertThrows(IllegalStateException.class, () -> p.remove(1));
        assertThrows(IllegalStateException.class, p::size);
        assertThrows(IllegalStateException.class, () -> p.commit(1));
        assertThrows(IllegalStateException.class, p::lastCommit);
        assertThrows(IllegalStateException.class, p::lastVersion);
        assertThrows(IllegalStateException.class, () -> p.findInRegion(new Sphere3(new Point3(0, 0, 0), 1)));
        assertThrows(IllegalStateException.class, () -> p.findNearest(new Point3(0, 0, 0), 1));
        assertThrows(IllegalStateException.class, () -> space.removePartition(p));
        assertEquals(0, node.calls);
    }

    @Test
    void rejectsPartitionOfAnotherSpace() {
        MapNode node = new MapNode();
        Partition3 p = newSpace().createPartition(node);
        Space3 other = newSpace();
        assertThrows(IllegalArgumentException.class, () -> other.removePartition(p));
        assertEquals(1, node.partitions.size());
    }

    @Test
    void lastVersionComesFromTheNodeOfThePartition() {
        MapNode node = new MapNode();
        Partition3 p = newSpace().createPartition(node);
        assertEquals(0, p.lastVersion().commit());
        p.commit(3);
        assertSame(node.versions.values().iterator().next(), p.lastVersion());
        assertEquals(3, p.lastVersion().commit());
    }

    @Test
    void commitGoesToTheNodeOfThePartition() {
        MapNode node = new MapNode();
        Partition3 p = newSpace().createPartition(node);
        assertEquals(0, p.lastCommit());
        p.commit(3);
        assertEquals(3, p.lastCommit());
        assertEquals(List.of(3L), List.copyOf(node.commits.values()));
    }

    @Test
    void entityIdsAreUniqueAcrossPartitions() {
        Space3 space = newSpace();
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
