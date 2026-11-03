package badspace.service.index;

import badspace.common.geometry.Box3;
import badspace.common.geometry.Sphere3;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.service.transaction.TransactionalComponentContract;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;

/**
 * Checks each 3D index as a transactional component, together with the slots of a
 * {@link TestStorage3}: the restore brings back the slots and then the index. The content is
 * the slots in their order and the results of some fixed queries, so a wrong auxiliary map
 * of an index shows up in the results of the writes after the restore.
 */
class SpatialIndex3TransactionsTest {

    private static final long MAX_ID = 60;
    private static final int MAX_COORDINATE = 30;
    private static final int NEAREST = 5;
    private static final List<Region3> REGIONS = List.of(
            new Box3(new Point3(-1, -1, -1), new Point3(MAX_COORDINATE + 1, MAX_COORDINATE + 1, MAX_COORDINATE + 1)),
            new Box3(new Point3(5, 5, 5), new Point3(15, 12, 14)),
            new Sphere3(new Point3(10, 10, 10), 6));
    private static final List<Point3> NEAREST_POINTS = List.of(
            new Point3(0, 0, 0), new Point3(MAX_COORDINATE, MAX_COORDINATE, MAX_COORDINATE), new Point3(7, 13, 3));
    private static final Comparator<Entity3> BY_ID = Comparator.comparingLong(Entity3::id);

    @Nested
    class LinearScan extends IndexTransactions {

        @Override
        IndexConfig index() {
            return IndexConfig.linearScan();
        }
    }

    @Nested
    class UniformGrid extends IndexTransactions {

        @Override
        IndexConfig index() {
            return IndexConfig.uniformGrid(4);
        }
    }

    @Nested
    class GridOctree extends IndexTransactions {

        @Override
        IndexConfig index() {
            // Small leaves, so the writes split and merge the nodes.
            return IndexConfig.gridQuadtree(8, 2);
        }

        @Override
        protected void checkStructure(TestStorage3 storage) {
            ((GridOctreeIndex3) storage.index()).checkStructure();
        }
    }

    /** The adapter of the contract for the index of the configuration. */
    abstract static class IndexTransactions extends TransactionalComponentContract<TestStorage3, TestStorage3.Snapshot> {

        abstract IndexConfig index();

        @Override
        protected TestStorage3 newComponent() {
            return new TestStorage3(index());
        }

        @Override
        protected TestStorage3.Snapshot emptySnapshot() {
            return TestStorage3.emptySnapshot();
        }

        @Override
        protected void write(TestStorage3 storage, Random random, long commit) {
            long entityId = random.nextLong(MAX_ID);
            Entity3 entity = new Entity3(entityId,
                    new Point3(random.nextInt(MAX_COORDINATE), random.nextInt(MAX_COORDINATE), random.nextInt(MAX_COORDINATE)));
            if (!contains(storage, entityId)) {
                storage.insertAll(List.of(entity));
            } else if (random.nextBoolean()) {
                storage.updateAll(List.of(entity));
            } else {
                storage.removeAll(new long[] {entityId});
            }
        }

        @Override
        protected Object workingContent(TestStorage3 storage) {
            return contentOf(storage, storage::findInRegion, storage::findNearest);
        }

        @Override
        protected Object snapshotContent(TestStorage3.Snapshot snapshot) {
            return contentOf(snapshot, snapshot::findInRegion, snapshot::findNearest);
        }
    }

    private static boolean contains(SlotView3 slots, long entityId) {
        for (int slot = 0; slot < slots.size(); slot++) {
            if (slots.idAt(slot) == entityId) {
                return true;
            }
        }
        return false;
    }

    /** Returns the slots in their order, then the results of the queries. */
    private static List<List<Entity3>> contentOf(SlotView3 slots, Function<Region3, List<Entity3>> findInRegion,
            BiFunction<Point3, Integer, List<Entity3>> findNearest) {
        List<List<Entity3>> content = new ArrayList<>();
        List<Entity3> entities = new ArrayList<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            entities.add(new Entity3(slots.idAt(slot), slots.positionAt(slot)));
        }
        content.add(entities);
        for (Region3 region : REGIONS) {
            List<Entity3> found = new ArrayList<>(findInRegion.apply(region));
            found.sort(BY_ID);
            content.add(found);
        }
        for (Point3 point : NEAREST_POINTS) {
            content.add(findNearest.apply(point, NEAREST));
        }
        return content;
    }
}
