package badspace.benchmark;

import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode2;
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

    static final PartitionId PARTITION_ID = new PartitionId(1);
    /** The token that {@link #newNode()} takes: the first owner of a partition has generation 1. */
    static final WriteToken WRITE_TOKEN = new WriteToken(PARTITION_ID, 1);
    static final long SEED = 42;

    /** Name of the index, see {@link IndexNames}. */
    @Param({"LINEAR_SCAN", "UNIFORM_GRID_100"})
    public String index;

    @Param({"UNIFORM", "CLUSTERS", "HOTSPOT", "CORRIDORS", "COINCIDENT", "FAR_CLUSTER", "ORIGIN_CLUSTER"})
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

    /** Returns a new node with an empty partition that uses the index of the benchmark, owned with {@link #WRITE_TOKEN}. */
    PartitionNode2 newNode() {
        PartitionNode2 node = new LocalPartitionNode2();
        node.createPartition(PARTITION_ID, IndexNames.parse(index));
        node.claim(PARTITION_ID, Ownership.INITIAL.generation()).orElseThrow();
        return node;
    }
}
