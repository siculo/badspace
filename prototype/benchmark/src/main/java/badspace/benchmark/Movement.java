package badspace.benchmark;

import badspace.common.Point2;
import java.util.SplittableRandom;

/** How an update moves an entity. */
public enum Movement {

    /** A step of a given length in a random direction, like the movement of one tick. */
    LOCAL,

    /** A jump to a new position, taken from the distribution of the entities. The step is not used. */
    TELEPORT;

    /**
     * Returns the new position of an entity that is in {@code from}. A LOCAL
     * move goes {@code step} units away, unless the limits of the world stop it.
     */
    Point2 move(Point2 from, double step, Workload workload, SplittableRandom random) {
        return switch (this) {
            case LOCAL -> {
                double angle = random.nextDouble(2 * Math.PI);
                yield workload.distribution().clamp(
                        from.x() + step * Math.cos(angle), from.y() + step * Math.sin(angle));
            }
            case TELEPORT -> workload.nextPosition();
        };
    }
}
