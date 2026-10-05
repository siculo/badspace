package badspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box3;
import badspace.common.geometry.Sphere3;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
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
 * Correctness tests that each 3D index must pass. A subclass gives the index
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
abstract class SpatialIndex3Contract {

    private static final int STEPS = 200;
    private static final int QUERIES_PER_STEP = 5;
    private static final int MAX_BATCH = 20;
    private static final long MAX_ID = 300;

    private static final Comparator<Entity3> BY_ID = Comparator.comparingLong(Entity3::id);

    /** Returns the index to test. */
    abstract IndexConfig index();

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 20);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void randomWritesAndQueriesGiveTheSameResultsAsTheModel(long seed) {
        Random random = new Random(seed);
        PartitionStorage3 storage = newStorage();
        Map<Long, Point3> model = new HashMap<>();
        for (int step = 0; step < STEPS; step++) {
            String write = randomWrite(random, storage, model);
            String where = "seed " + seed + ", step " + step + " (" + write + ")";
            assertEquals(model.size(), storage.size(), where + ": size");
            for (int i = 0; i < QUERIES_PER_STEP; i++) {
                Region3 region = randomRegion(random);
                assertEquals(expectedInRegion(model, region), sortedById(storage.findInRegion(region)),
                        where + ": findInRegion " + region);
                Point3 point = randomPoint(random);
                int count = 1 + random.nextInt(model.size() + 2);
                assertEquals(expectedNearest(model, point, count), storage.findNearest(point, count),
                        where + ": findNearest " + point + " " + count);
            }
        }
    }

    @Test
    void boxContainsItsBorderOnly() {
        PartitionStorage3 storage = storageWith(
                entity(1, 0, 0, 0), entity(2, 4, 2, 3), entity(3, 2, 0, 1), entity(4, 0, 2, 3),
                entity(5, Math.nextDown(0.0), 1, 1), entity(6, Math.nextUp(4.0), 1, 1),
                entity(7, 1, Math.nextDown(0.0), 1), entity(8, 1, Math.nextUp(2.0), 1),
                entity(9, 1, 1, Math.nextDown(0.0)), entity(10, 1, 1, Math.nextUp(3.0)));
        Box3 box = new Box3(new Point3(0, 0, 0), new Point3(4, 2, 3));
        assertEquals(List.of(entity(1, 0, 0, 0), entity(2, 4, 2, 3), entity(3, 2, 0, 1), entity(4, 0, 2, 3)),
                sortedById(storage.findInRegion(box)));
    }

    @Test
    void sphereContainsItsBorderOnly() {
        PartitionStorage3 storage = storageWith(
                entity(1, 2, 3, 6), entity(2, -7, 0, 0), entity(3, 0, 0, -7),
                entity(4, 2, 3, Math.nextUp(6.0)), entity(5, Math.nextDown(-7.0), 0, 0));
        Sphere3 sphere = new Sphere3(new Point3(0, 0, 0), 7);
        assertEquals(List.of(entity(1, 2, 3, 6), entity(2, -7, 0, 0), entity(3, 0, 0, -7)),
                sortedById(storage.findInRegion(sphere)));
    }

    @Test
    void regionsWithoutSizeContainOnlyTheirPoint() {
        PartitionStorage3 storage = storageWith(
                entity(1, 1, 1, 1), entity(2, 1, 1, 1), entity(3, 1, 1, Math.nextUp(1.0)));
        Point3 p = new Point3(1, 1, 1);
        List<Entity3> expected = List.of(entity(1, 1, 1, 1), entity(2, 1, 1, 1));
        assertEquals(expected, sortedById(storage.findInRegion(new Box3(p, p))));
        assertEquals(expected, sortedById(storage.findInRegion(new Sphere3(p, 0))));
    }

    @Test
    void findNearestBreaksTiesById() {
        PartitionStorage3 storage = storageWith(
                entity(30, 1, 0, 0), entity(10, 0, 0, 1), entity(20, 0, -1, 0),
                entity(40, 5, 5, 5), entity(5, 0, 0, 0));
        assertEquals(List.of(entity(5, 0, 0, 0), entity(10, 0, 0, 1), entity(20, 0, -1, 0)),
                storage.findNearest(new Point3(0, 0, 0), 3));
    }

    @Test
    void writesOutsideTheLimitsFailAndChangeNothing() {
        CoordinateLimits limits = index().limits();
        PartitionStorage3 storage = storageWith(entity(1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(entity(2, 0, 0, 0), entity(3, Math.nextUp(limits.max()), 0, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> storage.insertAll(List.of(entity(4, -Double.MAX_VALUE, 0, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateAll(List.of(entity(1, 0, 0, Math.nextDown(limits.min())))));
        assertEquals(1, storage.size());
        assertEquals(List.of(entity(1, 0, 0, 0)), storage.findNearest(new Point3(0, 0, 0), 2));
    }

    @Test
    void entitiesOnTheLimitsAreFoundAlsoByQueriesOutsideThem() {
        CoordinateLimits limits = index().limits();
        double min = limits.min();
        double max = limits.max();
        double far = Double.MAX_VALUE;
        List<Entity3> entities = List.of(
                entity(1, min, min, min), entity(2, max, max, max), entity(3, min, max, min), entity(4, 0, 0, 0));
        Map<Long, Point3> model = new HashMap<>();
        entities.forEach(e -> model.put(e.id(), e.position()));
        PartitionStorage3 storage = storageWith(entities.toArray(Entity3[]::new));
        for (Region3 region : List.of(
                new Box3(new Point3(min, min, min), new Point3(max, max, max)),
                new Box3(new Point3(max, max, max), new Point3(max, max, max)),
                new Sphere3(new Point3(2 * max, 0, 0), 2 * max),
                new Sphere3(new Point3(far, far, far), far))) {
            assertEquals(expectedInRegion(model, region), sortedById(storage.findInRegion(region)),
                    "findInRegion " + region);
        }
        for (Point3 point : List.of(new Point3(max, max, max), new Point3(2 * max, 0, 0), new Point3(far, far, far))) {
            assertEquals(expectedNearest(model, point, 2), storage.findNearest(point, 2), "findNearest " + point);
        }
    }

    @Test
    void queriesOfEmptyStorageAreEmpty() {
        PartitionStorage3 storage = storageWith();
        assertEquals(List.of(), storage.findInRegion(new Sphere3(new Point3(0, 0, 0), 1e9)));
        assertEquals(List.of(), storage.findNearest(new Point3(0, 0, 0), 3));
    }

    private PartitionStorage3 newStorage() {
        return new PartitionStorage3(index());
    }

    private PartitionStorage3 storageWith(Entity3... entities) {
        PartitionStorage3 storage = newStorage();
        storage.insertAll(List.of(entities));
        return storage;
    }

    private static Entity3 entity(long id, double x, double y, double z) {
        return new Entity3(id, new Point3(x, y, z));
    }

    private static List<Entity3> sortedById(List<Entity3> entities) {
        return entities.stream().sorted(BY_ID).toList();
    }

    private static List<Entity3> entitiesOf(Map<Long, Point3> model) {
        List<Entity3> entities = new ArrayList<>();
        model.forEach((id, p) -> entities.add(new Entity3(id, p)));
        return entities;
    }

    /** The entities of the model inside the region, sorted by ID. */
    private static List<Entity3> expectedInRegion(Map<Long, Point3> model, Region3 region) {
        return entitiesOf(model).stream().filter(e -> region.contains(e.position())).sorted(BY_ID).toList();
    }

    /** The count entities of the model nearest to the point, by distance and then by ID. */
    private static List<Entity3> expectedNearest(Map<Long, Point3> model, Point3 point, int count) {
        return entitiesOf(model).stream()
                .sorted(Comparator.comparingDouble((Entity3 e) -> e.position().distanceSquared(point))
                        .thenComparingLong(Entity3::id))
                .limit(count).toList();
    }

    /**
     * Applies a random write to the storage and to the model, and returns a
     * description of it. An empty storage always gets an insert, a full one
     * (all the IDs in use) never does.
     */
    private String randomWrite(Random random, PartitionStorage3 storage, Map<Long, Point3> model) {
        int choice = model.isEmpty() ? 0 : random.nextInt(20);
        if (choice < 8 && model.size() < MAX_ID) {
            List<Entity3> entities = newEntities(random, model);
            storage.insertAll(entities);
            entities.forEach(e -> model.put(e.id(), e.position()));
            return "insert " + entities.size();
        }
        if (choice < 12) {
            List<Entity3> entities = new ArrayList<>();
            for (long id : someIds(random, model)) {
                entities.add(new Entity3(id, randomPoint(random)));
            }
            storage.updateAll(entities);
            entities.forEach(e -> model.put(e.id(), e.position()));
            return "teleport " + entities.size();
        }
        if (choice < 16) {
            List<Entity3> entities = new ArrayList<>();
            for (long id : someIds(random, model)) {
                Point3 from = model.get(id);
                Point3 to = new Point3(
                        randomStep(random, from.x()), randomStep(random, from.y()), randomStep(random, from.z()));
                entities.add(new Entity3(id, to));
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
    private List<Entity3> newEntities(Random random, Map<Long, Point3> model) {
        List<Long> free = new ArrayList<>();
        for (long id = 1; id <= MAX_ID; id++) {
            if (!model.containsKey(id)) {
                free.add(id);
            }
        }
        Collections.shuffle(free, random);
        int count = 1 + random.nextInt(Math.min(MAX_BATCH, free.size()));
        List<Entity3> entities = new ArrayList<>();
        for (long id : free.subList(0, count)) {
            entities.add(new Entity3(id, randomPoint(random)));
        }
        return entities;
    }

    /** Some IDs of the model, at least one, in random order. */
    private static List<Long> someIds(Random random, Map<Long, Point3> model) {
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

    private Point3 randomPoint(Random random) {
        return new Point3(randomCoordinate(random), randomCoordinate(random), randomCoordinate(random));
    }

    private Region3 randomRegion(Random random) {
        if (random.nextBoolean()) {
            Point3 a = randomPoint(random);
            Point3 b = randomPoint(random);
            return new Box3(
                    new Point3(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
                    new Point3(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
        }
        double radius = random.nextBoolean() ? random.nextInt(0, 11) : random.nextDouble(0, 20);
        return new Sphere3(randomPoint(random), radius);
    }
}
