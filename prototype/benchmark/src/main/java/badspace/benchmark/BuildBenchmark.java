package badspace.benchmark;

import badspace.common.partition.EntityWrite2;
import badspace.common.partition.PartitionNode2;
import badspace.service.partition.NodeWrites;
import java.util.List;
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
        // Prepared once, so the benchmark measures only the call.
        List<EntityWrite2> changes;

        @Override
        void afterWorkload() {
            changes = NodeWrites.sets(workload.entities());
        }

        @Setup(Level.Iteration)
        public void createEmptyPartition() {
            node = newNode();
        }
    }

    @Benchmark
    public PartitionNode2 build(BuildState s) {
        NodeWrites.apply(s.node, WorkloadState.WRITE_TOKEN, s.changes);
        return s.node;
    }
}
