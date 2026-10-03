package badspace.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Runs the benchmarks and builds the HTML report. Usage:
 * <pre>
 * java -jar benchmarks.jar run [quick|full] [--index NAME1,NAME2] [--include REGEX] [--param NAME=V1,V2] [--percentiles] [--output FILE]
 * java -jar benchmarks.jar run --plan FILE
 * java -jar benchmarks.jar report [--output FILE] RESULTS.json...
 * </pre>
 * The quick profile is for a check during development; the full profile is
 * for the measures to keep. {@code --index} chooses the indices to measure,
 * by name (see {@link IndexNames}); it is the same as {@code --param index=...}.
 * Without {@code --output}, the results go to
 * {@code results/<date>-<profile>.json}. The run also writes the report of
 * its results next to them, with the same name and the {@code .html}
 * extension. {@code --plan} does the runs of a plan file instead (see
 * {@link BenchmarkPlan}), and takes no other options. Without
 * {@code --output}, the report command writes the report next to the last
 * results file, in the same way.
 */
public final class BenchmarkMain {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** The directories in the path of the JVM of a result, on Linux or Windows. */
    private static final Pattern JVM_DIRECTORIES = Pattern.compile("(?<=\"jvm\" : \")[^\"]*[/\\\\](?=[^\"/\\\\]*\")");

    /** The first field of each result, with its indentation. */
    private static final Pattern JMH_VERSION = Pattern.compile("(?m)^(\\s*)\"jmhVersion\" :");

    private static final String USAGE = """
            Usage:
              run [quick|full] [--index NAME1,NAME2] [--include REGEX] [--param NAME=V1,V2] [--percentiles] [--output FILE]
              run --plan FILE
              report [--output FILE] RESULTS.json...
            Index names: LINEAR_SCAN, UNIFORM_GRID_<cell size> (for example UNIFORM_GRID_100),
              GRID_QUADTREE_<cell size> or GRID_QUADTREE_<cell size>_<leaf capacity> (for example GRID_QUADTREE_128).
            Default indices: %s
            """.formatted(String.join(",", IndexNames.DEFAULT));

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
        if (args.contains("--plan")) {
            if (args.size() != 2 || !args.get(0).equals("--plan")) {
                fail("--plan takes a file and no other options");
            }
            runPlan(Path.of(args.get(1)));
            return;
        }
        Profile profile = Profile.QUICK;
        Pattern include = Pattern.compile("");
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("index", IndexNames.DEFAULT);
        boolean percentiles = false;
        Path output = null;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "quick" -> profile = Profile.QUICK;
                case "full" -> profile = Profile.FULL;
                case "--index" -> params.put("index", List.of(value(args, ++i).split(",")));
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
        if (output == null) {
            output = Path.of("results", LocalDateTime.now().format(DATE) + "-" + profile.name() + ".json");
        }
        RunOptions run = new RunOptions(null, profile, include, params, percentiles, output);
        try {
            run.check();
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
        }
        execute(run, sourceCommit());
    }

    /** Warns if the jar is older than the sources, and returns the commit to write in the results. */
    private static String sourceCommit() {
        SourceVersion.warnIfJarIsOld();
        String commit = SourceVersion.commit();
        System.out.println("Commit of the code: " + commit);
        return commit;
    }

    /** Checks all the plan, then does its runs and writes the report of each group. */
    private static void runPlan(Path file) throws RunnerException, IOException {
        BenchmarkPlan plan = null;
        try {
            plan = BenchmarkPlan.read(file, LocalDateTime.now().format(DATE));
        } catch (NoSuchFileException e) {
            failPlan(file, "file not found");
        } catch (IllegalArgumentException e) {
            failPlan(file, e.getMessage());
        }
        String commit = sourceCommit();
        int done = 0;
        for (BenchmarkPlan.Group group : plan.groups()) {
            List<Path> outputs = new ArrayList<>();
            for (RunOptions run : group.runs()) {
                done++;
                System.out.printf("== %s run %d of %d: %s (%s) -> %s%n", LocalTime.now().format(TIME),
                        done, plan.runCount(), run.name(), run.profile().name(), run.output());
                execute(run, commit);
                outputs.add(run.output());
            }
            if (group.report() != null) {
                writeReport(outputs, group.report());
            }
        }
        System.out.printf("== %s plan done%n", LocalTime.now().format(TIME));
    }

    /** Runs the benchmarks of the options and writes the results, with the commit of the code, and their report. */
    private static void execute(RunOptions run, String commit) throws RunnerException, IOException {
        Profile profile = run.profile();
        Map<String, List<String>> allParams = run.allParams();
        List<RunResult> results = new ArrayList<>();
        List<String> build = run.buildBenchmarks();
        if (!build.isEmpty()) {
            ChainedOptionsBuilder options = options(profile, allParams, build)
                    .warmupIterations(profile.buildWarmupIterations())
                    .measurementIterations(profile.buildMeasurementIterations());
            results.addAll(new Runner(options.build()).run());
        }
        List<String> operations = run.operationBenchmarks();
        if (!operations.isEmpty()) {
            ChainedOptionsBuilder options = options(profile, allParams, operations)
                    .warmupIterations(profile.warmupIterations())
                    .warmupTime(profile.warmupTime())
                    .measurementIterations(profile.measurementIterations())
                    .measurementTime(profile.measurementTime());
            if (run.percentiles()) {
                options.mode(Mode.AverageTime).mode(Mode.SampleTime);
            }
            results.addAll(new Runner(options.build()).run());
        }
        List<Footprint.Result> footprints = List.of();
        if (run.measuresFootprint()) {
            footprints = Footprint.measure(
                    allParams.get("index"),
                    paramValues(allParams, "distribution", Distribution.values()).stream()
                            .map(Distribution::valueOf).toList(),
                    allParams.get("size").stream().map(Integer::valueOf).toList());
        }
        writeResults(run.output(), results, footprints, commit);
        System.out.println("Results written to " + run.output().toAbsolutePath());
        writeReport(List.of(run.output()), run.reportPath());
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

    private static List<String> paramValues(Map<String, List<String>> params, String name, Enum<?>[] defaults) {
        return params.getOrDefault(name, Arrays.stream(defaults).map(Enum::name).toList());
    }

    /** Writes the JMH results and the footprints in a single JSON array, each with the commit of the code. */
    private static void writeResults(Path output, List<RunResult> results, List<Footprint.Result> footprints,
            String commit) throws IOException {
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
        // Keep only the name of the JVM executable: its path shows the local file system.
        String json = JVM_DIRECTORIES.matcher("[\n" + String.join(",\n", entries) + "\n]\n").replaceAll("");
        // Each entry starts with "jmhVersion": the commit goes before it.
        json = JMH_VERSION.matcher(json)
                .replaceAll("$1\"commit\" : \"" + Matcher.quoteReplacement(commit) + "\",\n$1\"jmhVersion\" :");
        Files.writeString(output, json);
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
        writeReport(inputs, output != null ? output : RunOptions.reportPath(inputs.get(inputs.size() - 1)));
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

    private static void failPlan(Path file, String message) {
        System.err.println("Error in the plan " + file + ": " + message);
        System.exit(2);
    }
}
