package badspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.Box2;
import badspace.common.Circle2;
import badspace.common.CoordinateLimits;
import badspace.common.Entity2;
import badspace.common.IndexConfig;
import badspace.common.Point2;
import badspace.common.Region2;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Correctness tests that each 2D index must pass. A subclass gives the index
 * to test.
 * <p>
 * The random test runs many writes on a storage with that index, and after
 * each write it compares some query results with a simple model: a map from
 * ID to position, searched by brute force. The linear scan passes the same
 * tests, so all the indices give the same results as the linear scan.
 * Each run uses a fixed seed, so a failure can be repeated: the message gives
 * the seed and the step.
 * <p>
 * Coordinates are often small integers, so there are many entities on the
 * border of the regions, in the same position or at the same distance.
 * The positions of the entities stay in the limits of the index.
 * <p>
 * Each test fails after 10 seconds, in a separate thread, so that an index
 * that loops too long fails the test instead of blocking the build. A test
 * takes about 30 ms.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class SpatialIndex2Contract {

    private static final int STEPS = 200;
    private static final int QUERIES_PER_STEP = 5;
    private static final int MAX_BATCH = 20;
    private static final long MAX_ID = 300;

    private static final Comparator<Entity2> BY_ID = Comparator.comparingLong(Entity2::id);

    /** Returns the index to test. */
    abstract IndexConfig index();

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 20);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void randomWritesAndQueriesGiveTheSameResultsAsTheModel(long seed) {
        Random random = new Random(seed);
        PartitionStorage2 storage = newStorage();
        Map<Long, Point2> model = new HashMap<>();
        for (int step = 0; step < STEPS; step++) {
            String write = randomWrite(random, storage, model);
            String where = "seed " + seed + ", step " + step + " (" + write + ")";
            assertEquals(model.size(), storage.size(), where + ": size");
            for (int i = 0; i < QUERIES_PER_STEP; i++) {
                Region2 region = randomRegion(random);
                assertEquals(expectedInRegion(model, region), sortedById(storage.findInRegion(region)),
                        where + ": findInRegion " + region);
                Point2 point = randomPoint(random);
                int count = 1 + random.nextInt(model.size() + 2);
                assertEquals(expectedNearest(model, point, count), storage.findNearest(point, count),
                        where + ": findNearest " + point + " " + count);
            }
        }
    }

    @Test
    void boxContainsItsBorderOnly() {
        PartitionStorage2 storage = storageWith(
                entity(1, 0, 0), entity(2, 4, 2), entity(3, 2, 0), entity(4, 0, 2),
                entity(5, Math.nextDown(0.0), 1), entity(6, Math.nextUp(4.0), 1),
                entity(7, 1, Math.nextDown(0.0)), entity(8, 1, Math.nextUp(2.0)));
        Box2 box = new Box2(new Point2(0, 0), new Point2(4, 2));
        assertEquals(List.of(entity(1, 0, 0), entity(2, 4, 2), entity(3, 2, 0), entity(4, 0, 2)),
                sortedById(storage.findInRegion(box)));
    }

    @Test
    void circleContainsItsBorderOnly() {
        PartitionStorage2 storage = storageWith(
                entity(1, 3, 4), entity(2, -5, 0), entity(3, 0, -5),
                entity(4, 3, Math.nextUp(4.0)), entity(5, Math.nextDown(-5.0), 0));
        Circle2 circle = new Circle2(new Point2(0, 0), 5);
        assertEquals(List.of(entity(1, 3, 4), entity(2, -5, 0), entity(3, 0, -5)),
                sortedById(storage.findInRegion(circle)));
    }

    @Test
    void regionsWithoutSizeContainOnlyTheirPoint() {
        PartitionStorage2 storage = storageWith(
                entity(1, 1, 1), entity(2, 1, 1), entity(3, 1, Math.nextUp(1.0)));
        Point2 p = new Point2(1, 1);
        List<Entity2> expected = List.of(entity(1, 1, 1), entity(2, 1, 1));
        assertEquals(expected, sortedById(storage.findInRegion(new Box2(p, p))));
        assertEquals(expected, sortedById(storage.findInRegion(new Circle2(p, 0))));
    }

    @Test
    void findNearestBreaksTiesById() {
        PartitionStorage2 storage = storageWith(
                entity(30, 1, 0), entity(10, 0, 1), entity(20, -1, 0), entity(40, 5, 5), entity(5, 0, 0));
        assertEquals(List.of(entity(5, 0, 0), entity(10, 0, 1), entity(20, -1, 0)),
                storage.findNearest(new Point2(0, 0), 3));
    }

    @Test
    void writesOutsideTheLimitsFailAndChangeNothing() {
        CoordinateLimits limits = index().limits();
        PartitionStorage2 storage = storageWith(entity(1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(entity(2, 0, 0), entity(3, Math.nextUp(limits.max()), 0))));
        assertThrows(IllegalArgumentException.class, () -> storage.insertAll(List.of(entity(4, -Double.MAX_VALUE, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateAll(List.of(entity(1, 0, Math.nextDown(limits.min())))));
        assertEquals(1, storage.size());
        assertEquals(List.of(entity(1, 0, 0)), storage.findNearest(new Point2(0, 0), 2));
    }

    @Test
    void entitiesOnTheLimitsAreFoundAlsoByQueriesOutsideThem() {
        CoordinateLimits limits = index().limits();
        double min = limits.min();
        double max = limits.max();
        double far = Double.MAX_VALUE;
        List<Entity2> entities = List.of(
                entity(1, min, min), entity(2, max, max), entity(3, min, max), entity(4, 0, 0));
        Map<Long, Point2> model = new HashMap<>();
        entities.forEach(e -> model.put(e.id(), e.position()));
        PartitionStorage2 storage = storageWith(entities.toArray(Entity2[]::new));
        for (Region2 region : List.of(
                new Box2(new Point2(min, min), new Point2(max, max)),
                new Box2(new Point2(max, max), new Point2(max, max)),
                new Circle2(new Point2(2 * max, 0), 2 * max),
                new Circle2(new Point2(far, far), far))) {
            assertEquals(expectedInRegion(model, region), sortedById(storage.findInRegion(region)),
                    "findInRegion " + region);
        }
        for (Point2 point : List.of(new Point2(max, max), new Point2(2 * max, 0), new Point2(far, far))) {
            assertEquals(expectedNearest(model, point, 2), storage.findNearest(point, 2), "findNearest " + point);
        }
    }

    @Test
    void queriesOfEmptyStorageAreEmpty() {
        PartitionStorage2 storage = storageWith();
        assertEquals(List.of(), storage.findInRegion(new Circle2(new Point2(0, 0), 1e9)));
        assertEquals(List.of(), storage.findNearest(new Point2(0, 0), 3));
    }

    private PartitionStorage2 newStorage() {
        return new PartitionStorage2(index());
    }

    private PartitionStorage2 storageWith(Entity2... entities) {
        PartitionStorage2 storage = newStorage();
        storage.insertAll(List.of(entities));
        return storage;
    }

    private static Entity2 entity(long id, double x, double y) {
        return new Entity2(id, new Point2(x, y));
    }

    private static List<Entity2> sortedById(List<Entity2> entities) {
        return entities.stream().sorted(BY_ID).toList();
    }

    private static List<Entity2> entitiesOf(Map<Long, Point2> model) {
        List<Entity2> entities = new ArrayList<>();
        model.forEach((id, p) -> entities.add(new Entity2(id, p)));
        return entities;
    }

    /** The entities of the model inside the region, sorted by ID. */
    private static List<Entity2> expectedInRegion(Map<Long, Point2> model, Region2 region) {
        return entitiesOf(model).stream().filter(e -> region.contains(e.position())).sorted(BY_ID).toList();
    }

    /** The count entities of the model nearest to the point, by distance and then by ID. */
    private static List<Entity2> expectedNearest(Map<Long, Point2> model, Point2 point, int count) {
        return entitiesOf(model).stream()
                .sorted(Comparator.comparingDouble((Entity2 e) -> e.position().distanceSquared(point))
                        .thenComparingLong(Entity2::id))
                .limit(count).toList();
    }

    /**
     * Applies a random write to the storage and to the model, and returns a
     * description of it. An empty storage always gets an insert, a full one
     * (all the IDs in use) never does.
     */
    private String randomWrite(Random random, PartitionStorage2 storage, Map<Long, Point2> model) {
        int choice = model.isEmpty() ? 0 : random.nextInt(20);
        if (choice < 8 && model.size() < MAX_ID) {
            List<Entity2> entities = newEntities(random, model);
            storage.insertAll(entities);
            entities.forEach(e -> model.put(e.id(), e.position()));
            return "insert " + entities.size();
        }
        if (choice < 12) {
            List<Entity2> entities = new ArrayList<>();
            for (long id : someIds(random, model)) {
                entities.add(new Entity2(id, randomPoint(random)));
            }
            storage.updateAll(entities);
            entities.forEach(e -> model.put(e.id(), e.position()));
            return "teleport " + entities.size();
        }
        if (choice < 16) {
            List<Entity2> entities = new ArrayList<>();
            for (long id : someIds(random, model)) {
                Point2 from = model.get(id);
                Point2 to = new Point2(randomStep(random, from.x()), randomStep(random, from.y()));
                entities.add(new Entity2(id, to));
            }
            storage.updateAll(entities);
            entities.forEach(e -> model.put(e.id(), e.position()));
            return "step " + entities.size();
        }
        List<Long> ids = choice < 19 ? someIds(random, model) : new ArrayList<>(model.keySet());
        storage.removeAll(ids.stream().mapToLong(Long::longValue).toArray());
        ids.forEach(model::remove);
        return "remove " + ids.size();
    }

    /** New entities with IDs that are not in the model. IDs can be used again after a removal. */
    private List<Entity2> newEntities(Random random, Map<Long, Point2> model) {
        List<Long> free = new ArrayList<>();
        for (long id = 1; id <= MAX_ID; id++) {
            if (!model.containsKey(id)) {
                free.add(id);
            }
        }
        Collections.shuffle(free, random);
        int count = 1 + random.nextInt(Math.min(MAX_BATCH, free.size()));
        List<Entity2> entities = new ArrayList<>();
        for (long id : free.subList(0, count)) {
            entities.add(new Entity2(id, randomPoint(random)));
        }
        return entities;
    }

    /** Some IDs of the model, at least one, in random order. */
    private static List<Long> someIds(Random random, Map<Long, Point2> model) {
        List<Long> ids = new ArrayList<>(model.keySet());
        Collections.sort(ids);
        Collections.shuffle(ids, random);
        int count = 1 + random.nextInt(Math.min(MAX_BATCH, ids.size()));
        return new ArrayList<>(ids.subList(0, count));
    }

    /**
     * Mostly a small integer, sometimes any value near it or far from it, up to
     * 1e9 or to the limits of the index.
     */
    private double randomCoordinate(Random random) {
        int choice = random.nextInt(9);
        if (choice < 6) {
            return random.nextInt(-10, 11);
        }
        if (choice < 8) {
            return random.nextDouble(-10, 10);
        }
        CoordinateLimits limits = index().limits();
        return random.nextDouble(Math.max(-1e9, limits.min()), Math.min(1e9, limits.max()));
    }

    /** Moves the coordinate by a small random step, but not outside the limits of the index. */
    private double randomStep(Random random, double coordinate) {
        CoordinateLimits limits = index().limits();
        return Math.clamp(coordinate + random.nextDouble(-1, 1), limits.min(), limits.max());
    }

    private Point2 randomPoint(Random random) {
        return new Point2(randomCoordinate(random), randomCoordinate(random));
    }

    private Region2 randomRegion(Random random) {
        if (random.nextBoolean()) {
            Point2 a = randomPoint(random);
            Point2 b = randomPoint(random);
            return new Box2(
                    new Point2(Math.min(a.x(), b.x()), Math.min(a.y(), b.y())),
                    new Point2(Math.max(a.x(), b.x()), Math.max(a.y(), b.y())));
        }
        double radius = random.nextBoolean() ? random.nextInt(0, 11) : random.nextDouble(0, 20);
        return new Circle2(randomPoint(random), radius);
    }
}
