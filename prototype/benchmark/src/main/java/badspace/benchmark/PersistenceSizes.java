package badspace.benchmark;

import badspace.common.partition.SavedPartition;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.partition.LocalPartitionNode3;
import badspace.service.partition.NodeCopies;
import badspace.service.persistence.SaveDirectory;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Measures the space of the persistence, for the partitions of {@link PersistenceWorkload}
 * in 2D and 3D. JMH does not measure space, so this class does it in the process of the
 * runner, and writes the results in the format of the JMH results, like {@link Footprint}:
 * <ul>
 *   <li>{@code fileBytes} and {@code fileBytesPerEntity}: the size of the complete file of a
 *       save, written by the node, read from the file system. It is exact.</li>
 *   <li>{@code copyBytes} and {@code copyBytesPerEntity}: an estimate of the memory that the
 *       autonomous copy of a snapshot keeps, that is the heap in use with the copies minus
 *       the heap in use without them, both after a garbage collection.</li>
 * </ul>
 * For the copy, the source partition and its snapshot stay alive in both observations, so
 * they are not counted, and the garbage collection removes the temporary objects of the
 * copy. Each sample keeps {@link #copyCount} copies at the same time and divides by their
 * number, so the noise of the heap counts less for small partitions. The copy owns its
 * arrays, the list, the immutable maps of the metadata (the ones of {@code Map.copyOf} in
 * {@code ApplicationMetadata}: the hash maps read from the snapshot are temporary) and the
 * boxed values; it shares the strings of the keys with the snapshot, and an entity without
 * metadata has the same empty map of the JDK as all the others. The score is the median of {@link #REPEAT}
 * samples, all kept in the results: they can differ, or even be negative, when the
 * collector does not free the same memory each time.
 */
final class PersistenceSizes {

    /** The names of the measures in the JSON results, for 2D and then for 3D. */
    static final List<String> BENCHMARKS = List.of(
            "badspace.benchmark.PersistenceSizes2.fileBytes",
            "badspace.benchmark.PersistenceSizes2.fileBytesPerEntity",
            "badspace.benchmark.PersistenceSizes2.copyBytes",
            "badspace.benchmark.PersistenceSizes2.copyBytesPerEntity",
            "badspace.benchmark.PersistenceSizes3.fileBytes",
            "badspace.benchmark.PersistenceSizes3.fileBytesPerEntity",
            "badspace.benchmark.PersistenceSizes3.copyBytes",
            "badspace.benchmark.PersistenceSizes3.copyBytesPerEntity");

    /** Number of samples of the memory of the copy. */
    static final int REPEAT = 5;

    /** Number of entities in the copies of a sample, at least: they reduce the noise of the heap. */
    static final int COPY_ENTITIES = 100_000;

    /** A sample with a spread larger than this share of the median makes the measure unstable. */
    private static final double UNSTABLE_SPREAD = 0.1;

    private PersistenceSizes() {
    }

    /**
     * Makes the measures with the given names, for all the sizes and the metadata sets, with
     * the partitions in new directories inside the directory of the run, which it deletes at
     * the end. Returns the results as JSON objects in the format of the JMH results.
     */
    static List<String> measure(List<String> benchmarks, List<Integer> sizes, List<MetadataSet> metadataSets,
            Path runDirectory) throws IOException {
        List<String> results = new ArrayList<>();
        for (int dimensions = 2; dimensions <= 3; dimensions++) {
            String prefix = "badspace.benchmark.PersistenceSizes" + dimensions + ".";
            boolean file = benchmarks.stream().anyMatch(b -> b.startsWith(prefix + "file"));
            boolean copy = benchmarks.stream().anyMatch(b -> b.startsWith(prefix + "copy"));
            if (!file && !copy) {
                continue;
            }
            for (int size : sizes) {
                for (MetadataSet metadata : metadataSets) {
                    Path directory = Files.createTempDirectory(runDirectory, "sizes-");
                    try {
                        Map<String, double[]> samples = dimensions == 2
                                ? measure2(directory, size, metadata, file, copy)
                                : measure3(directory, size, metadata, file, copy);
                        Map<String, String> params = new LinkedHashMap<>();
                        params.put("metadata", metadata.name());
                        params.put("size", Integer.toString(size));
                        for (Map.Entry<String, double[]> e : samples.entrySet()) {
                            String benchmark = prefix + e.getKey();
                            if (benchmarks.contains(benchmark)) {
                                results.add(result(benchmark, params, e.getValue()));
                            }
                        }
                    } finally {
                        SaveDestination.delete(directory);
                    }
                }
            }
        }
        return results;
    }

    private static Map<String, double[]> measure2(Path directory, int size, MetadataSet metadata,
            boolean file, boolean copy) throws IOException {
        LocalPartitionNode2 node = PersistenceWorkload.publishedNode2(directory, size, metadata);
        Map<String, double[]> samples = new LinkedHashMap<>();
        if (file) {
            fileSamples(directory, node.saveLatest(WorkloadState.PARTITION_ID), size, samples);
        }
        if (copy) {
            copySamples(() -> NodeCopies.copy(node, WorkloadState.PARTITION_ID, PersistenceWorkload.SNAPSHOT_TICK),
                    size, samples);
        }
        Reference.reachabilityFence(node);
        return samples;
    }

    private static Map<String, double[]> measure3(Path directory, int size, MetadataSet metadata,
            boolean file, boolean copy) throws IOException {
        LocalPartitionNode3 node = PersistenceWorkload.publishedNode3(directory, size, metadata);
        Map<String, double[]> samples = new LinkedHashMap<>();
        if (file) {
            fileSamples(directory, node.saveLatest(WorkloadState.PARTITION_ID), size, samples);
        }
        if (copy) {
            copySamples(() -> NodeCopies.copy(node, WorkloadState.PARTITION_ID, PersistenceWorkload.SNAPSHOT_TICK),
                    size, samples);
        }
        Reference.reachabilityFence(node);
        return samples;
    }

    /** Adds the size of the file of the save, in total and per entity. */
    private static void fileSamples(Path directory, SavedPartition saved, int size, Map<String, double[]> samples)
            throws IOException {
        long bytes = Files.size(new SaveDirectory(directory).saveFile(saved.name(), saved.tick()));
        samples.put("fileBytes", new double[] {bytes});
        samples.put("fileBytesPerEntity", new double[] {(double) bytes / size});
    }

    /** Adds the samples of the memory of a copy, in total and per entity, each sorted. */
    private static void copySamples(Supplier<Object> copy, int size, Map<String, double[]> samples) {
        Object[] copies = new Object[copyCount(size)];
        // The first copy loads and initializes the classes that it uses, outside the samples.
        Reference.reachabilityFence(copy.get());
        double[] bytes = new double[REPEAT];
        for (int r = 0; r < REPEAT; r++) {
            Arrays.fill(copies, null);
            long before = Footprint.usedHeap();
            for (int i = 0; i < copies.length; i++) {
                copies[i] = copy.get();
            }
            long after = Footprint.usedHeap();
            Reference.reachabilityFence(copies);
            bytes[r] = (double) (after - before) / copies.length;
        }
        Arrays.fill(copies, null);
        Arrays.sort(bytes);
        samples.put("copyBytes", bytes);
        samples.put("copyBytesPerEntity", Arrays.stream(bytes).map(b -> b / size).toArray());
    }

    /** Returns the number of copies that a sample keeps at the same time for partitions of the size. */
    static int copyCount(int size) {
        return Math.max(1, COPY_ENTITIES / Math.max(1, size));
    }

    /** Returns the result of a measure, and prints it, with a warning if its samples are unstable. */
    private static String result(String benchmark, Map<String, String> params, double[] samples) {
        double median = samples[samples.length / 2];
        String unit = benchmark.endsWith("PerEntity") ? "B/entity" : "B";
        String name = benchmark.substring(benchmark.lastIndexOf('.', benchmark.lastIndexOf('.') - 1) + 1);
        System.out.printf(Locale.ROOT, "%s %s: %.1f %s%n", name, params, median, unit);
        if (samples.length > 1 && (samples[0] <= 0
                || samples[samples.length - 1] - samples[0] > UNSTABLE_SPREAD * Math.abs(median))) {
            System.out.printf(Locale.ROOT, "WARNING: unstable samples for %s %s: %s%n", name, params,
                    Arrays.stream(samples).mapToObj(s -> String.format(Locale.ROOT, "%.1f", s))
                            .collect(Collectors.joining(", ")));
        }
        return Footprint.toJson(benchmark, params, unit, median, samples);
    }
}
