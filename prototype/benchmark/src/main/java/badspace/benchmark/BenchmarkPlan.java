package badspace.benchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A plan: the runs to do one after the other, read from a JSON file, so that
 * the same measures can be done again without writing all the options. The
 * runs are in groups: each group has a profile, its runs and, if it has a
 * name for it, a report with the results of all its runs. For example:
 * <pre>
 * {
 *   "groups": [
 *     {
 *       "profile": "quick",
 *       "report": "index-comparison",
 *       "runs": [
 *         { "name": "uniform-grid", "index": ["UNIFORM_GRID_50", "UNIFORM_GRID_100"] },
 *         { "name": "coincident", "index": "GRID_QUADTREE_256",
 *           "params": { "distribution": "COINCIDENT", "size": [1000, 100000] },
 *           "include": "update|insert", "percentiles": true },
 *         { "name": "linear", "index": "LINEAR_SCAN", "output": "results/reference-linear-quick.json" }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 * A run has the same options as the run command; only {@code name} is
 * required. Without {@code output}, the results go to
 * {@code results/<date>-<name>-<profile>.json}, and the report of the group
 * to {@code results/<date>-<report>-<profile>.html}. The date is the start of
 * the plan, the same for all the files. A single value can be written
 * without the array, and the numbers with or without quotes. The file can
 * have comments, like in Java, and commas after the last element.
 * <p>
 * Reading a plan checks all of it: the names and values of the parameters,
 * the patterns, and that no two files have the same path. So an error stops
 * the plan before the first run, not after hours.
 */
record BenchmarkPlan(List<Group> groups) {

    /**
     * A group of runs with the same profile.
     *
     * @param report the path of the report with all the runs of the group, or null
     */
    record Group(Profile profile, List<RunOptions> runs, Path report) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            // Keeps the digits of the numbers as they are written: 0.10 stays 0.10.
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** The names of runs and reports are parts of file names. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._+-]+");

    private static final Set<String> PLAN_FIELDS = Set.of("groups");
    private static final Set<String> GROUP_FIELDS = Set.of("profile", "report", "runs");
    private static final Set<String> RUN_FIELDS = Set.of("name", "index", "include", "params", "percentiles", "output");

    /**
     * Reads and checks the plan in the file. The date goes in the names of the
     * files without an output path. Fails with IllegalArgumentException if
     * the plan is not valid.
     */
    static BenchmarkPlan read(Path file, String date) throws IOException {
        return parse(Files.readString(file), date);
    }

    /** Reads and checks a plan from its JSON text. */
    static BenchmarkPlan parse(String json, String date) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bad JSON: " + e.getOriginalMessage()
                    + " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")", e);
        }
        if (root == null) {
            throw new IllegalArgumentException("The plan is empty");
        }
        checkFields(root, "plan", PLAN_FIELDS);
        List<Group> groups = new ArrayList<>();
        JsonNode groupNodes = required(root, "groups", "plan");
        checkNotEmptyArray(groupNodes, "groups");
        for (int g = 0; g < groupNodes.size(); g++) {
            groups.add(group(groupNodes.get(g), "groups[" + g + "]", date));
        }
        BenchmarkPlan plan = new BenchmarkPlan(List.copyOf(groups));
        plan.checkPaths();
        return plan;
    }

    /** Returns the number of runs in all the groups. */
    int runCount() {
        return groups.stream().mapToInt(g -> g.runs().size()).sum();
    }

    private static Group group(JsonNode node, String where, String date) {
        checkFields(node, where, GROUP_FIELDS);
        Profile profile = Profile.QUICK;
        if (node.has("profile")) {
            String name = text(node.get("profile"), where + ".profile");
            try {
                profile = Profile.named(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ".profile: " + e.getMessage(), e);
            }
        }
        Path report = null;
        if (node.has("report")) {
            String name = name(node.get("report"), where + ".report");
            report = Path.of("results", date + "-" + name + "-" + profile.name() + ".html");
        }
        JsonNode runNodes = required(node, "runs", where);
        checkNotEmptyArray(runNodes, where + ".runs");
        List<RunOptions> runs = new ArrayList<>();
        for (int r = 0; r < runNodes.size(); r++) {
            runs.add(run(runNodes.get(r), where + ".runs[" + r + "]", profile, date));
        }
        return new Group(profile, List.copyOf(runs), report);
    }

    private static RunOptions run(JsonNode node, String where, Profile profile, String date) {
        checkFields(node, where, RUN_FIELDS);
        String name = name(required(node, "name", where), where + ".name");
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("index", IndexNames.DEFAULT);
        if (node.has("params")) {
            JsonNode paramNodes = node.get("params");
            if (!paramNodes.isObject()) {
                throw new IllegalArgumentException(where + ".params must be an object");
            }
            for (Map.Entry<String, JsonNode> param : paramNodes.properties()) {
                if (param.getKey().equals("index")) {
                    throw new IllegalArgumentException(where + ".params: give the indices with \"index\" in the run");
                }
                params.put(param.getKey(), values(param.getValue(), where + ".params." + param.getKey()));
            }
        }
        if (node.has("index")) {
            params.put("index", values(node.get("index"), where + ".index"));
        }
        Pattern include = Pattern.compile("");
        if (node.has("include")) {
            String regex = text(node.get("include"), where + ".include");
            try {
                include = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(where + ".include: bad pattern: " + e.getMessage(), e);
            }
        }
        boolean percentiles = false;
        if (node.has("percentiles")) {
            JsonNode value = node.get("percentiles");
            if (!value.isBoolean()) {
                throw new IllegalArgumentException(where + ".percentiles must be true or false");
            }
            percentiles = value.booleanValue();
        }
        Path output = node.has("output")
                ? Path.of(text(node.get("output"), where + ".output"))
                : Path.of("results", date + "-" + name + "-" + profile.name() + ".json");
        RunOptions run = new RunOptions(name, profile, include, params, percentiles, output);
        try {
            run.check();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + " (" + name + "): " + e.getMessage(), e);
        }
        return run;
    }

    /** Fails if two runs or reports write the same file, so a run would delete the results of another one. */
    private void checkPaths() {
        Map<Path, String> writers = new HashMap<>();
        for (Group group : groups) {
            for (RunOptions run : group.runs()) {
                addPath(writers, run.output(), "the results of " + run.name());
                addPath(writers, run.reportPath(), "the report of " + run.name());
            }
            if (group.report() != null) {
                addPath(writers, group.report(), "the report of the group " + group.report().getFileName());
            }
        }
    }

    private static void addPath(Map<Path, String> writers, Path path, String what) {
        String other = writers.putIfAbsent(path.toAbsolutePath().normalize(), what);
        if (other != null) {
            throw new IllegalArgumentException("Same file for " + other + " and " + what + ": " + path);
        }
    }

    private static void checkFields(JsonNode node, String where, Set<String> fields) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(where + " must be an object");
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!fields.contains(field.getKey())) {
                throw new IllegalArgumentException(where + ": unknown field \"" + field.getKey()
                        + "\" (fields: " + String.join(", ", fields.stream().sorted().toList()) + ")");
            }
        }
    }

    private static JsonNode required(JsonNode node, String field, String where) {
        if (!node.has(field)) {
            throw new IllegalArgumentException(where + ": missing field \"" + field + "\"");
        }
        return node.get(field);
    }

    private static void checkNotEmptyArray(JsonNode node, String where) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(where + " must be an array that is not empty");
        }
    }

    private static String text(JsonNode node, String where) {
        if (!node.isTextual()) {
            throw new IllegalArgumentException(where + " must be a string");
        }
        return node.textValue();
    }

    private static String name(JsonNode node, String where) {
        String name = text(node, where);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(where + ": \"" + name
                    + "\" is not a valid name (use letters, digits and . _ + -)");
        }
        return name;
    }

    /** Returns the values of a parameter: one string or number, or an array of them. */
    private static List<String> values(JsonNode node, String where) {
        List<JsonNode> nodes = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(nodes::add);
        } else {
            nodes.add(node);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : nodes) {
            if (!value.isTextual() && !value.isNumber()) {
                throw new IllegalArgumentException(where + ": values must be strings or numbers");
            }
            // The results and the report compare the values as text, so a
            // number is written without exponent: 1e-4 becomes 0.0001.
            values.add(value.isNumber() ? value.decimalValue().toPlainString() : value.textValue());
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(where + ": no values");
        }
        return List.copyOf(values);
    }
}
