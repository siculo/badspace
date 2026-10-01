package badspace.benchmark;

import badspace.common.PartitionNode2;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Time to fill an empty partition with all the entities in a single call,
 * as when a partition is loaded. Each iteration starts from a new partition,
 * so the benchmark measures single calls.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BuildBenchmark {

    @State(Scope.Thread)
    public static class BuildState extends WorkloadState {

        PartitionNode2 node;

        @Setup(Level.Iteration)
        public void createEmptyPartition() {
            node = newNode();
        }
    }

    @Benchmark
    public PartitionNode2 build(BuildState s) {
        s.node.insertAll(WorkloadState.PARTITION, s.workload.entities());
        return s.node;
    }
}
