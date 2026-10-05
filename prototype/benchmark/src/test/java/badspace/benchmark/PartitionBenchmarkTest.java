package badspace.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import badspace.common.Box2;
import badspace.common.Entity2;
import badspace.common.Point2;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks that the benchmarks leave the partition as they found it. */
class PartitionBenchmarkTest {

    private static final int SIZE = 1000;
    private static final Box2 WORLD = new Box2(
            new Point2(0, 0), new Point2(Distribution.WORLD_SIZE, Distribution.WORLD_SIZE));

    private final PartitionBenchmark benchmark = new PartitionBenchmark();

    private static <S extends WorkloadState> S setUp(S state) {
        state.index = "LINEAR_SCAN";
        state.distribution = Distribution.CLUSTERS;
        state.size = SIZE;
        state.setUpTrial();
        return state;
    }

    private static List<Entity2> content(PartitionBenchmark.PartitionState s) {
        return s.node.findInRegion(WorkloadState.PARTITION, WORLD).stream()
                .sorted(Comparator.comparingLong(Entity2::id))
                .toList();
    }

    @Test
    void insertIsUndoneAfterEachCall() {
        PartitionBenchmark.InsertState s = new PartitionBenchmark.InsertState();
        s.batchSize = 100;
        setUp(s);
        List<Entity2> before = content(s);
        for (int i = 0; i < 2 * PartitionBenchmark.BATCH_COUNT; i++) {
            benchmark.insert(s);
            assertEquals(SIZE + 100, s.node.size(WorkloadState.PARTITION));
            s.removeInserted();
        }
        assertEquals(before, content(s));
    }

    @Test
    void removeIsUndoneAfterEachCall() {
        PartitionBenchmark.RemoveState s = new PartitionBenchmark.RemoveState();
        s.batchSize = 100;
        setUp(s);
        List<Entity2> before = content(s);
        for (int i = 0; i < 2 * PartitionBenchmark.BATCH_COUNT; i++) {
            benchmark.remove(s);
            assertEquals(SIZE - 100, s.node.size(WorkloadState.PARTITION));
            s.insertRemoved();
        }
        assertEquals(before, content(s));
    }

    @Test
    void updatesMoveTheEntitiesAndThenMoveThemBack() {
        for (Movement movement : Movement.values()) {
            PartitionBenchmark.UpdateState s = new PartitionBenchmark.UpdateState();
            s.batchSize = 10;
            s.movement = movement;
            s.step = 10;
            setUp(s);
            List<Entity2> before = content(s);
            int batches = s.moved.size();
            for (int i = 0; i < batches; i++) {
                benchmark.update(s);
            }
            assertFalse(before.equals(content(s)));
            for (int i = 0; i < batches; i++) {
                benchmark.update(s);
            }
            assertEquals(before, content(s));
        }
    }

    @Test
    void queriesFindTheExpectedNumberOfEntities() {
        PartitionBenchmark.RangeState range = new PartitionBenchmark.RangeState();
        range.selectivity = 0.01;
        range.queryCenter = QueryCenter.UNIFORM;
        range.shape = RegionShape.CIRCLE;
        setUp(range);
        assertEquals(10, benchmark.findInRegion(range).size());

        PartitionBenchmark.NearestState nearest = new PartitionBenchmark.NearestState();
        nearest.k = 10;
        nearest.queryCenter = QueryCenter.DATA;
        setUp(nearest);
        assertEquals(10, benchmark.findNearest(nearest).size());

        PartitionBenchmark.GetState get = new PartitionBenchmark.GetState();
        get.batchSize = 100;
        setUp(get);
        assertEquals(100, benchmark.get(get).size());
    }
}
