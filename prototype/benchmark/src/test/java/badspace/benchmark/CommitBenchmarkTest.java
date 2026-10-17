package badspace.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.PartitionNode2;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks that the tick benchmarks move the entities and then move them back, that the
 * commits publish the moves, and that the readers run until the trial stops them.
 */
class CommitBenchmarkTest {

    private static final int SIZE = 1000;
    private static final Box2 WORLD = new Box2(
            new Point2(0, 0), new Point2(Distribution.WORLD_SIZE, Distribution.WORLD_SIZE));

    private final CommitBenchmark benchmark = new CommitBenchmark();

    private static <S extends CommitBenchmark.SnapshotState> S setUp(S state, int readers) {
        state.index = "LINEAR_SCAN";
        state.distribution = Distribution.CLUSTERS;
        state.size = SIZE;
        state.readers = readers;
        state.setUpTrial();
        return state;
    }

    /** Commits the moves of the writer and moves the tick, so the next snapshot has them. */
    private static void commitAndPublish(CommitBenchmark.SnapshotState s) {
        s.node.commit(WorkloadState.WRITE_TOKEN);
        s.node.setTick(s.node.tick() + 1);
    }

    /** The latest published snapshot. */
    private static List<Entity2> published(CommitBenchmark.SnapshotState s) {
        PartitionNode2 node = s.node;
        Acquisition acquisition = node.acquireLatest(WorkloadState.PARTITION_ID, AcquireOptions.defaultTimeout());
        try {
            return sorted(node.findInRegion(acquisition, WORLD));
        } finally {
            node.release(acquisition);
        }
    }

    private static List<Entity2> sorted(List<Entity2> entities) {
        return entities.stream().sorted(Comparator.comparingLong(Entity2::id)).toList();
    }

    @Test
    void ticksPublishTheMovesAndThenMoveTheEntitiesBack() throws Exception {
        CommitBenchmark.TickState s = new CommitBenchmark.TickState();
        s.movingFraction = 0.1;
        setUp(s, 0);
        try {
            List<Entity2> before = published(s);
            assertEquals(100, s.moved.size());

            benchmark.commitTick(s);
            assertNotEquals(before, published(s));
            assertEquals(2, s.node.tick(WorkloadState.PARTITION_ID));

            benchmark.commitTick(s);
            assertEquals(before, published(s));
        } finally {
            s.stopReaders();
        }
    }

    @Test
    void movesWithoutCommitDoNotPublish() throws Exception {
        CommitBenchmark.TickState s = new CommitBenchmark.TickState();
        s.movingFraction = 1;
        setUp(s, 0);
        try {
            List<Entity2> before = published(s);
            benchmark.movesWithoutCommit(s);
            assertEquals(before, published(s));
            commitAndPublish(s);
            assertNotEquals(before, published(s));
            benchmark.movesWithoutCommit(s);
            commitAndPublish(s);
            assertEquals(before, published(s));
        } finally {
            s.stopReaders();
        }
    }

    @Test
    void ticksWithoutMovingEntitiesKeepThePartition() throws Exception {
        CommitBenchmark.TickState s = new CommitBenchmark.TickState();
        s.movingFraction = 0;
        setUp(s, 0);
        try {
            List<Entity2> before = published(s);
            benchmark.commitTick(s);
            assertEquals(before, published(s));
            assertEquals(2, s.node.tick(WorkloadState.PARTITION_ID));
        } finally {
            s.stopReaders();
        }
    }

    @Test
    void movingFractionOutsideZeroToOneIsRejected() {
        CommitBenchmark.TickState s = new CommitBenchmark.TickState();
        s.movingFraction = 1.5;
        assertThrows(IllegalArgumentException.class, () -> setUp(s, 0));
    }

    @Test
    void readersRunDuringTheTrialAndStopAtTheEnd() throws Exception {
        CommitBenchmark.AcquireState s = new CommitBenchmark.AcquireState();
        setUp(s, 2);
        try {
            assertEquals(2, s.concurrentReaders.running());
            for (int i = 0; i < 100; i++) {
                benchmark.acquireAndRelease(s);
            }
        } finally {
            s.stopReaders();
        }
        assertEquals(0, s.concurrentReaders.running());
    }
}
