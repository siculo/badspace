package badspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import badspace.common.Box2;
import badspace.common.Circle2;
import badspace.common.Entity2;
import badspace.common.IndexConfig;
import badspace.common.Point2;
import badspace.common.Region2;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Runs the index contract on the grid of quadtrees, against the brute-force
 * model. The test coordinates are mostly between -10 and 10, sometimes up to
 * 1e9 or to the limits of the index, so the parameters give many splits and
 * merges with small leaves, and deep trees in large cells. Small integer
 * coordinates give many entities in the same position, which stay in one
 * leaf.
 * <p>
 * The other tests compare the index with the linear scan in special cases.
 */
class GridQuadtreeIndex2Test {

    private static final Comparator<Entity2> BY_ID = Comparator.comparingLong(Entity2::id);

    @Nested
    class SmallCells extends SpatialIndex2Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.gridQuadtree(0.25, 1);
        }
    }

    @Nested
    class MediumCells extends SpatialIndex2Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.gridQuadtree(4, 2);
        }
    }

    @Nested
    class LargeCells extends SpatialIndex2Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.gridQuadtree(0x1p20, 4);
        }
    }

    /** The largest cells and the default capacity: deep trees that reach the limits of precision. */
    @Nested
    class HugeCells extends SpatialIndex2Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.gridQuadtree(0x1p30, 16);
        }
    }

    @Test
    void manyEntitiesInTheSamePositionStayInOneLeaf() {
        List<Entity2> entities = new ArrayList<>();
        LongStream.rangeClosed(1, 2000).forEach(id -> entities.add(entity(id, 3, 3)));
        entities.add(entity(5000, 3.5, 3));
        entities.add(entity(5001, 2.5, 3));
        Comparison c = new Comparison(1, 1, entities);
        c.check(new Point2(3, 3), 5);
        c.check(new Point2(3.4, 3), 3);
        c.check(new Box2(new Point2(3, 3), new Point2(3, 3)));
        c.check(new Circle2(new Point2(3.5, 3), 0.5));
        c.removeAll(LongStream.rangeClosed(1, 1990).toArray());
        c.check(new Point2(3.4, 3), 3);
        c.check(new Circle2(new Point2(3, 3), 1));
    }

    @Test
    void entitiesInTheSamePositionDoNotMakeAChainOfNodes() {
        List<Entity2> entities = new ArrayList<>();
        // 0.0 and -0.0 are the same position for the index.
        LongStream.rangeClosed(1, 100).forEach(id -> entities.add(entity(id, id % 2 == 0 ? 0.0 : -0.0, 0)));
        LongStream.rangeClosed(101, 200).forEach(id -> entities.add(entity(id, 300, 300)));
        Comparison c = new Comparison(128, 16, entities);
        assertEquals(0, c.maxDepth());

        // In the cell [256, 384), 300 and 301 go in different children at
        // depth 7; the 99 entities still in 300 then stay in one leaf.
        c.update(entity(101, 301, 300));
        assertEquals(7, c.maxDepth());
        c.check(new Point2(300.6, 300), 3);
        c.update(entity(101, 300, 300));
        assertEquals(7, c.maxDepth());
        c.check(new Point2(300.6, 300), 3);
        c.check(new Box2(new Point2(-1, -1), new Point2(0, 0)));
    }

    /**
     * Short steps in a dense cluster move the entities between near leaves,
     * also across the cells and to the same position, so the move goes up
     * only to the first node that contains the new position.
     */
    @Test
    void shortStepsInADenseClusterKeepTheStructure() {
        Random random = new Random(1);
        List<Entity2> entities = new ArrayList<>();
        for (long id = 1; id <= 2000; id++) {
            entities.add(entity(id, 300 + random.nextGaussian() * 5, 300 + random.nextGaussian() * 5));
        }
        Comparison c = new Comparison(64, 4, entities);
        c.checkStructure();
        Map<Long, Entity2> current = new HashMap<>();
        entities.forEach(e -> current.put(e.id(), e));
        for (int round = 0; round < 100; round++) {
            for (int i = 0; i < 50; i++) {
                long id = 1 + random.nextInt(entities.size());
                Entity2 e = current.get(id);
                double step = random.nextInt(10) == 0 ? 50 : 0.5;
                Entity2 moved = random.nextInt(10) == 0
                        ? entity(id, 300, 300)
                        : entity(id, e.position().x() + random.nextGaussian() * step,
                                e.position().y() + random.nextGaussian() * step);
                c.update(moved);
                current.put(id, moved);
            }
            c.checkStructure();
            if (round % 10 == 0) {
                c.check(new Point2(300, 300), 20);
                c.check(new Point2(301.5, 299), 5);
                c.check(new Box2(new Point2(295, 295), new Point2(302, 304)));
            }
        }
    }

    @Test
    void smallNegativeCoordinatesAreInTheRightCell() {
        double small = Double.MIN_VALUE;
        Comparison c = new Comparison(0x1p20, 1, List.of(
                entity(1, -small, -small), entity(2, small, small), entity(3, 0, 0), entity(4, -small, small)));
        c.check(new Box2(new Point2(-small, -small), new Point2(-small, -small)));
        c.check(new Box2(new Point2(-1, -1), new Point2(-small, 1)));
        c.check(new Box2(new Point2(0, 0), new Point2(1, 1)));
        c.check(new Point2(-1e-300, -1e-300), 2);
    }

    private static Entity2 entity(long id, double x, double y) {
        return new Entity2(id, new Point2(x, y));
    }

    /** The same entities in a storage with the grid of quadtrees and in one with the linear scan. */
    private static final class Comparison {

        final PartitionStorage2 tested;
        final PartitionStorage2 reference = new PartitionStorage2();
        GridQuadtreeIndex2 index;

        Comparison(double cellSize, int leafCapacity, List<Entity2> entities) {
            tested = new PartitionStorage2(IndexConfig.gridQuadtree(cellSize, leafCapacity).limits(),
                    storage -> index = new GridQuadtreeIndex2(storage, cellSize, leafCapacity));
            tested.insertAll(entities);
            reference.insertAll(entities);
        }

        void check(Point2 point, int count) {
            assertEquals(reference.findNearest(point, count), tested.findNearest(point, count),
                    "findNearest " + point + " " + count);
        }

        void check(Region2 region) {
            assertEquals(sortedById(reference.findInRegion(region)), sortedById(tested.findInRegion(region)),
                    "findInRegion " + region);
        }

        void update(Entity2 entity) {
            tested.updateAll(List.of(entity));
            reference.updateAll(List.of(entity));
        }

        void checkStructure() {
            index.checkStructure();
        }

        int maxDepth() {
            return index.maxDepth();
        }

        void removeAll(long[] ids) {
            tested.removeAll(ids);
            reference.removeAll(ids);
        }

        private static List<Entity2> sortedById(List<Entity2> entities) {
            return entities.stream().sorted(BY_ID).toList();
        }
    }
}
