package badspace.benchmark;

import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.SnapshotNotAvailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Benchmarks of the snapshots of a partition that already has {@code size} entities: the
 * cost of a tick of the writer with a commit, and the cost of an acquisition and its release.
 * <p>
 * A tick moves a fraction of the entities, commits and moves the tick, so the commit makes
 * a snapshot: it copies the changed pages of the coordinates into a buffer, and the moves
 * copy the nodes of the index that are still shared with the published snapshot. The same
 * moves without a commit are the reference: the difference is the cost of the snapshots.
 * The moved entities go back to their positions at the next tick, so the partition stays
 * the same during a trial.
 * <p>
 * Readers on other threads can acquire the latest snapshot, query it and release it while
 * the benchmark runs: they keep snapshots alive, so the commits find fewer buffers in the
 * pool, and they compete with the writer for the processor and for the acquisitions.
 * <p>
 * These benchmarks are not in the default run: a run chooses them with its include
 * pattern, for example {@code --include CommitBenchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CommitBenchmark {

    /** Length of the LOCAL move of an entity in a tick, as the default step of the updates. */
    static final double STEP = 10;

    /** Number of nearest entities that a reader asks for in each query. */
    static final int READER_QUERY_COUNT = 10;

    /**
     * A partition with all the entities of the workload, committed and published, and the
     * readers on other threads.
     */
    public abstract static class SnapshotState extends PartitionBenchmark.PartitionState {

        /** Number of reader threads that acquire, query and release the latest snapshot. */
        @Param({"0", "2"})
        public int readers;

        long tick;
        ConcurrentReaders concurrentReaders;

        @Override
        void prepare() {
            prepareMoves();
            // The readers need a published snapshot.
            node.commit(WRITE_TOKEN);
            node.setTick(++tick);
            concurrentReaders = new ConcurrentReaders(node, readers, workload.entities().getFirst().position());
        }

        /** Prepares the moves of the ticks, before the first commit. */
        void prepareMoves() {
        }

        @TearDown(Level.Trial)
        public void stopReaders() throws InterruptedException {
            concurrentReaders.stop();
        }
    }

    @State(Scope.Thread)
    public static class TickState extends SnapshotState {

        /** The fraction of the entities that move at each tick, from 0 to 1. */
        @Param({"0", "0.01", "0.1", "1"})
        public double movingFraction;

        List<Entity2> moved;
        List<Entity2> original;
        boolean movedBack = true;

        @Override
        void prepareMoves() {
            if (!(movingFraction >= 0 && movingFraction <= 1)) {
                throw new IllegalArgumentException("Moving fraction is not between 0 and 1: " + movingFraction);
            }
            int count = (int) Math.round(size * movingFraction);
            original = List.copyOf(shuffledEntities().subList(0, count));
            moved = original.stream()
                    .map(e -> new Entity2(e.id(), Movement.LOCAL.move(e.position(), STEP, workload, random)))
                    .toList();
        }

        /** Returns the moves of the next tick: the moved positions, then the original ones, and so on. */
        List<Entity2> nextMoves() {
            movedBack = !movedBack;
            return movedBack ? original : moved;
        }
    }

    @State(Scope.Thread)
    public static class AcquireState extends SnapshotState {
    }

    /** Moves a fraction of the entities, commits and moves the tick: one tick of the writer. */
    @Benchmark
    public void commitTick(TickState s) {
        move(s);
        s.node.commit(WorkloadState.WRITE_TOKEN);
        s.node.setTick(++s.tick);
    }

    /** The same moves as {@link #commitTick}, without the commit and the move of the tick. */
    @Benchmark
    public void movesWithoutCommit(TickState s) {
        move(s);
    }

    /** Acquires the latest snapshot and releases it. */
    @Benchmark
    public Acquisition acquireAndRelease(AcquireState s) {
        Acquisition acquisition = s.node.acquireLatest(WorkloadState.PARTITION_ID, AcquireOptions.defaultTimeout());
        s.node.release(acquisition);
        return acquisition;
    }

    private static void move(TickState s) {
        List<Entity2> moves = s.nextMoves();
        if (!moves.isEmpty()) {
            s.node.updateAll(WorkloadState.WRITE_TOKEN, moves);
        }
    }

    /**
     * Threads that acquire the latest snapshot of the partition, ask it for the nearest
     * entities to a point and release it, until they are stopped. They are not measured.
     */
    static final class ConcurrentReaders {

        private final List<Thread> threads = new ArrayList<>();
        // Written by the thread that stops the readers, read by the readers.
        private volatile boolean stopped;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        ConcurrentReaders(PartitionNode2 node, int count, Point2 point) {
            if (count < 0) {
                throw new IllegalArgumentException("Negative number of readers: " + count);
            }
            for (int i = 0; i < count; i++) {
                Thread thread = new Thread(() -> read(node, point), "reader-" + i);
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
        }

        private void read(PartitionNode2 node, Point2 point) {
            try {
                while (!stopped) {
                    Acquisition acquisition;
                    try {
                        acquisition = node.acquireLatest(WorkloadState.PARTITION_ID, AcquireOptions.defaultTimeout());
                    } catch (SnapshotNotAvailableException e) {
                        continue;
                    }
                    try {
                        node.findNearest(acquisition, point, READER_QUERY_COUNT);
                    } finally {
                        node.release(acquisition);
                    }
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }

        /** Returns the number of reader threads that are still running. */
        long running() {
            return threads.stream().filter(Thread::isAlive).count();
        }

        /** Stops the readers and waits for them. Fails if a reader failed or did not stop. */
        void stop() throws InterruptedException {
            stopped = true;
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(10));
                if (thread.isAlive()) {
                    throw new IllegalStateException("Reader did not stop: " + thread.getName());
                }
            }
            if (failure.get() != null) {
                throw new IllegalStateException("A reader failed", failure.get());
            }
        }
    }
}
