package badspace.benchmark;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;

/**
 * The options of one run: the benchmarks to run, with which parameters, and
 * where to write the results. They come from the command line or from a plan.
 *
 * @param name the name of the run in a plan, or null on the command line
 * @param params the parameters given for the run, which replace the values of
 *        the profile; {@code index} is always there
 */
record RunOptions(
        String name,
        Profile profile,
        Pattern include,
        Map<String, List<String>> params,
        boolean percentiles,
        Path output) {

    RunOptions {
        params = Map.copyOf(params);
    }

    /** Returns the values of all the parameters: the ones of the profile, replaced by the ones of the run. */
    Map<String, List<String>> allParams() {
        Map<String, List<String>> all = new LinkedHashMap<>(profile.params());
        all.putAll(params);
        return all;
    }

    /**
     * Fails with IllegalArgumentException if a parameter is not valid, or if
     * no benchmark matches {@link #include}. It only reads the options, so a
     * plan can check all its runs before the first one starts.
     */
    void check() {
        params.forEach(BenchmarkParams::check);
        if (buildBenchmarks().isEmpty() && operationBenchmarks().isEmpty() && !measuresFootprint()) {
            throw new IllegalArgumentException("No benchmark matches: " + include);
        }
    }

    /** Returns the full names of the build benchmarks to run. */
    List<String> buildBenchmarks() {
        return matching(BuildBenchmark.class);
    }

    /** Returns the full names of the operation benchmarks to run. */
    List<String> operationBenchmarks() {
        return matching(PartitionBenchmark.class);
    }

    boolean measuresFootprint() {
        return include.matcher(Footprint.BENCHMARK).find();
    }

    /** Returns the path of the report of the run: next to the results, with the {@code .html} extension. */
    Path reportPath() {
        return reportPath(output);
    }

    /** Returns the path of the report for a results file: same directory and name, extension {@code .html}. */
    static Path reportPath(Path results) {
        String name = results.getFileName().toString().replaceFirst("\\.json$", "") + ".html";
        return results.resolveSibling(name);
    }

    private List<String> matching(Class<?> benchmarks) {
        return Arrays.stream(benchmarks.getMethods())
                .filter(m -> m.isAnnotationPresent(Benchmark.class))
                .map(Method::getName)
                .sorted()
                .map(name -> benchmarks.getName() + "." + name)
                .filter(name -> include.matcher(name).find())
                .toList();
    }
}
