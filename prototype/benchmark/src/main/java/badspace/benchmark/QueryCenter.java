package badspace.benchmark;

import badspace.common.Point2;
import java.util.SplittableRandom;

/** Where the queries are made. */
public enum QueryCenter {

    /** Any point of the world, also where there are no entities. */
    UNIFORM,

    /** The position of an entity, so the queries go where the entities are. */
    DATA;

    /** Returns the center of a new query. */
    Point2 next(Workload workload, SplittableRandom random) {
        return switch (this) {
            case UNIFORM -> Distribution.uniform(random);
            case DATA -> workload.entities().get(random.nextInt(workload.entities().size())).position();
        };
    }
}
