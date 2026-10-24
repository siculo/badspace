package badspace.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the measures of the space of the persistence: the size of the real file, the
 * samples of the memory of the copy with their units and parameters, and that the
 * directories of the measures are deleted. The values of the heap are not checked: they
 * depend on the collector.
 */
class PersistenceSizesTest {

    private static final int SIZE = 100;
    private static final JsonMapper MAPPER = new JsonMapper();

    @TempDir
    private Path runDirectory;

    /** Returns the results by benchmark name and metadata set, for example {@code PersistenceSizes2.fileBytes NONE}. */
    private Map<String, JsonNode> measure(List<String> benchmarks) throws Exception {
        List<String> json = PersistenceSizes.measure(benchmarks, List.of(SIZE), List.of(MetadataSet.values()), runDirectory);
        Map<String, JsonNode> results = new HashMap<>();
        for (String result : json) {
            JsonNode node = MAPPER.readTree(result);
            String benchmark = node.get("benchmark").textValue();
            String name = benchmark.substring(benchmark.lastIndexOf('.', benchmark.lastIndexOf('.') - 1) + 1);
            results.put(name + " " + node.get("params").get("metadata").textValue(), node);
        }
        return results;
    }

    /**
     * Returns the size of a save in the format: the header with the name "benchmark" (33
     * bytes), the count of the entities, the IDs and the coordinates, the table of the keys,
     * the pairs of each entity with their count, and the CRC.
     */
    private static long expectedFileBytes(int dimensions, MetadataSet metadata) {
        long keyTable = Integer.BYTES + metadata.keys().stream().mapToLong(k -> Integer.BYTES + k.length()).sum();
        long perEntity = Long.BYTES + dimensions * Double.BYTES + Integer.BYTES
                + metadata.keys().size() * (Integer.BYTES + Long.BYTES);
        return 33 + Integer.BYTES + keyTable + SIZE * perEntity + Integer.BYTES;
    }

    @Test
    void fileBytesAreTheSizeOfTheSaveFile() throws Exception {
        Map<String, JsonNode> results = measure(PersistenceSizes.BENCHMARKS.stream().filter(b -> b.contains(".file")).toList());
        assertEquals(8, results.size());
        for (int dimensions = 2; dimensions <= 3; dimensions++) {
            for (MetadataSet metadata : MetadataSet.values()) {
                JsonNode total = results.get("PersistenceSizes" + dimensions + ".fileBytes " + metadata).get("primaryMetric");
                JsonNode perEntity = results.get("PersistenceSizes" + dimensions + ".fileBytesPerEntity " + metadata)
                        .get("primaryMetric");
                assertEquals(expectedFileBytes(dimensions, metadata), total.get("score").doubleValue());
                assertEquals("B", total.get("scoreUnit").textValue());
                assertEquals(total.get("score").doubleValue() / SIZE, perEntity.get("score").doubleValue(), 0.001);
                assertEquals("B/entity", perEntity.get("scoreUnit").textValue());
            }
        }
    }

    @Test
    void copyBytesKeepAllTheSamples() throws Exception {
        Map<String, JsonNode> results = measure(List.of(
                "badspace.benchmark.PersistenceSizes3.copyBytes", "badspace.benchmark.PersistenceSizes3.copyBytesPerEntity"));
        assertEquals(4, results.size());
        for (MetadataSet metadata : MetadataSet.values()) {
            JsonNode result = results.get("PersistenceSizes3.copyBytes " + metadata);
            assertEquals("footprint", result.get("mode").textValue());
            assertEquals(Integer.toString(SIZE), result.get("params").get("size").textValue());
            JsonNode metric = result.get("primaryMetric");
            assertEquals("B", metric.get("scoreUnit").textValue());
            assertEquals(PersistenceSizes.REPEAT, metric.get("rawData").get(0).size());
            assertEquals(metric.get("rawData").get(0).get(PersistenceSizes.REPEAT / 2).doubleValue(),
                    metric.get("score").doubleValue());
            assertEquals("B/entity", results.get("PersistenceSizes3.copyBytesPerEntity " + metadata)
                    .get("primaryMetric").get("scoreUnit").textValue());
        }
    }

    @Test
    void measuresDeleteTheirDirectories() throws Exception {
        measure(PersistenceSizes.BENCHMARKS);
        try (Stream<Path> files = Files.list(runDirectory)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void smallPartitionsKeepManyCopiesInASample() {
        assertEquals(100, PersistenceSizes.copyCount(1000));
        assertEquals(1, PersistenceSizes.copyCount(100_000));
        assertTrue(PersistenceSizes.copyCount(1_000_000) >= 1);
    }
}
