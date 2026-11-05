package badspace.benchmark;

import badspace.common.geometry.Point2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.EntityWrite2;
import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.WriteMode;
import badspace.common.partition.WriteSession2;
import badspace.common.partition.WriteToken;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
            NodeWrites.commit(node, WRITE_TOKEN);
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

        List<EntityWrite2> moved;
        List<EntityWrite2> original;
        // The same moves as entities, for the writes through the buffer of the writer.
        List<Entity2> movedEntities;
        List<Entity2> originalEntities;
        WriteSession2 session;
        boolean movedBack = true;

        @Override
        void prepareMoves() {
            if (!(movingFraction >= 0 && movingFraction <= 1)) {
                throw new IllegalArgumentException("Moving fraction is not between 0 and 1: " + movingFraction);
            }
            int count = (int) Math.round(size * movingFraction);
            originalEntities = List.copyOf(shuffledEntities().subList(0, count));
            movedEntities = originalEntities.stream()
                    .map(e -> new Entity2(e.id(), Movement.LOCAL.move(e.position(), STEP, workload, random)))
                    .toList();
            original = NodeWrites.sets(originalEntities);
            moved = NodeWrites.sets(movedEntities);
            session = WriteSession2.of(node, WRITE_TOKEN, node.limits(PARTITION_ID));
        }

        /** Returns the moves of the next tick as entities, like {@link #nextMoves()}. */
        List<Entity2> nextMovedEntities() {
            movedBack = !movedBack;
            return movedBack ? originalEntities : movedEntities;
        }

        /** Returns the moves of the next tick: the moved positions, then the original ones, and so on. */
        List<EntityWrite2> nextMoves() {
            movedBack = !movedBack;
            return movedBack ? original : moved;
        }
    }

    /**
     * The moves of {@link TickState} through the writes of the writer, on a node in each write
     * mode: in the direct mode each call goes to the node, with the buffer the commit sends
     * them in one batch.
     */
    @State(Scope.Thread)
    public static class WriterTickState extends TickState {

        /** The write mode of the node. */
        @Param({"DIRECT", "BUFFERED"})
        public WriteMode writeMode;

        @Override
        WriteMode writeMode() {
            return writeMode;
        }
    }

    @State(Scope.Thread)
    public static class AcquireState extends SnapshotState {
    }

    /**
     * The moves of {@link TickState}, which a writer on another thread commits again and
     * again, one batch for each commit, while the benchmark moves the tick: each move of the
     * tick waits for the commit that holds the lock of the partition when it arrives. With
     * {@code busyPartitions} greater than 1, the node has more partitions with the same
     * entities, each with its own writer, so the move of the tick finds commits running on
     * more partitions.
     */
    @State(Scope.Thread)
    public static class ContendedTickState extends TickState {

        /** Number of partitions of the node with a writer that commits without pauses. */
        @Param({"1"})
        public int busyPartitions;

        List<CommittingWriter> writers;

        @Override
        void prepare() {
            super.prepare();
            if (busyPartitions < 1) {
                throw new IllegalArgumentException("Number of busy partitions is less than 1: " + busyPartitions);
            }
            List<WriteToken> tokens = new ArrayList<>(List.of(WRITE_TOKEN));
            List<EntityWrite2> entities = NodeWrites.sets(workload.entities());
            for (int i = 1; i < busyPartitions; i++) {
                PartitionId partitionId = new PartitionId(PARTITION_ID.value() + i);
                node.createPartition(partitionId, new PartitionName(PARTITION_NAME.value() + "-" + i),
                        IndexNames.parse(index));
                WriteToken token = node.claim(partitionId, Ownership.INITIAL.generation()).orElseThrow();
                node.commit(token, entities);
                tokens.add(token);
            }
            node.setTick(++tick);
            writers = new ArrayList<>(tokens.size());
            for (WriteToken token : tokens) {
                writers.add(new CommittingWriter(commits(token), original, moved));
            }
        }

        /**
         * Returns how the writer of a partition commits a batch: with the commit of the node.
         * The tests replace it, to stop the writer after a commit that they choose.
         */
        Consumer<List<EntityWrite2>> commits(WriteToken token) {
            return changes -> node.commit(token, changes);
        }

        @TearDown(Level.Trial)
        public void stopWriters() throws InterruptedException {
            for (CommittingWriter writer : writers) {
                writer.stop();
            }
        }
    }

    /** Moves a fraction of the entities, commits and moves the tick: one tick of the writer. */
    @Benchmark
    public void commitTick(TickState s) {
        move(s);
        NodeWrites.commit(s.node, WorkloadState.WRITE_TOKEN);
        s.node.setTick(++s.tick);
    }

    /**
     * The same tick as {@link #commitTick}, through the writes of the writer, a
     * {@link WriteSession2}, as the writer handle of the API does, in the write mode of the
     * node. In the direct mode the moves go to the node at once, like in commitTick, and the
     * difference is the cost of building the changes; with the buffer the commit sends them in
     * one batch, and the difference is also the cost of the buffer.
     */
    @Benchmark
    public void commitTickWithTheWriter(WriterTickState s) {
        s.session.setAll(s.nextMovedEntities());
        s.session.commit();
        s.node.setTick(++s.tick);
    }

    /** The same moves as {@link #commitTick}, without the commit and the move of the tick. */
    @Benchmark
    public void movesWithoutCommit(TickState s) {
        move(s);
    }

    /**
     * Moves the tick while a writer on another thread commits the moves of a fraction of the
     * entities, without pauses between the commits, on each of {@code busyPartitions}
     * partitions (see {@link ContendedTickState}). The time
     * is the latency of the move of the tick, mostly the wait for the commit that holds the
     * lock of the partition; the commits are not measured. With movingFraction 0 the commits
     * have no changes: the reference.
     */
    @Benchmark
    public void moveTickDuringCommits(ContendedTickState s) {
        s.node.setTick(++s.tick);
    }

    /** Acquires the latest snapshot and releases it. */
    @Benchmark
    public Acquisition acquireAndRelease(AcquireState s) {
        Acquisition acquisition = s.node.acquireLatest(WorkloadState.PARTITION_ID, AcquireOptions.defaultTimeout());
        s.node.release(acquisition);
        return acquisition;
    }

    private static void move(TickState s) {
        List<EntityWrite2> moves = s.nextMoves();
        if (!moves.isEmpty()) {
            NodeWrites.apply(s.node, WorkloadState.WRITE_TOKEN, moves);
        }
    }

    /**
     * A thread that commits batches on the partition until it is stopped, with no pauses:
     * the original positions, then the moved ones, and so on. It is not measured.
     */
    static final class CommittingWriter {

        private final Thread thread;
        // Written by the thread that stops the writer, read by the writer.
        private volatile boolean stopped;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        /** Starts the writer: it commits the moved positions first, with the given commit. */
        CommittingWriter(Consumer<List<EntityWrite2>> commits, List<EntityWrite2> original, List<EntityWrite2> moved) {
            thread = new Thread(() -> commit(commits, original, moved), "writer");
            thread.setDaemon(true);
            thread.start();
        }

        private void commit(Consumer<List<EntityWrite2>> commits, List<EntityWrite2> original,
                List<EntityWrite2> moved) {
            try {
                boolean movedBack = true;
                while (!stopped) {
                    movedBack = !movedBack;
                    commits.accept(movedBack ? original : moved);
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }

        /** Stops the writer and waits for it. Fails if the writer failed or did not stop. */
        void stop() throws InterruptedException {
            stopped = true;
            thread.join(TimeUnit.SECONDS.toMillis(10));
            if (thread.isAlive()) {
                throw new IllegalStateException("Writer did not stop");
            }
            if (failure.get() != null) {
                throw new IllegalStateException("Writer failed", failure.get());
            }
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
