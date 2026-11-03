package badspace.service.index;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Circle2;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.Entity2;
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
 * Checks each 2D index as a transactional component, together with the slots of a
 * {@link TestStorage2}: the restore brings back the slots and then the index. The content is
 * the slots in their order and the results of some fixed queries, so a wrong auxiliary map
 * of an index shows up in the results of the writes after the restore.
 */
class SpatialIndex2TransactionsTest {

    private static final long MAX_ID = 60;
    private static final int MAX_COORDINATE = 20;
    private static final int NEAREST = 5;
    private static final List<Region2> REGIONS = List.of(
            new Box2(new Point2(-1, -1), new Point2(MAX_COORDINATE + 1, MAX_COORDINATE + 1)),
            new Box2(new Point2(5, 5), new Point2(15, 12)),
            new Circle2(new Point2(10, 10), 6));
    private static final List<Point2> NEAREST_POINTS = List.of(
            new Point2(0, 0), new Point2(MAX_COORDINATE, MAX_COORDINATE), new Point2(7, 13));
    private static final Comparator<Entity2> BY_ID = Comparator.comparingLong(Entity2::id);

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
    class GridQuadtree extends IndexTransactions {

        @Override
        IndexConfig index() {
            // Small leaves, so the writes split and merge the nodes.
            return IndexConfig.gridQuadtree(8, 2);
        }

        @Override
        protected void checkStructure(TestStorage2 storage) {
            ((GridQuadtreeIndex2) storage.index()).checkStructure();
        }
    }

    /** The adapter of the contract for the index of the configuration. */
    abstract static class IndexTransactions extends TransactionalComponentContract<TestStorage2, TestStorage2.Snapshot> {

        abstract IndexConfig index();

        @Override
        protected TestStorage2 newComponent() {
            return new TestStorage2(index());
        }

        @Override
        protected TestStorage2.Snapshot emptySnapshot() {
            return TestStorage2.emptySnapshot();
        }

        @Override
        protected void write(TestStorage2 storage, Random random, long commit) {
            long entityId = random.nextLong(MAX_ID);
            Entity2 entity = new Entity2(entityId,
                    new Point2(random.nextInt(MAX_COORDINATE), random.nextInt(MAX_COORDINATE)));
            if (!contains(storage, entityId)) {
                storage.insertAll(List.of(entity));
            } else if (random.nextBoolean()) {
                storage.updateAll(List.of(entity));
            } else {
                storage.removeAll(new long[] {entityId});
            }
        }

        @Override
        protected Object workingContent(TestStorage2 storage) {
            return contentOf(storage, storage::findInRegion, storage::findNearest);
        }

        @Override
        protected Object snapshotContent(TestStorage2.Snapshot snapshot) {
            return contentOf(snapshot, snapshot::findInRegion, snapshot::findNearest);
        }
    }

    private static boolean contains(SlotView2 slots, long entityId) {
        for (int slot = 0; slot < slots.size(); slot++) {
            if (slots.idAt(slot) == entityId) {
                return true;
            }
        }
        return false;
    }

    /** Returns the slots in their order, then the results of the queries. */
    private static List<List<Entity2>> contentOf(SlotView2 slots, Function<Region2, List<Entity2>> findInRegion,
            BiFunction<Point2, Integer, List<Entity2>> findNearest) {
        List<List<Entity2>> content = new ArrayList<>();
        List<Entity2> entities = new ArrayList<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            entities.add(new Entity2(slots.idAt(slot), slots.positionAt(slot)));
        }
        content.add(entities);
        for (Region2 region : REGIONS) {
            List<Entity2> found = new ArrayList<>(findInRegion.apply(region));
            found.sort(BY_ID);
            content.add(found);
        }
        for (Point2 point : NEAREST_POINTS) {
            content.add(findNearest.apply(point, NEAREST));
        }
        return content;
    }
}
