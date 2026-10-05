package badspace.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BenchmarkPlanTest {

    private static final String DATE = "2026-10-03-1200";

    @Test
    void readsGroupsAndRuns() {
        BenchmarkPlan plan = BenchmarkPlan.parse("""
                {
                  // Comments and commas after the last element are allowed.
                  "groups": [
                    {
                      "profile": "quick",
                      "report": "comparison",
                      "runs": [
                        { "name": "grid", "index": ["UNIFORM_GRID_50", "UNIFORM_GRID_100"], },
                        { "name": "coincident", "index": "GRID_QUADTREE_256",
                          "params": { "distribution": "COINCIDENT", "size": [1000, 100000], "selectivity": 1e-4,
                                      "step": [1, 2.5, 40] },
                          "include": "update|insert", "percentiles": true },
                        { "name": "linear", "index": "LINEAR_SCAN", "output": "results/reference-linear-quick.json" },
                      ],
                    },
                    { "profile": "full", "runs": [ { "name": "grid" } ] }
                  ]
                }
                """, DATE);

        assertEquals(2, plan.groups().size());
        assertEquals(4, plan.runCount());
        BenchmarkPlan.Group quick = plan.groups().get(0);
        assertEquals(Profile.QUICK, quick.profile());
        assertEquals(Path.of("results", DATE + "-comparison-quick.html"), quick.report());

        RunOptions grid = quick.runs().get(0);
        assertEquals("grid", grid.name());
        assertEquals(Map.of("index", List.of("UNIFORM_GRID_50", "UNIFORM_GRID_100")), grid.params());
        assertFalse(grid.percentiles());
        assertEquals(Path.of("results", DATE + "-grid-quick.json"), grid.output());

        RunOptions coincident = quick.runs().get(1);
        assertEquals(List.of("GRID_QUADTREE_256"), coincident.params().get("index"));
        assertEquals(List.of("COINCIDENT"), coincident.params().get("distribution"));
        assertEquals(List.of("1000", "100000"), coincident.params().get("size"));
        assertEquals(List.of("0.0001"), coincident.params().get("selectivity"));
        assertEquals(List.of("1", "2.5", "40"), coincident.params().get("step"));
        assertEquals(List.of("1000", "100000"), coincident.allParams().get("size"));
        assertEquals(Profile.QUICK.params().get("k"), coincident.allParams().get("k"));
        assertEquals("update|insert", coincident.include().pattern());
        assertTrue(coincident.percentiles());

        assertEquals(Path.of("results/reference-linear-quick.json"), quick.runs().get(2).output());

        BenchmarkPlan.Group full = plan.groups().get(1);
        assertEquals(Profile.FULL, full.profile());
        assertNull(full.report());
        RunOptions defaults = full.runs().get(0);
        assertEquals(Map.of("index", IndexNames.DEFAULT), defaults.params());
        assertEquals(Path.of("results", DATE + "-grid-full.json"), defaults.output());
    }

    @Test
    void rejectsPlansThatAreNotValid() {
        String[] plans = {
                "",
                "{",
                "[]",
                "{}",
                "{ \"groups\": [] }",
                "{ \"groups\": [ { \"runs\": [] } ] }",
                "{ \"groups\": [], \"group\": [] }",
                "{ \"groups\": [ { \"profile\": \"slow\", \"runs\": [ { \"name\": \"a\" } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"index\": \"LINEAR_SCAN\" } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a/b\" } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"name\": \"b\" } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"indices\": \"LINEAR_SCAN\" } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"index\": \"QUADTREE\" } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"index\": [] } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"params\": { \"index\": \"LINEAR_SCAN\" } } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"params\": { \"sizes\": 1000 } } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"params\": { \"size\": 1000.5 } } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"params\": { \"distribution\": \"RANDOM\" } } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"params\": { \"size\": true } } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"params\": { \"step\": 0 } } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"params\": { \"step\": -10 } } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"params\": { \"step\": \"Infinity\" } } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"include\": \"(\" } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"include\": \"nothing\" } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"percentiles\": \"yes\" } ] } ] }",
        };
        for (String plan : plans) {
            assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(plan, DATE), plan);
        }
    }

    @Test
    void rejectsTwoFilesWithTheSamePath() {
        String[] plans = {
                // Two runs with the same name in the same profile.
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\" }, { \"name\": \"a\" } ] } ] }",
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\" } ] }, { \"runs\": [ { \"name\": \"a\" } ] } ] }",
                // The report of the group is the report of a run.
                "{ \"groups\": [ { \"report\": \"a\", \"runs\": [ { \"name\": \"a\" } ] } ] }",
                // Two outputs that are the same file.
                "{ \"groups\": [ { \"runs\": [ { \"name\": \"a\", \"output\": \"results/x.json\" },"
                        + " { \"name\": \"b\", \"output\": \"results/../results/x.json\" } ] } ] }",
        };
        for (String plan : plans) {
            assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(plan, DATE), plan);
        }
    }

    @Test
    void sameRunNamesInDifferentProfilesAreValid() {
        BenchmarkPlan plan = BenchmarkPlan.parse("""
                { "groups": [
                  { "profile": "quick", "report": "all", "runs": [ { "name": "a" } ] },
                  { "profile": "full", "report": "all", "runs": [ { "name": "a" } ] }
                ] }
                """, DATE);
        assertEquals(2, plan.runCount());
    }

    @Test
    void plansInTheRepositoryAreValid() throws Exception {
        try (var files = Files.list(Path.of("plans"))) {
            List<Path> plans = files.filter(p -> p.toString().endsWith(".json")).toList();
            assertFalse(plans.isEmpty());
            for (Path plan : plans) {
                BenchmarkPlan.read(plan, DATE);
            }
        }
    }
}
