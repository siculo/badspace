package badspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box3;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.RetentionWindow;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.geometry.Sphere3;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class Space3Test {

    /** A snapshot of {@link MapNode}: only the tick and the size, the reads are not needed. */
    private record MapSnapshot(long tick, int size) {
    }

    /**
     * A simple node that keeps the entities of each partition in a map. It does not check the input,
     * except that a removed partition must be empty. It keeps only the latest snapshot of each
     * partition.
     */
    private static final class MapNode implements PartitionNode3 {
        final Map<PartitionId, Map<Long, Point3>> partitions = new HashMap<>();
        final Map<PartitionId, IndexConfig> indices = new HashMap<>();
        final Map<PartitionId, Integer> retainedTicks = new HashMap<>();
        /** The number of commits of each partition. */
        final Map<PartitionId, Integer> commits = new HashMap<>();
        /** The size at the last commit of each partition in the current tick. */
        final Map<PartitionId, Integer> pending = new HashMap<>();
        /** The latest snapshot of each partition, of the tick before the current one. */
        final Map<PartitionId, MapSnapshot> snapshots = new HashMap<>();
        /** The acquisitions not yet released, by number. */
        final Map<Long, MapSnapshot> acquired = new HashMap<>();
        final Map<PartitionId, Long> creationTicks = new HashMap<>();
        /** The ticks of the calls to setTick, in order. */
        final List<Long> setTicks = new ArrayList<>();
        long tick;
        // The options of the last acquisition.
        AcquireOptions lastOptions;
        long lastAcquisitionId;
        int calls;

        @Override
        public void createPartition(PartitionId partitionId, IndexConfig index, int retainedTicks) {
            partitions.put(partitionId, new HashMap<>());
            indices.put(partitionId, index);
            this.retainedTicks.put(partitionId, retainedTicks);
            pending.put(partitionId, 0);
            creationTicks.put(partitionId, tick);
        }

        @Override
        public void setTick(long tick) {
            setTicks.add(tick);
            if (tick > this.tick) {
                for (PartitionId partitionId : partitions.keySet()) {
                    Integer size = pending.remove(partitionId);
                    int published = size != null ? size : snapshots.get(partitionId).size();
                    snapshots.put(partitionId, new MapSnapshot(tick - 1, published));
                }
            }
            this.tick = tick;
        }

        @Override
        public long tick() {
            return tick;
        }

        @Override
        public long tick(PartitionId partitionId) {
            calls++;
            return tick;
        }

        @Override
        public long creationTick(PartitionId partitionId) {
            calls++;
            return creationTicks.get(partitionId);
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
        public void commit(PartitionId partitionId) {
            calls++;
            commits.merge(partitionId, 1, Integer::sum);
            pending.put(partitionId, partitions.get(partitionId).size());
        }

        /** Acquires the snapshot of the tick: the map node keeps only the latest one. */
        @Override
        public Acquisition acquire(PartitionId partitionId, long tick, AcquireOptions options) {
            calls++;
            lastOptions = options;
            MapSnapshot snapshot = snapshots.get(partitionId);
            if (snapshot == null || snapshot.tick() != tick) {
                Reason reason = tick >= this.tick ? Reason.NOT_YET_PUBLISHED : Reason.OUT_OF_RETENTION;
                throw new SnapshotNotAvailableException(reason, "Only the latest snapshot");
            }
            acquired.put(++lastAcquisitionId, snapshot);
            return new Acquisition(partitionId, lastAcquisitionId, tick);
        }

        @Override
        public Acquisition acquireLatest(PartitionId partitionId, AcquireOptions options) {
            return acquire(partitionId, tick - 1, options);
        }

        @Override
        public void release(Acquisition acquisition) {
            calls++;
            acquired.remove(acquisition.id());
        }

        @Override
        public int size(Acquisition acquisition) {
            calls++;
            return admit(acquisition).size();
        }

        @Override
        public List<Entity3> getAll(Acquisition acquisition, long[] entityIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Entity3> findInRegion(Acquisition acquisition, Region3 region) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Entity3> findNearest(Acquisition acquisition, Point3 point, int count) {
            throw new UnsupportedOperationException();
        }

        private MapSnapshot admit(Acquisition acquisition) {
            MapSnapshot snapshot = acquired.get(acquisition.id());
            if (snapshot == null) {
                throw new IllegalStateException("Acquisition was released");
            }
            return snapshot;
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

    // Short drift limit, so the constructors wait only a little. The two generators make
    // different kinds of ID, so they can have the same generator ID.
    private static final SnowflakeIdGenerator IDS = new SnowflakeIdGenerator(0, 10);
    private static final SnowflakeIdGenerator PARTITION_IDS = new SnowflakeIdGenerator(0, 10);
    private static final SpaceConfig CONFIG = SpaceConfig.of(IDS, PARTITION_IDS);

    private static Space3 newSpace() {
        return new Space3(CONFIG);
    }

    @Test
    void partitionIdsComeFromTheirOwnGenerator() {
        AtomicLong lastPartitionId = new AtomicLong(100);
        Space3 space = new Space3(CONFIG.withPartitionIds(lastPartitionId::incrementAndGet));
        MapNode node = new MapNode();
        space.createPartition(node);
        space.createPartition(node);
        assertEquals(Set.of(new PartitionId(101), new PartitionId(102)), node.partitions.keySet());
    }

    @Test
    void spacesThatShareTheGeneratorsGiveDifferentPartitionIds() {
        MapNode node = new MapNode();
        new Space3(CONFIG).createPartition(node);
        new Space3(CONFIG).createPartition(node);
        assertEquals(2, node.partitions.size());
    }

    @Test
    void spaceConfigRejectsNull() {
        assertThrows(NullPointerException.class, () -> SpaceConfig.of(null, PARTITION_IDS));
        assertThrows(NullPointerException.class, () -> SpaceConfig.of(IDS, null));
        assertThrows(NullPointerException.class, () -> CONFIG.withEntityIds(null));
        assertThrows(NullPointerException.class, () -> CONFIG.withPartitionIds(null));
        assertThrows(NullPointerException.class, () -> new Space3(null));
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
        assertEquals(new PartitionConfig(IndexConfig.linearScan(), RemovalPolicy.REQUIRE_EMPTY,
                RetentionWindow.DEFAULT_RETAINED_TICKS), p.config());
        assertEquals(1, p.config().retainedTicks());
        assertEquals(IndexConfig.linearScan(), node.indices.values().iterator().next());
        assertEquals(RetentionWindow.DEFAULT_RETAINED_TICKS, node.retainedTicks.values().iterator().next());
    }

    @Test
    void chosenRetainedTicksGoToTheNode() {
        MapNode node = new MapNode();
        Partition3 p = newSpace().createPartition(node, PartitionConfig.defaults().withRetainedTicks(3));
        assertEquals(3, p.config().retainedTicks());
        assertEquals(IndexConfig.linearScan(), p.config().index());
        assertEquals(3, node.retainedTicks.values().iterator().next());
    }

    @Test
    void configRejectsRetainedTicksLessThanOne() {
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.defaults().withRetainedTicks(0));
        assertThrows(IllegalArgumentException.class, () -> PartitionConfig.defaults().withRetainedTicks(-1));
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
        assertThrows(NullPointerException.class, () -> new PartitionConfig(null, RemovalPolicy.REQUIRE_EMPTY, 1));
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
        assertThrows(IllegalStateException.class, p::commit);
        assertThrows(IllegalStateException.class, p::tick);
        assertThrows(IllegalStateException.class, p::creationTick);
        assertThrows(IllegalStateException.class, p::acquireLatest);
        assertThrows(IllegalStateException.class, () -> p.acquire(0));
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
    void acquiredSnapshotReadsThroughTheNode() {
        Space3 space = newSpace();
        MapNode node = new MapNode();
        Partition3 p = space.createPartition(node);
        p.insert(1, 1, 1);
        p.commit();
        space.setTick(1);
        AcquiredSnapshot3 snapshot = p.acquireLatest();
        assertEquals(0, snapshot.tick());
        assertEquals(1, snapshot.size());
        assertEquals(1, node.acquired.size());
    }

    @Test
    void closeReleasesTheAcquisitionAndCanBeCalledAgain() {
        Space3 space = newSpace();
        MapNode node = new MapNode();
        Partition3 p = space.createPartition(node);
        space.setTick(1);
        AcquiredSnapshot3 snapshot;
        try (AcquiredSnapshot3 v = p.acquireLatest()) {
            snapshot = v;
            assertEquals(0, snapshot.size());
        }
        assertEquals(0, node.acquired.size());
        snapshot.close();
        assertThrows(IllegalStateException.class, snapshot::size);
        assertEquals(0, snapshot.tick());
    }

    @Test
    void selectionsGoToTheNodeOfThePartition() {
        Space3 space = newSpace();
        MapNode node = new MapNode();
        Partition3 p = space.createPartition(node);
        p.commit();
        space.setTick(4);
        assertEquals(3, p.acquire(3).tick());
        assertEquals(3, p.acquireLatest().tick());
        SnapshotNotAvailableException old = assertThrows(SnapshotNotAvailableException.class, () -> p.acquire(2));
        assertEquals(Reason.OUT_OF_RETENTION, old.reason());
        SnapshotNotAvailableException future = assertThrows(SnapshotNotAvailableException.class, () -> p.acquire(4));
        assertEquals(Reason.NOT_YET_PUBLISHED, future.reason());
        assertEquals(2, node.acquired.size());
    }

    @Test
    void acquisitionsWithoutOptionsHaveTheDefaultTimeout() {
        Space3 space = newSpace();
        MapNode node = new MapNode();
        Partition3 p = space.createPartition(node);
        space.setTick(1);
        p.acquireLatest();
        assertEquals(AcquireOptions.defaultTimeout(), node.lastOptions);
        node.lastOptions = null;
        p.acquire(0);
        assertEquals(AcquireOptions.defaultTimeout(), node.lastOptions);
    }

    @Test
    void acquisitionOptionsGoToTheNode() {
        Space3 space = newSpace();
        MapNode node = new MapNode();
        Partition3 p = space.createPartition(node);
        space.setTick(1);
        AcquireOptions options = AcquireOptions.timeout(Duration.ofSeconds(5));
        p.acquireLatest(options);
        assertEquals(options, node.lastOptions);
        AcquireOptions other = AcquireOptions.timeout(Duration.ofSeconds(6));
        p.acquire(0, other);
        assertEquals(other, node.lastOptions);
    }

    @Test
    void commitGoesToTheNodeOfThePartition() {
        MapNode node = new MapNode();
        Partition3 p = newSpace().createPartition(node);
        p.commit();
        p.commit();
        assertEquals(List.of(2), List.copyOf(node.commits.values()));
    }

    @Test
    void newSpaceIsAtTickZero() {
        Space3 space = newSpace();
        assertEquals(0, space.tick());
        Partition3 p = space.createPartition(new MapNode());
        assertEquals(0, p.creationTick());
        assertEquals(0, p.tick());
    }

    @Test
    void setTickGoesOnceToEachNodeOfTheSpace() {
        Space3 space = newSpace();
        MapNode a = new MapNode();
        MapNode b = new MapNode();
        space.createPartition(a);
        space.createPartition(a);
        space.createPartition(b);
        space.setTick(3);
        space.setTick(8);
        assertEquals(8, space.tick());
        assertEquals(List.of(0L, 3L, 8L), a.setTicks);
        assertEquals(List.of(0L, 3L, 8L), b.setTicks);
    }

    @Test
    void newNodeGetsTheTickOfTheSpaceBeforeTheCreation() {
        Space3 space = newSpace();
        MapNode old = new MapNode();
        space.createPartition(old);
        space.setTick(5);
        MapNode node = new MapNode();
        Partition3 p = space.createPartition(node);
        assertEquals(List.of(5L), node.setTicks);
        assertEquals(5, p.creationTick());
        assertEquals(5, p.tick());
        assertEquals(List.of(0L, 5L), old.setTicks);
    }

    @Test
    void setTickWithTheSameTickKeepsTheTick() {
        Space3 space = newSpace();
        space.setTick(4);
        space.setTick(4);
        assertEquals(4, space.tick());
    }

    @Test
    void setTickRejectsAnEarlierTickAndChangesNothing() {
        Space3 space = newSpace();
        MapNode node = new MapNode();
        space.createPartition(node);
        space.setTick(3);
        assertThrows(IllegalArgumentException.class, () -> space.setTick(2));
        assertEquals(3, space.tick());
        assertEquals(List.of(0L, 3L), node.setTicks);
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
