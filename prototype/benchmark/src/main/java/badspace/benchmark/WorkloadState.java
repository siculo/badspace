package badspace.benchmark;

import badspace.common.IndexType;
import badspace.common.PartitionId;
import badspace.common.PartitionNode2;
import badspace.service.LocalPartitionNode2;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Parameters shared by all the benchmarks: the index, the distribution of the
 * entities and their number. The default values of the parameters are the ones
 * of the quick profile; {@link BenchmarkMain} can change them.
 */
@State(Scope.Thread)
public abstract class WorkloadState {

    static final PartitionId PARTITION = new PartitionId(1);
    static final long SEED = 42;

    @Param("LINEAR_SCAN")
    public IndexType index;

    @Param({"UNIFORM", "CLUSTERS", "HOTSPOT", "CORRIDORS", "COINCIDENT"})
    public Distribution distribution;

    @Param({"1000", "100000"})
    public int size;

    Workload workload;

    @Setup(Level.Trial)
    public void setUpTrial() {
        workload = Workload.generate(distribution, size, SEED);
        afterWorkload();
    }

    /** Called once for each trial, after the workload is ready. */
    void afterWorkload() {
    }

    /** Returns a new node with an empty partition that uses the index of the benchmark. */
    PartitionNode2 newNode() {
        PartitionNode2 node = new LocalPartitionNode2();
        node.createPartition(PARTITION, index);
        return node;
    }
}
