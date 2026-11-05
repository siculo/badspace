package badspace.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.EntityWrite2;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.WriteMode;
import badspace.common.partition.WriteToken;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks that the tick benchmarks move the entities and then move them back, that the
 * commits publish the moves, and that the readers run until the trial stops them.
 */
class CommitBenchmarkTest {

    private static final int SIZE = 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
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
        s.node.commit(WorkloadState.WRITE_TOKEN, List.of());
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

    @ParameterizedTest
    @EnumSource(WriteMode.class)
    void ticksThroughTheWriterPublishTheMovesAndThenMoveTheEntitiesBack(WriteMode writeMode) throws Exception {
        CommitBenchmark.WriterTickState s = new CommitBenchmark.WriterTickState();
        s.writeMode = writeMode;
        s.movingFraction = 0.1;
        setUp(s, 0);
        try {
            List<Entity2> before = published(s);
            benchmark.commitTickWithTheWriter(s);
            assertNotEquals(before, published(s));
            benchmark.commitTickWithTheWriter(s);
            assertEquals(before, published(s));
        } finally {
            s.stopReaders();
        }
    }

    /**
     * The writer of the state commits on its thread while the benchmark moves the tick. The
     * test stops the writer after its first commit, of the moved positions, so it knows what
     * the next move of the tick publishes: the whole batch. Then it lets the writer go on, and
     * the writer stops at the end of the trial.
     */
    @Test
    void moveOfTheTickPublishesTheWholeBatchOfTheWriter() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(1);
        CommitBenchmark.ContendedTickState s = new CommitBenchmark.ContendedTickState() {
            @Override
            Consumer<List<EntityWrite2>> commits(WriteToken token) {
                Consumer<List<EntityWrite2>> commit = super.commits(token);
                return changes -> {
                    commit.accept(changes);
                    if (changes == moved && committed.getCount() > 0) {
                        committed.countDown();
                        await(checked);
                    }
                };
            }
        };
        s.movingFraction = 1;
        s.busyPartitions = 1;
        setUp(s, 0);
        try {
            List<Entity2> original = published(s);
            await(committed);
            benchmark.moveTickDuringCommits(s);
            assertEquals(sorted(s.movedEntities), published(s));
            assertNotEquals(original, published(s));
        } finally {
            checked.countDown();
            s.stopWriters();
            s.stopReaders();
        }
    }

    /** With more busy partitions each has its writer, and a move of the tick moves all of them. */
    @Test
    void movesOfTheTickMoveAllTheBusyPartitions() throws Exception {
        CommitBenchmark.ContendedTickState s = new CommitBenchmark.ContendedTickState();
        s.movingFraction = 0.1;
        s.busyPartitions = 3;
        setUp(s, 0);
        try {
            assertEquals(3, s.writers.size());
            for (int i = 0; i < 10; i++) {
                benchmark.moveTickDuringCommits(s);
                long tick = s.node.tick(WorkloadState.PARTITION_ID);
                assertEquals(tick, s.node.tick(new PartitionId(2)));
                assertEquals(tick, s.node.tick(new PartitionId(3)));
            }
        } finally {
            s.stopWriters();
            s.stopReaders();
        }
    }

    @Test
    void busyPartitionsLessThanOneAreRejected() {
        CommitBenchmark.ContendedTickState s = new CommitBenchmark.ContendedTickState();
        s.movingFraction = 0.1;
        s.busyPartitions = 0;
        assertThrows(IllegalArgumentException.class, () -> setUp(s, 0));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the writer or the test did not go on");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
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
