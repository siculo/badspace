package badspace.service.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import badspace.common.geometry.Box3;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.geometry.Sphere3;
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
 * Runs the index contract on the grid of octrees, against the brute-force
 * model. The test coordinates are mostly between -10 and 10, sometimes up to
 * 1e9 or to the limits of the index, so the parameters give many splits and
 * merges with small leaves, and deep trees in large cells. Small integer
 * coordinates give many entities in the same position, which stay in one
 * leaf.
 * <p>
 * The other tests compare the index with the linear scan in special cases;
 * there the z coordinate is the same as y.
 */
class GridOctreeIndex3Test {

    private static final Comparator<Entity3> BY_ID = Comparator.comparingLong(Entity3::id);

    @Nested
    class SmallCells extends SpatialIndex3Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.gridQuadtree(0.25, 1);
        }
    }

    @Nested
    class MediumCells extends SpatialIndex3Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.gridQuadtree(4, 2);
        }
    }

    @Nested
    class LargeCells extends SpatialIndex3Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.gridQuadtree(0x1p20, 4);
        }
    }

    /** The largest cells and the default capacity: deep trees that reach the limits of precision. */
    @Nested
    class HugeCells extends SpatialIndex3Contract {

        @Override
        IndexConfig index() {
            return IndexConfig.gridQuadtree(0x1p30, 16);
        }
    }

    @Test
    void manyEntitiesInTheSamePositionStayInOneLeaf() {
        List<Entity3> entities = new ArrayList<>();
        LongStream.rangeClosed(1, 2000).forEach(id -> entities.add(entity(id, 3, 3)));
        entities.add(entity(5000, 3.5, 3));
        entities.add(entity(5001, 2.5, 3));
        Comparison c = new Comparison(1, 1, entities);
        c.check(new Point3(3, 3, 3), 5);
        c.check(new Point3(3.4, 3, 3), 3);
        c.check(new Box3(new Point3(3, 3, 3), new Point3(3, 3, 3)));
        c.check(new Sphere3(new Point3(3.5, 3, 3), 0.5));
        c.removeAll(LongStream.rangeClosed(1, 1990).toArray());
        c.check(new Point3(3.4, 3, 3), 3);
        c.check(new Sphere3(new Point3(3, 3, 3), 1));
    }

    @Test
    void entitiesInTheSamePositionDoNotMakeAChainOfNodes() {
        List<Entity3> entities = new ArrayList<>();
        // 0.0 and -0.0 are the same position for the index.
        LongStream.rangeClosed(1, 100).forEach(id -> entities.add(entity(id, id % 2 == 0 ? 0.0 : -0.0, 0)));
        LongStream.rangeClosed(101, 200).forEach(id -> entities.add(entity(id, 300, 300)));
        Comparison c = new Comparison(128, 16, entities);
        assertEquals(0, c.maxDepth());

        // In the cell [256, 384), 300 and 301 go in different children at
        // depth 7; the 99 entities still in 300 then stay in one leaf.
        c.update(entity(101, 301, 300));
        assertEquals(7, c.maxDepth());
        c.check(new Point3(300.6, 300, 300), 3);
        c.update(entity(101, 300, 300));
        assertEquals(7, c.maxDepth());
        c.check(new Point3(300.6, 300, 300), 3);
        c.check(new Box3(new Point3(-1, -1, -1), new Point3(0, 0, 0)));
    }

    /**
     * Short steps in a dense cluster move the entities between near leaves,
     * also across the cells and to the same position, so the move changes
     * only the nodes below the first node that contains both positions. A
     * commit in each round checks that the moves copy the nodes that an
     * older version can see.
     */
    @Test
    void shortStepsInADenseClusterKeepTheStructure() {
        Random random = new Random(1);
        List<Entity3> entities = new ArrayList<>();
        for (long id = 1; id <= 2000; id++) {
            entities.add(entity(id, 300 + random.nextGaussian() * 5, 300 + random.nextGaussian() * 5));
        }
        Comparison c = new Comparison(64, 4, entities);
        c.checkStructure();
        Map<Long, Entity3> current = new HashMap<>();
        entities.forEach(e -> current.put(e.id(), e));
        for (int round = 0; round < 100; round++) {
            Comparison.Versions committed = c.commit(round + 1);
            for (int i = 0; i < 50; i++) {
                long id = 1 + random.nextInt(entities.size());
                Entity3 e = current.get(id);
                double step = random.nextInt(10) == 0 ? 50 : 0.5;
                Entity3 moved = random.nextInt(10) == 0
                        ? entity(id, 300, 300)
                        : entity(id, e.position().x() + random.nextGaussian() * step,
                                e.position().y() + random.nextGaussian() * step);
                c.update(moved);
                current.put(id, moved);
            }
            c.checkStructure();
            if (round % 10 == 0) {
                c.check(new Point3(300, 300, 300), 20);
                c.check(new Point3(301.5, 299, 300), 5);
                c.check(new Box3(new Point3(295, 295, 295), new Point3(302, 304, 304)));
                // The moves of this round do not change the version of its commit.
                committed.check(new Point3(300, 300, 300), 20);
                committed.check(new Point3(301.5, 299, 300), 5);
                committed.check(new Box3(new Point3(295, 295, 295), new Point3(302, 304, 304)));
            }
        }
    }

    @Test
    void smallNegativeCoordinatesAreInTheRightCell() {
        double small = Double.MIN_VALUE;
        Comparison c = new Comparison(0x1p20, 1, List.of(
                entity(1, -small, -small), entity(2, small, small), entity(3, 0, 0), entity(4, -small, small)));
        c.check(new Box3(new Point3(-small, -small, -small), new Point3(-small, -small, -small)));
        c.check(new Box3(new Point3(-1, -1, -1), new Point3(-small, 1, 1)));
        c.check(new Box3(new Point3(0, 0, 0), new Point3(1, 1, 1)));
        c.check(new Point3(-1e-300, -1e-300, -1e-300), 2);
    }

    private static Entity3 entity(long id, double x, double y) {
        return new Entity3(id, new Point3(x, y, y));
    }

    /** The same entities in a storage with the grid of octrees and in one with the linear scan. */
    private static final class Comparison {

        final TestStorage3 tested;
        final TestStorage3 reference = new TestStorage3(IndexConfig.linearScan());
        GridOctreeIndex3 index;

        Comparison(double cellSize, int leafCapacity, List<Entity3> entities) {
            tested = new TestStorage3(slots -> index = new GridOctreeIndex3(slots, cellSize, leafCapacity));
            tested.insertAll(entities);
            reference.insertAll(entities);
        }

        void check(Point3 point, int count) {
            assertEquals(reference.findNearest(point, count), tested.findNearest(point, count),
                    "findNearest " + point + " " + count);
        }

        void check(Region3 region) {
            assertEquals(sortedById(reference.findInRegion(region)), sortedById(tested.findInRegion(region)),
                    "findInRegion " + region);
        }

        void update(Entity3 entity) {
            tested.updateAll(List.of(entity));
            reference.updateAll(List.of(entity));
        }

        void checkStructure() {
            index.checkStructure();
        }

        Versions commit(long n) {
            return new Versions(tested.commit(n), reference.commit(n));
        }

        int maxDepth() {
            return index.maxDepth();
        }

        void removeAll(long[] ids) {
            tested.removeAll(ids);
            reference.removeAll(ids);
        }

        /** The versions of the two storages at the same commit. */
        record Versions(TestStorage3.Version tested, TestStorage3.Version reference) {

            void check(Point3 point, int count) {
                assertEquals(reference.findNearest(point, count), tested.findNearest(point, count),
                        "version findNearest " + point + " " + count);
            }

            void check(Region3 region) {
                assertEquals(sortedById(reference.findInRegion(region)), sortedById(tested.findInRegion(region)),
                        "version findInRegion " + region);
            }
        }

        private static List<Entity3> sortedById(List<Entity3> entities) {
            return entities.stream().sorted(BY_ID).toList();
        }
    }
}
