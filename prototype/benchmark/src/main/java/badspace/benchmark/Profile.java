package badspace.benchmark;

import java.util.List;
import java.util.Map;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Values of the parameters and JMH settings of a run. The quick profile is for
 * a check during development; the full profile is for the measures to keep.
 */
record Profile(
        String name,
        Map<String, List<String>> params,
        int forks,
        int warmupIterations,
        TimeValue warmupTime,
        int measurementIterations,
        TimeValue measurementTime,
        int buildWarmupIterations,
        int buildMeasurementIterations) {

    static final Profile QUICK = new Profile(
            "quick",
            Map.of(
                    "size", List.of("1000", "100000"),
                    "batchSize", List.of("1", "100"),
                    "selectivity", List.of("0.001", "0.01"),
                    "k", List.of("1", "10"),
                    "shape", List.of("BOX")),
            1, 2, TimeValue.milliseconds(500), 3, TimeValue.milliseconds(500), 5, 10);

    static final Profile FULL = new Profile(
            "full",
            Map.of(
                    "size", List.of("1000", "10000", "100000", "1000000"),
                    "batchSize", List.of("1", "10", "100", "1000"),
                    "selectivity", List.of("0.0001", "0.001", "0.01", "0.1"),
                    "k", List.of("1", "10", "100"),
                    "shape", List.of("BOX", "CIRCLE")),
            1, 3, TimeValue.seconds(1), 5, TimeValue.seconds(1), 10, 20);

    /** Returns the profile with the name. Fails with IllegalArgumentException if there is none. */
    static Profile named(String name) {
        return switch (name) {
            case "quick" -> QUICK;
            case "full" -> FULL;
            default -> throw new IllegalArgumentException("Unknown profile: " + name + " (use quick or full)");
        };
    }
}
