package badspace.benchmark;

import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionNode2;
import badspace.service.partition.LocalPartitionNode2;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openjdk.jmh.util.Version;

/**
 * Measures the memory that a partition uses for each entity, storage and
 * index together. JMH does not measure memory, so this class does it with the
 * heap size after a garbage collection: the result is approximate, but good
 * enough to compare the indices. The results are written in the same JSON
 * format as the JMH results, so the report reads them in the same way.
 */
final class Footprint {

    /** Name of the benchmark in the JSON results. */
    static final String BENCHMARK = "badspace.benchmark.Footprint.bytesPerEntity";

    private static final int REPEAT = 3;

    private Footprint() {
    }

    /**
     * One measure, with its parameters. The score is the median of the
     * samples, to reduce the noise of the garbage collector.
     *
     * @param samples bytes per entity of each repetition, sorted
     */
    record Result(String index, Distribution distribution, int size, double[] samples) {

        double bytesPerEntity() {
            return samples[samples.length / 2];
        }

        /** Returns the result in the format of the JMH results, see {@link Footprint#toJson}. */
        String toJson() {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("index", index);
            params.put("distribution", distribution.name());
            params.put("size", Integer.toString(size));
            return Footprint.toJson(BENCHMARK, params, "B/entity", bytesPerEntity(), samples);
        }
    }

    /**
     * Returns a measure that JMH does not make, with all the fields of a JMH result, in
     * the mode {@code footprint}. Tools that read JMH results, like the online JMH
     * Visualizer, expect all of them. The confidence interval goes from the lowest to the
     * highest sample, and the percentiles come from the samples.
     *
     * @param samples the samples of the measure, sorted
     */
    static String toJson(String benchmark, Map<String, String> params, String unit, double score, double[] samples) {
        String percentiles = Stream.of("0.0", "50.0", "90.0", "95.0", "99.0", "99.9", "99.99", "99.999", "99.9999", "100.0")
                .map(p -> String.format(Locale.ROOT, "\"%s\" : %s", p, number(percentile(samples, Double.parseDouble(p)))))
                .collect(Collectors.joining(", "));
        String rawData = Arrays.stream(samples).mapToObj(Footprint::number).collect(Collectors.joining(", "));
        String paramsJson = params.entrySet().stream()
                .map(e -> String.format(Locale.ROOT, "        \"%s\" : \"%s\"", json(e.getKey()), json(e.getValue())))
                .collect(Collectors.joining(",\n"));
        return String.format(Locale.ROOT, """
                {
                    "jmhVersion" : "%s",
                    "benchmark" : "%s",
                    "mode" : "footprint",
                    "threads" : 1,
                    "forks" : 0,
                    "jvm" : "%s",
                    "jvmArgs" : [],
                    "jdkVersion" : "%s",
                    "vmName" : "%s",
                    "vmVersion" : "%s",
                    "warmupIterations" : 0,
                    "warmupTime" : "single-shot",
                    "warmupBatchSize" : 1,
                    "measurementIterations" : %d,
                    "measurementTime" : "single-shot",
                    "measurementBatchSize" : 1,
                    "params" : {
                %s
                    },
                    "primaryMetric" : {
                        "score" : %s,
                        "scoreError" : "NaN",
                        "scoreConfidence" : [%s, %s],
                        "scorePercentiles" : {%s},
                        "scoreUnit" : "%s",
                        "rawData" : [[%s]]
                    },
                    "secondaryMetrics" : {}
                }""",
                Version.getPlainVersion(), json(benchmark),
                json(Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                json(System.getProperty("java.version")), json(System.getProperty("java.vm.name")),
                json(System.getProperty("java.vm.version")), samples.length,
                paramsJson,
                number(score), number(samples[0]), number(samples[samples.length - 1]),
                percentiles, json(unit), rawData);
    }

    /** Returns the sample at the percentile, with the nearest-rank method. */
    private static double percentile(double[] samples, double p) {
        int rank = (int) Math.ceil(p / 100 * samples.length);
        return samples[Math.clamp(rank - 1, 0, samples.length - 1)];
    }

    static List<Result> measure(List<String> indices, List<Distribution> distributions, List<Integer> sizes) {
        List<Result> results = new ArrayList<>();
        for (String index : indices) {
            for (Distribution distribution : distributions) {
                for (int size : sizes) {
                    Workload workload = Workload.generate(distribution, size, WorkloadState.SEED);
                    Result result = new Result(index, distribution, size, samples(index, workload));
                    System.out.printf(Locale.ROOT, "Footprint %s %s %d: %.1f B/entity%n",
                            index, distribution, size, result.bytesPerEntity());
                    results.add(result);
                }
            }
        }
        return results;
    }

    /** Returns the bytes per entity of each repetition, sorted. */
    private static double[] samples(String index, Workload workload) {
        double[] samples = new double[REPEAT];
        for (int r = 0; r < REPEAT; r++) {
            long before = usedHeap();
            PartitionNode2 node = new LocalPartitionNode2();
            node.createPartition(WorkloadState.PARTITION_ID, WorkloadState.PARTITION_NAME, IndexNames.parse(index));
            node.claim(WorkloadState.PARTITION_ID, Ownership.INITIAL.generation()).orElseThrow();
            node.insertAll(WorkloadState.WRITE_TOKEN, workload.entities());
            long after = usedHeap();
            Reference.reachabilityFence(node);
            samples[r] = (double) (after - before) / workload.entities().size();
        }
        Arrays.sort(samples);
        return samples;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Escapes a string for a JSON string literal. */
    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Returns the heap in use after some garbage collections, so it has almost only live objects. */
    static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
