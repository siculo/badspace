package badspace.benchmark;

import badspace.common.partition.Entity2;
import badspace.common.geometry.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The entities of a benchmark, generated from a distribution with a fixed seed.
 * Their IDs go from 1 to the number of entities.
 */
final class Workload {

    private final Distribution distribution;
    private final List<Entity2> entities;
    private final Supplier<Point2> positions;

    private Workload(Distribution distribution, List<Entity2> entities, Supplier<Point2> positions) {
        this.distribution = distribution;
        this.entities = entities;
        this.positions = positions;
    }

    static Workload generate(Distribution distribution, int size, long seed) {
        Supplier<Point2> positions = distribution.source(seed);
        List<Entity2> entities = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entities.add(new Entity2(i + 1, positions.get()));
        }
        return new Workload(distribution, List.copyOf(entities), positions);
    }

    /** Returns the distribution of the entities, which also gives their world. */
    Distribution distribution() {
        return distribution;
    }

    List<Entity2> entities() {
        return entities;
    }

    /** Returns the first ID that the entities do not use. */
    long firstFreeId() {
        return entities.size() + 1;
    }

    /** Returns a new position, with the same distribution as the entities. */
    Point2 nextPosition() {
        return positions.get();
    }
}
