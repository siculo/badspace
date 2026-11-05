package badspace.benchmark;

import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.PendingWrites2;
import badspace.service.partition.NodeWrites;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measures the memory of the buffer of the writes of a 2D writer, a {@link PendingWrites2}
 * with a set of each entity of a partition and, with {@link MetadataSet#FIXED}, the writes of
 * its metadata, all before the commit. JMH does not measure memory, so this class does it in
 * the process of the runner, like {@link Footprint}: the heap in use with the buffer minus the
 * heap in use before it, both after a garbage collection. The entities, their positions and
 * the metadata writes exist before the buffer, as in a writer that builds them anyway, so they
 * are not counted: the result is the cost of the composition by entity. The score is the median
 * of {@link #REPEAT} samples, all kept in the results.
 */
final class BufferSizes {

    /** The name of the measure in the JSON results. */
    static final String BENCHMARK = "badspace.benchmark.BufferSizes.bytesPerEntity";

    /** Number of samples of each measure. */
    static final int REPEAT = 5;

    private BufferSizes() {
    }

    /** Makes the measure for all the sizes and the metadata sets, and returns the results as JSON objects. */
    static List<String> measure(List<Integer> sizes, List<MetadataSet> metadataSets) {
        List<String> results = new ArrayList<>();
        for (int size : sizes) {
            for (MetadataSet metadata : metadataSets) {
                double[] samples = samples(size, metadata);
                double median = samples[samples.length / 2];
                Map<String, String> params = new LinkedHashMap<>();
                params.put("metadata", metadata.name());
                params.put("size", Integer.toString(size));
                System.out.printf(Locale.ROOT, "BufferSizes.bytesPerEntity %s: %.1f B/entity%n", params, median);
                results.add(Footprint.toJson(BENCHMARK, params, "B/entity", median, samples));
            }
        }
        return results;
    }

    /** Returns the bytes per entity of each repetition, sorted. */
    private static double[] samples(int size, MetadataSet metadata) {
        List<Entity2> entities = PersistenceWorkload.entities2(size);
        List<MetadataWrite> writes = PersistenceWorkload.metadata(metadata, size);
        PartitionNode2 node = NodeWrites.newNode();
        double[] samples = new double[REPEAT];
        for (int r = 0; r < REPEAT; r++) {
            long before = Footprint.usedHeap();
            PendingWrites2 pending = new PendingWrites2(node, WorkloadState.WRITE_TOKEN,
                    IndexConfig.linearScan().limits());
            pending.setAll(entities);
            pending.updateMetadataAll(writes);
            long after = Footprint.usedHeap();
            Reference.reachabilityFence(pending);
            samples[r] = (double) (after - before) / size;
        }
        Reference.reachabilityFence(entities);
        Reference.reachabilityFence(writes);
        Arrays.sort(samples);
        return samples;
    }
}
