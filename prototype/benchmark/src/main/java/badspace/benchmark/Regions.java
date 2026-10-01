package badspace.benchmark;

import badspace.common.Box2;
import badspace.common.Circle2;
import badspace.common.Entity2;
import badspace.common.Point2;
import badspace.common.Region2;
import java.util.List;

/**
 * Builds the regions of the range queries. A region is sized on the entities,
 * so that it contains a given number of them: in this way the selectivity is
 * the same for all the distributions, and the benchmarks compare the cost of
 * the search, not the size of the result.
 */
final class Regions {

    private Regions() {
    }

    /**
     * Returns the smallest region with the center and the shape that contains
     * at least {@code count} entities. It can contain more of them when some
     * entities are at the same distance from the center.
     *
     * @param distances work array with at least one element for each entity
     */
    static Region2 calibrated(RegionShape shape, Point2 center, List<Entity2> entities, int count, double[] distances) {
        if (count < 1 || count > entities.size()) {
            throw new IllegalArgumentException("Bad count: " + count);
        }
        int n = entities.size();
        for (int i = 0; i < n; i++) {
            Point2 p = entities.get(i).position();
            distances[i] = switch (shape) {
                case BOX -> Math.max(Math.abs(p.x() - center.x()), Math.abs(p.y() - center.y()));
                case CIRCLE -> Math.sqrt(p.distanceSquared(center));
            };
        }
        // nextUp() keeps the farthest entity inside, despite rounding errors.
        double radius = Math.nextUp(kthSmallest(distances, n, count - 1));
        return switch (shape) {
            case BOX -> new Box2(
                    new Point2(center.x() - radius, center.y() - radius),
                    new Point2(center.x() + radius, center.y() + radius));
            case CIRCLE -> new Circle2(center, radius);
        };
    }

    /**
     * Returns the value that would be at index {@code k} if the first
     * {@code length} values were sorted. It changes the order of the values.
     */
    static double kthSmallest(double[] values, int length, int k) {
        int low = 0;
        int high = length - 1;
        while (low < high) {
            double pivot = values[(low + high) >>> 1];
            int i = low;
            int j = high;
            while (i <= j) {
                while (values[i] < pivot) {
                    i++;
                }
                while (values[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    double t = values[i];
                    values[i] = values[j];
                    values[j] = t;
                    i++;
                    j--;
                }
            }
            // Now values[low..j] <= pivot <= values[i..high], and the values between j and i are equal to pivot.
            if (k <= j) {
                high = j;
            } else if (k >= i) {
                low = i;
            } else {
                return values[k];
            }
        }
        return values[k];
    }
}
