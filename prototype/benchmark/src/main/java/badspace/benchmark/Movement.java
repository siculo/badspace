package badspace.benchmark;

import badspace.common.Point2;
import java.util.SplittableRandom;

/** How an update moves an entity. */
public enum Movement {

    /** A short step in a random direction, like the movement of one tick. */
    LOCAL,

    /** A jump to a new position, taken from the distribution of the entities. */
    TELEPORT;

    static final double STEP = Distribution.WORLD_SIZE / 1000;

    /** Returns the new position of an entity that is in {@code from}. */
    Point2 move(Point2 from, Workload workload, SplittableRandom random) {
        return switch (this) {
            case LOCAL -> {
                double angle = random.nextDouble(2 * Math.PI);
                yield workload.distribution().clamp(
                        from.x() + STEP * Math.cos(angle), from.y() + STEP * Math.sin(angle));
            }
            case TELEPORT -> workload.nextPosition();
        };
    }
}
