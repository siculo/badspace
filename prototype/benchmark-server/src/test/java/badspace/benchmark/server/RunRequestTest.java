package badspace.benchmark.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RunRequestTest {

    private static final String PLAN = """
            {"groups":[{"runs":[{"name":"memory","output":"results/reference.json"}]}]}
            """;

    @Test
    void acceptsBranchNamesWithSlashesAndPlanComments() throws Exception {
        RunRequest request = RunRequest.parse(RunStore.JSON.readTree("""
                {"branch":"feature/test", "plan": %s, /* a plan comment */ }
                """.formatted(PLAN)));
        assertEquals("refs/heads/feature/test", request.remoteRef());
    }

    @Test
    void acceptsTagNames() throws Exception {
        RunRequest request = RunRequest.parse(RunStore.JSON.readTree("{\"tag\":\"v1.0\",\"plan\":" + PLAN + "}"));
        assertEquals("refs/tags/v1.0", request.remoteRef());
    }

    @Test
    void rejectsAmbiguousAndInvalidSources() {
        for (String source : new String[]{"", "\"branch\":\"main\",\"tag\":\"v1\",",
                "\"branch\":null,", "\"branch\":\"--help\",", "\"branch\":\"x..y\",",
                "\"branch\":\"main\",\"command\":\"echo\","}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RunRequest.parse(RunStore.JSON.readTree("{" + source + "\"plan\":" + PLAN + "}")));
        }
    }

    @Test
    void rejectsOutputPathsOutsideTheRunResults() {
        for (String output : new String[]{"/tmp/result.json", "results/../../other.json", "result.json",
                "results", "C:\\result.json", "results\\result.json"}) {
            var root = RunStore.JSON.createObjectNode();
            root.put("branch", "main");
            root.putObject("plan").putArray("groups").addObject().putArray("runs").addObject()
                    .put("name", "memory").put("output", output);
            assertThrows(IllegalArgumentException.class, () -> RunRequest.parse(root), output);
        }
    }

    @Test
    void rejectsMissingRunsBeforeUpdatingTheRepository() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> RunRequest.parse(RunStore.JSON.readTree("{\"branch\":\"main\",\"plan\":{\"groups\":[{}]}}")));
    }
}
