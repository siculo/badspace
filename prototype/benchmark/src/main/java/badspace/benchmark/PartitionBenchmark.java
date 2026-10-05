package badspace.benchmark;

import badspace.common.partition.Entity2;
import badspace.common.partition.PartitionNode2;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
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
 * Benchmarks of the single operations on a partition that already has
 * {@code size} entities. Each benchmark measures one call to the node.
 * <p>
 * The partition must stay the same during a trial, otherwise later calls
 * would measure a different partition. So writes are undone: insert and
 * remove are undone after each call, outside the measured time; updates move
 * the entities and then move them back.
 * <p>
 * The inputs are prepared before the trial, in a ring that the calls go
 * through, so the benchmarks do not measure the creation of the inputs.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class PartitionBenchmark {

    /** Number of batches in the ring of each write benchmark, at most. */
    static final int BATCH_COUNT = 64;

    /** Number of queries in the ring of each query benchmark. */
    static final int QUERY_COUNT = 256;

    /** A partition with all the entities of the workload. */
    public abstract static class PartitionState extends WorkloadState {

        PartitionNode2 node;
        SplittableRandom random;

        @Override
        void afterWorkload() {
            node = newNode();
            node.insertAll(PARTITION, workload.entities());
            random = new SplittableRandom(SEED + 1);
            prepare();
        }

        /** Prepares the inputs of the benchmark. */
        abstract void prepare();

        /** Returns the entities of the workload in a random order, so they can be split in batches. */
        List<Entity2> shuffledEntities() {
            List<Entity2> shuffled = new ArrayList<>(workload.entities());
            Collections.shuffle(shuffled, random);
            return shuffled;
        }

        /** Returns the number of batches that fit in the partition, so that batches do not share entities. */
        int disjointBatchCount(int batchSize) {
            return Math.max(1, Math.min(BATCH_COUNT, size / batchSize));
        }
    }

    /** A batch of entities with their IDs. */
    record Batch(List<Entity2> entities, long[] ids) {

        static Batch of(List<Entity2> entities) {
            return new Batch(List.copyOf(entities), entities.stream().mapToLong(Entity2::id).toArray());
        }
    }

    @State(Scope.Thread)
    public static class InsertState extends PartitionState {

        @Param({"1", "100"})
        public int batchSize;

        Batch[] batches;
        int next;
        Batch current;

        @Override
        void prepare() {
            batches = new Batch[BATCH_COUNT];
            long id = workload.firstFreeId();
            for (int b = 0; b < batches.length; b++) {
                List<Entity2> entities = new ArrayList<>(batchSize);
                for (int i = 0; i < batchSize; i++) {
                    entities.add(new Entity2(id++, workload.nextPosition()));
                }
                batches[b] = Batch.of(entities);
            }
        }

        Batch nextBatch() {
            current = batches[next];
            next = (next + 1) % batches.length;
            return current;
        }

        @TearDown(Level.Invocation)
        public void removeInserted() {
            node.removeAll(PARTITION, current.ids());
        }
    }

    @State(Scope.Thread)
    public static class RemoveState extends PartitionState {

        @Param({"1", "100"})
        public int batchSize;

        Batch[] batches;
        int next;
        Batch current;

        @Override
        void prepare() {
            List<Entity2> shuffled = shuffledEntities();
            batches = new Batch[disjointBatchCount(batchSize)];
            for (int b = 0; b < batches.length; b++) {
                batches[b] = Batch.of(shuffled.subList(b * batchSize, (b + 1) * batchSize));
            }
        }

        Batch nextBatch() {
            current = batches[next];
            next = (next + 1) % batches.length;
            return current;
        }

        @TearDown(Level.Invocation)
        public void insertRemoved() {
            node.insertAll(PARTITION, current.entities());
        }
    }

    @State(Scope.Thread)
    public static class UpdateState extends PartitionState {

        @Param({"1", "100"})
        public int batchSize;

        @Param({"LOCAL", "TELEPORT"})
        public Movement movement;

        /**
         * Length of a LOCAL move, {@code d = v · DT}: with the side {@code L}
         * of a leaf or cell, {@code d / L} gives how often an entity changes
         * leaf. TELEPORT does not use it.
         */
        @Param("10")
        public double step;

        List<List<Entity2>> moved;
        List<List<Entity2>> original;
        int next;

        @Override
        void prepare() {
            List<Entity2> shuffled = shuffledEntities();
            int count = disjointBatchCount(batchSize);
            moved = new ArrayList<>(count);
            original = new ArrayList<>(count);
            for (int b = 0; b < count; b++) {
                List<Entity2> batch = List.copyOf(shuffled.subList(b * batchSize, (b + 1) * batchSize));
                original.add(batch);
                moved.add(batch.stream()
                        .map(e -> new Entity2(e.id(), movement.move(e.position(), step, workload, random)))
                        .toList());
            }
        }

        /** Goes through all the moved batches, then through all the original ones, and so on. */
        List<Entity2> nextBatch() {
            int count = moved.size();
            int i = next;
            next = (next + 1) % (2 * count);
            return i < count ? moved.get(i) : original.get(i - count);
        }
    }

    @State(Scope.Thread)
    public static class GetState extends PartitionState {

        @Param({"1", "100"})
        public int batchSize;

        long[][] batches;
        int next;

        @Override
        void prepare() {
            batches = new long[BATCH_COUNT][batchSize];
            for (long[] batch : batches) {
                for (int i = 0; i < batch.length; i++) {
                    batch[i] = 1 + random.nextInt(size);
                }
            }
        }

        long[] nextBatch() {
            long[] batch = batches[next];
            next = (next + 1) % batches.length;
            return batch;
        }
    }

    @State(Scope.Thread)
    public static class RangeState extends PartitionState {

        /** Share of the entities that each query finds. */
        @Param({"0.001", "0.01"})
        public double selectivity;

        @Param({"UNIFORM", "DATA"})
        public QueryCenter queryCenter;

        @Param("BOX")
        public RegionShape shape;

        Region2[] regions;
        int next;

        @Override
        void prepare() {
            int count = Math.clamp(Math.round(selectivity * size), 1, size);
            double[] distances = new double[size];
            regions = new Region2[QUERY_COUNT];
            for (int i = 0; i < regions.length; i++) {
                Point2 center = queryCenter.next(workload, random);
                regions[i] = Regions.calibrated(shape, center, workload.entities(), count, distances);
            }
        }

        Region2 nextRegion() {
            Region2 region = regions[next];
            next = (next + 1) % regions.length;
            return region;
        }
    }

    @State(Scope.Thread)
    public static class NearestState extends PartitionState {

        @Param({"1", "10"})
        public int k;

        @Param({"UNIFORM", "DATA"})
        public QueryCenter queryCenter;

        Point2[] points;
        int next;

        @Override
        void prepare() {
            points = new Point2[QUERY_COUNT];
            for (int i = 0; i < points.length; i++) {
                points[i] = queryCenter.next(workload, random);
            }
        }

        Point2 nextPoint() {
            Point2 point = points[next];
            next = (next + 1) % points.length;
            return point;
        }
    }

    @Benchmark
    public void insert(InsertState s) {
        s.node.insertAll(WorkloadState.PARTITION, s.nextBatch().entities());
    }

    @Benchmark
    public void remove(RemoveState s) {
        s.node.removeAll(WorkloadState.PARTITION, s.nextBatch().ids());
    }

    @Benchmark
    public void update(UpdateState s) {
        s.node.updateAll(WorkloadState.PARTITION, s.nextBatch());
    }

    @Benchmark
    public List<Entity2> get(GetState s) {
        return s.node.getAll(WorkloadState.PARTITION, s.nextBatch());
    }

    @Benchmark
    public List<Entity2> findInRegion(RangeState s) {
        return s.node.findInRegion(WorkloadState.PARTITION, s.nextRegion());
    }

    @Benchmark
    public List<Entity2> findNearest(NearestState s) {
        return s.node.findNearest(WorkloadState.PARTITION, s.nextPoint(), s.k);
    }
}
