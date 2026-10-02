package badspace.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.Entity2;
import badspace.common.Point2;
import badspace.common.Region2;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RegionsTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 10, 101, 1000})
    void kthSmallestAgreesWithSorting(int length) {
        SplittableRandom random = new SplittableRandom(length);
        for (int round = 0; round < 20; round++) {
            // Few distinct values, so there are many duplicates.
            double[] values = random.ints(length, 0, round % 2 == 0 ? 5 : 1_000_000).asDoubleStream().toArray();
            double[] sorted = values.clone();
            Arrays.sort(sorted);
            int k = random.nextInt(length);
            assertEquals(sorted[k], Regions.kthSmallest(values, length, k));
        }
    }

    @ParameterizedTest
    @EnumSource(RegionShape.class)
    void calibratedRegionContainsTheRequestedNumberOfEntities(RegionShape shape) {
        // Uniform positions are all different, so the region contains exactly count entities.
        List<Entity2> entities = Workload.generate(Distribution.UNIFORM, 2000, 7).entities();
        double[] distances = new double[entities.size()];
        SplittableRandom random = new SplittableRandom(7);
        for (int count : new int[] {1, 2, 20, 200, 2000}) {
            Point2 center = Distribution.UNIFORM.randomPoint(random);
            Region2 region = Regions.calibrated(shape, center, entities, count, distances);
            assertEquals(count, entities.stream().filter(e -> region.contains(e.position())).count());
        }
    }

    @ParameterizedTest
    @EnumSource(RegionShape.class)
    void calibratedRegionContainsAtLeastCountWithCoincidentEntities(RegionShape shape) {
        List<Entity2> entities = Workload.generate(Distribution.COINCIDENT, 5000, 7).entities();
        double[] distances = new double[entities.size()];
        Point2 center = entities.get(0).position();
        Region2 region = Regions.calibrated(shape, center, entities, 3, distances);
        assertTrue(entities.stream().filter(e -> region.contains(e.position())).count() >= 3);
    }
}
