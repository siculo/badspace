package badspace.benchmark.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Set;

/** A remote branch tip or tag, and an inline plan in the benchmark runner's format. */
record RunRequest(String branch, String tag, JsonNode plan) {

    static RunRequest parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Request must be an object");
        }
        for (String field : root.propertyStream().map(e -> e.getKey()).toList()) {
            if (!Set.of("branch", "tag", "plan").contains(field)) {
                throw new IllegalArgumentException("Unknown request field: " + field);
            }
        }
        String branch = ref(root, "branch");
        String tag = ref(root, "tag");
        if ((branch == null) == (tag == null)) {
            throw new IllegalArgumentException("Give either branch or tag");
        }
        JsonNode plan = root.get("plan");
        if (plan == null || !plan.isObject() || !plan.path("groups").isArray()
                || plan.path("groups").isEmpty()) {
            throw new IllegalArgumentException("plan.groups must be a non-empty array");
        }
        // Semantic checks belong to the runner built from the requested revision.
        // Check output paths here because the plan must only write in this job's results.
        for (JsonNode group : plan.path("groups")) {
            if (!group.path("runs").isArray() || group.path("runs").isEmpty()) {
                throw new IllegalArgumentException("Each group must have a non-empty runs array");
            }
            for (JsonNode run : group.path("runs")) {
                if (!run.isObject()) {
                    throw new IllegalArgumentException("Each run must be an object");
                }
                if (run.has("output")) {
                    JsonNode output = run.get("output");
                    if (!output.isTextual()) {
                        throw new IllegalArgumentException("output must be a string");
                    }
                    Path path = Path.of(output.textValue()).normalize();
                    if (path.isAbsolute() || !path.startsWith("results") || path.getNameCount() < 2
                            || output.textValue().contains("\\")) {
                        throw new IllegalArgumentException("output must be a relative file under results/: " + output);
                    }
                }
            }
        }
        return new RunRequest(branch, tag, plan.deepCopy());
    }

    String remoteRef() {
        return branch != null ? "refs/heads/" + branch : "refs/tags/" + tag;
    }

    private static String ref(JsonNode root, String field) {
        if (!root.has(field)) {
            return null;
        }
        JsonNode value = root.get(field);
        if (!value.isTextual() || !value.textValue().matches("[A-Za-z0-9_][A-Za-z0-9._/+-]*")
                || value.textValue().contains("..") || value.textValue().contains("//")) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value);
        }
        return value.textValue();
    }
}
