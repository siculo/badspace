package badspace.benchmark;

import badspace.common.IndexType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Runs the benchmarks and builds the HTML report. Usage:
 * <pre>
 * java -jar benchmarks.jar run [quick|full] [--include REGEX] [--param NAME=V1,V2] [--percentiles] [--output FILE]
 * java -jar benchmarks.jar report [--output FILE] RESULTS.json...
 * </pre>
 * The quick profile is for a check during development; the full profile is
 * for the measures to keep. Without {@code --output}, the results go to
 * {@code results/<date>-<profile>.json}. The run also writes the report of
 * its results next to them, with the same name and the {@code .html}
 * extension. Without {@code --output}, the report command writes the report
 * next to the last results file, in the same way.
 */
public final class BenchmarkMain {

    /** Values of the parameters and JMH settings of a run. */
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
    }

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

    private static final String USAGE = """
            Usage:
              run [quick|full] [--include REGEX] [--param NAME=V1,V2] [--percentiles] [--output FILE]
              report [--output FILE] RESULTS.json...
            """;

    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            fail("Missing command");
        }
        List<String> rest = List.of(args).subList(1, args.length);
        switch (args[0]) {
            case "run" -> run(rest);
            case "report" -> report(rest);
            default -> fail("Unknown command: " + args[0]);
        }
    }

    private static void run(List<String> args) throws RunnerException, IOException {
        Profile profile = QUICK;
        Pattern include = Pattern.compile("");
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("index", Arrays.stream(IndexType.values()).map(Enum::name).toList());
        boolean percentiles = false;
        Path output = null;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "quick" -> profile = QUICK;
                case "full" -> profile = FULL;
                case "--include" -> include = Pattern.compile(value(args, ++i));
                case "--percentiles" -> percentiles = true;
                case "--output" -> output = Path.of(value(args, ++i));
                case "--param" -> {
                    String[] nameAndValues = value(args, ++i).split("=", 2);
                    if (nameAndValues.length != 2) {
                        fail("Bad parameter: " + args.get(i));
                    }
                    params.put(nameAndValues[0], List.of(nameAndValues[1].split(",")));
                }
                default -> fail("Unknown option: " + args.get(i));
            }
        }
        Map<String, List<String>> allParams = new LinkedHashMap<>(profile.params());
        allParams.putAll(params);
        if (output == null) {
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
            output = Path.of("results", date + "-" + profile.name() + ".json");
        }

        List<RunResult> results = new ArrayList<>();
        List<String> build = matching(BuildBenchmark.class, include);
        if (!build.isEmpty()) {
            ChainedOptionsBuilder options = options(profile, allParams, build)
                    .warmupIterations(profile.buildWarmupIterations())
                    .measurementIterations(profile.buildMeasurementIterations());
            results.addAll(new Runner(options.build()).run());
        }
        List<String> operations = matching(PartitionBenchmark.class, include);
        if (!operations.isEmpty()) {
            ChainedOptionsBuilder options = options(profile, allParams, operations)
                    .warmupIterations(profile.warmupIterations())
                    .warmupTime(profile.warmupTime())
                    .measurementIterations(profile.measurementIterations())
                    .measurementTime(profile.measurementTime());
            if (percentiles) {
                options.mode(Mode.AverageTime).mode(Mode.SampleTime);
            }
            results.addAll(new Runner(options.build()).run());
        }
        List<Footprint.Result> footprints = List.of();
        if (include.matcher(Footprint.BENCHMARK).find()) {
            footprints = Footprint.measure(
                    allParams.get("index").stream().map(IndexType::valueOf).toList(),
                    paramValues(allParams, "distribution", Distribution.values()).stream()
                            .map(Distribution::valueOf).toList(),
                    allParams.get("size").stream().map(Integer::valueOf).toList());
        }
        if (results.isEmpty() && footprints.isEmpty()) {
            fail("No benchmark matches: " + include);
        }
        writeResults(output, results, footprints);
        System.out.println("Results written to " + output.toAbsolutePath());
        writeReport(List.of(output), reportPath(output));
    }

    private static ChainedOptionsBuilder options(Profile profile, Map<String, List<String>> params, List<String> benchmarks) {
        ChainedOptionsBuilder options = new OptionsBuilder()
                .forks(profile.forks())
                // JMH 1.37 uses sun.misc.Unsafe: this hides the warnings of the new JDKs.
                .jvmArgsAppend("--sun-misc-unsafe-memory-access=allow")
                .shouldFailOnError(true);
        for (String benchmark : benchmarks) {
            options.include("^" + Pattern.quote(benchmark) + "$");
        }
        // JMH skips the parameters that a benchmark does not have.
        params.forEach((name, values) -> options.param(name, values.toArray(String[]::new)));
        return options;
    }

    /** Returns the full names of the benchmarks of the class that match the pattern. */
    private static List<String> matching(Class<?> benchmarks, Pattern include) {
        return Arrays.stream(benchmarks.getMethods())
                .filter(m -> m.isAnnotationPresent(Benchmark.class))
                .map(Method::getName)
                .sorted()
                .map(name -> benchmarks.getName() + "." + name)
                .filter(name -> include.matcher(name).find())
                .toList();
    }

    private static List<String> paramValues(Map<String, List<String>> params, String name, Enum<?>[] defaults) {
        return params.getOrDefault(name, Arrays.stream(defaults).map(Enum::name).toList());
    }

    /** Writes the JMH results and the footprints in a single JSON array. */
    private static void writeResults(Path output, List<RunResult> results, List<Footprint.Result> footprints)
            throws IOException {
        List<String> entries = new ArrayList<>();
        if (!results.isEmpty()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            ResultFormatFactory.getInstance(ResultFormatType.JSON, out).writeOut(results);
            String json = bytes.toString(StandardCharsets.UTF_8).strip();
            // Remove the brackets of the array, to add the footprints to it.
            entries.add(json.substring(1, json.length() - 1).strip());
        }
        footprints.forEach(f -> entries.add(f.toJson()));
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, "[\n" + String.join(",\n", entries) + "\n]\n");
    }

    private static void report(List<String> args) throws IOException {
        Path output = null;
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--output")) {
                output = Path.of(value(args, ++i));
            } else {
                inputs.add(Path.of(args.get(i)));
            }
        }
        if (inputs.isEmpty()) {
            fail("Missing result files");
        }
        writeReport(inputs, output != null ? output : reportPath(inputs.get(inputs.size() - 1)));
    }

    /** Returns the path of the report for a results file: same directory and name, extension {@code .html}. */
    private static Path reportPath(Path results) {
        String name = results.getFileName().toString().replaceFirst("\\.json$", "") + ".html";
        return results.resolveSibling(name);
    }

    /** Writes an HTML report with one run for each results file. */
    private static void writeReport(List<Path> inputs, Path output) throws IOException {
        String template;
        try (InputStream in = BenchmarkMain.class.getResourceAsStream("/report-template.html")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> runs = new ArrayList<>();
        for (Path input : inputs) {
            String label = input.getFileName().toString().replaceFirst("\\.json$", "");
            // "</" would close the script element that contains the data.
            String json = Files.readString(input).replace("</", "<\\/");
            runs.add("{\"label\": \"" + label.replace("\\", "\\\\").replace("\"", "\\\"") + "\", \"results\": " + json + "}");
        }
        String html = template.replace("/*DATA*/[]", runs.stream().collect(Collectors.joining(",\n", "[\n", "\n]")));
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, html);
        System.out.println("Report written to " + output.toAbsolutePath());
    }

    private static String value(List<String> args, int i) {
        if (i >= args.size()) {
            fail("Missing value for " + args.get(i - 1));
        }
        return args.get(i);
    }

    private static void fail(String message) {
        System.err.println(message);
        System.err.print(USAGE);
        System.exit(2);
    }
}
