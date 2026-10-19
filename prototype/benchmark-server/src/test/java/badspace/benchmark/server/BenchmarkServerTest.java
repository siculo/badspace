package badspace.benchmark.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses real Git repositories, Maven builds, HTTP requests, and parent/child JVMs. */
class BenchmarkServerTest {

    @TempDir
    Path temp;
    private Path origin;
    private Path repository;
    private Path results;
    private String latestCommit;
    private String tagCommit;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void createRepository() throws Exception {
        origin = temp.resolve("origin");
        repository = temp.resolve("benchmark clone");
        results = temp.resolve("server results");
        Files.createDirectories(origin.resolve("prototype/benchmark/src/main/java"));
        Files.writeString(origin.resolve("prototype/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>root</artifactId><version>1</version>
                  <packaging>pom</packaging><modules><module>benchmark</module></modules>
                </project>
                """);
        Files.writeString(origin.resolve("prototype/benchmark/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>benchmark</artifactId><version>1</version>
                  <properties><maven.compiler.release>25</maven.compiler.release></properties>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version>
                      <executions><execution><phase>package</phase><goals><goal>shade</goal></goals>
                        <configuration><finalName>benchmarks</finalName><createDependencyReducedPom>false</createDependencyReducedPom>
                          <transformers><transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                            <mainClass>FixtureBenchmark</mainClass>
                          </transformer></transformers>
                        </configuration>
                      </execution></executions>
                    </plugin>
                  </plugins></build>
                </project>
                """);
        writeRunner("tag");
        git(origin, "init", "-b", "main");
        git(origin, "config", "user.email", "test@example.invalid");
        git(origin, "config", "user.name", "Benchmark test");
        git(origin, "add", ".");
        git(origin, "commit", "-m", "Tag revision");
        tagCommit = git(origin, "rev-parse", "HEAD").strip();
        git(origin, "tag", "v1");
        writeRunner("branch");
        git(origin, "add", ".");
        git(origin, "commit", "-m", "Branch revision");
        latestCommit = git(origin, "rev-parse", "HEAD").strip();
        git(temp, "clone", origin.toString(), repository.toString());
    }

    @Test
    void fetchesLatestBranchBuildsRunsAndKeepsDownloadableResultsAfterRestart() throws Exception {
        // Move the local clone back: the service must fetch and select the current remote tip.
        git(repository, "checkout", "--detach", "v1");
        String id;
        try (BenchmarkServer server = start()) {
            assertEquals(200, request(server, "/", "GET", null, false).statusCode());
            HttpResponse<String> accepted = submit(server, "branch", "main", "success");
            assertEquals(202, accepted.statusCode(), accepted.body());
            id = tree(accepted).path("id").asText();
            awaitFinished(server, id);
            JsonNode view = tree(request(server, "/api/runs/" + id, "GET", null, true));
            assertEquals("SUCCEEDED", view.path("run").path("state").asText(), view.toString());
            assertEquals(latestCommit, view.path("run").path("commit").asText());
            assertEquals(latestCommit, git(repository, "rev-parse", "HEAD").strip());
            assertTrue(view.path("artifacts").toString().contains("results/result.json"));
            HttpResponse<String> artifact = request(server, "/api/runs/" + id + "/files/results/result.json", "GET", null, true);
            assertEquals(200, artifact.statusCode());
            assertEquals("branch", tree(artifact).path("revision").asText());
            assertFalse(Files.exists(repository.resolve("results")));
            assertEquals(404, request(server, "/api/runs/" + id + "/files/results/../../status.json", "GET", null, true).statusCode());
        }
        try (BenchmarkServer server = start()) {
            JsonNode history = tree(request(server, "/api/runs", "GET", null, true));
            assertEquals(id, history.get(0).path("run").path("id").asText());
            assertEquals("SUCCEEDED", history.get(0).path("run").path("state").asText());
        }
    }

    @Test
    void servesReportsAndJsonWithAResultsCookieButKeepsOtherFilesAndApiPrivate() throws Exception {
        String cookie;
        String id;
        try (BenchmarkServer server = start()) {
            id = tree(submit(server, "branch", "main", "success")).path("id").asText();
            awaitFinished(server, id);
            HttpResponse<String> history = request(server, "/api/runs", "GET", null, true);
            String setCookie = history.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(setCookie.contains("HttpOnly"));
            assertTrue(setCookie.contains("SameSite=Strict"));
            cookie = setCookie.split(";", 2)[0];
            String files = "/api/runs/" + id + "/files/";
            HttpResponse<String> report = cookieRequest(server, files + "results/result.html", "GET", cookie);
            assertEquals(200, report.statusCode());
            assertEquals("<html>Benchmark results</html>", report.body());
            assertEquals("text/html; charset=utf-8", report.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(report.headers().firstValue("Content-Disposition").isEmpty());
            assertTrue(report.headers().firstValue("Content-Security-Policy").orElseThrow().contains("sandbox allow-scripts"));
            HttpResponse<String> json = cookieRequest(server, files + "results/result.json", "GET", cookie);
            assertEquals(200, json.statusCode());
            assertEquals("attachment", json.headers().firstValue("Content-Disposition").orElseThrow());
            assertEquals(401, request(server, files + "results/result.html", "GET", null, false).statusCode());
            assertEquals(401, cookieRequest(server, files + "results/result.html", "GET", "benchmark_results=invalid").statusCode());
            assertEquals(401, cookieRequest(server, "/api/runs", "GET", cookie).statusCode());
            assertEquals(401, cookieRequest(server, "/api/runs/" + id + "/cancel", "POST", cookie).statusCode());
            Files.writeString(results.resolve(id).resolve("results/private.txt"), "private");
            for (String name : List.of("plan.json", "run.log", "status.json", "results/private.txt", "results/../plan.json")) {
                assertEquals(404, cookieRequest(server, files + name, "GET", cookie).statusCode(), name);
            }
            assertEquals(404, request(server, "/results/" + id + "/results/result.html", "GET", null, true).statusCode());
            JsonNode view = tree(request(server, "/api/runs/" + id, "GET", null, true));
            assertEquals(2, view.path("artifacts").size());
        }
        try (BenchmarkServer server = start()) {
            assertEquals(401, cookieRequest(server, "/api/runs/" + id + "/files/results/result.html", "GET", cookie).statusCode());
        }
    }

    @Test
    void measuresTheRequestedTagInsteadOfTheBranchTip() throws Exception {
        try (BenchmarkServer server = start()) {
            HttpResponse<String> accepted = submit(server, "tag", "v1", "success");
            String id = tree(accepted).path("id").asText();
            awaitFinished(server, id);
            JsonNode view = tree(request(server, "/api/runs/" + id, "GET", null, true));
            assertEquals("SUCCEEDED", view.path("run").path("state").asText(), view.toString());
            assertEquals(tagCommit, view.path("run").path("commit").asText());
            assertEquals("tag", tree(request(server, "/api/runs/" + id + "/files/results/result.json", "GET", null, true))
                    .path("revision").asText());
        }
    }

    @Test
    void buildFailureDoesNotLaunchTheBenchmark() throws Exception {
        Files.writeString(origin.resolve("prototype/benchmark/src/main/java/Broken.java"), "invalid Java");
        git(origin, "add", ".");
        git(origin, "commit", "-m", "Broken build");
        try (BenchmarkServer server = start()) {
            String id = tree(submit(server, "branch", "main", "success")).path("id").asText();
            awaitFinished(server, id);
            JsonNode view = tree(request(server, "/api/runs/" + id, "GET", null, true));
            assertEquals("FAILED", view.path("run").path("state").asText());
            assertFalse(Files.exists(results.resolve(id).resolve("results/result.json")));
            assertTrue(Files.readString(results.resolve(id).resolve("run.log")).contains("BUILD FAILURE"));
        }
    }

    @Test
    void rejectsDirtyCloneWithoutOverwritingLocalChanges() throws Exception {
        Path source = repository.resolve("prototype/benchmark/src/main/java/FixtureBenchmark.java");
        Files.writeString(source, "local changes");
        try (BenchmarkServer server = start()) {
            String id = tree(submit(server, "branch", "main", "success")).path("id").asText();
            awaitFinished(server, id);
            JsonNode view = tree(request(server, "/api/runs/" + id, "GET", null, true));
            assertEquals("FAILED", view.path("run").path("state").asText());
            assertTrue(view.path("run").path("message").asText().contains("local changes"));
            assertEquals("local changes", Files.readString(source));
        }
    }

    @Test
    void cancellationStopsForkedJvmBeforeAnotherRunCanStart() throws Exception {
        try (BenchmarkServer server = start()) {
            String id = tree(submit(server, "branch", "main", "block")).path("id").asText();
            Path job = results.resolve(id);
            Path childPid = job.resolve("results/child.pid");
            await(job, () -> Files.exists(childPid));
            long pid = Long.parseLong(Files.readString(childPid));
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
            assertEquals(409, submit(server, "branch", "main", "success").statusCode());
            assertEquals(202, request(server, "/api/runs/" + id + "/cancel", "POST", "", true).statusCode());
            awaitFinished(server, id);
            assertEquals("CANCELLED", tree(request(server, "/api/runs/" + id, "GET", null, true))
                    .path("run").path("state").asText());
            ProcessHandle child = ProcessHandle.of(pid).orElse(null);
            if (child != null) {
                child.onExit().get(10, TimeUnit.SECONDS);
                assertFalse(child.isAlive());
            }
            assertEquals(202, submit(server, "branch", "main", "success").statusCode());
        }
    }

    @Test
    void benchmarkFailureKeepsItsLogAndDoesNotReportSuccess() throws Exception {
        try (BenchmarkServer server = start()) {
            String id = tree(submit(server, "branch", "main", "fail")).path("id").asText();
            awaitFinished(server, id);
            JsonNode view = tree(request(server, "/api/runs/" + id, "GET", null, true));
            assertEquals("FAILED", view.path("run").path("state").asText());
            assertTrue(view.path("run").path("message").asText().contains("code 7"));
            assertTrue(request(server, "/api/runs/" + id + "/log", "GET", null, true).body().contains("Fixture run failed"));
            assertFalse(Files.exists(results.resolve(id).resolve("results/result.json")));
        }
    }

    @Test
    void serviceShutdownStopsTheActiveForkAndSavesCancellation() throws Exception {
        BenchmarkServer server = start();
        String id;
        long pid;
        try {
            id = tree(submit(server, "branch", "main", "block")).path("id").asText();
            Path job = results.resolve(id);
            Path childPid = job.resolve("results/child.pid");
            await(job, () -> Files.exists(childPid));
            pid = Long.parseLong(Files.readString(childPid));
        } finally {
            server.close();
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        try (BenchmarkServer restarted = start()) {
            assertEquals("CANCELLED", tree(request(restarted, "/api/runs/" + id, "GET", null, true))
                    .path("run").path("state").asText());
        }
    }

    @Test
    void authenticatesApiAndRejectsCrossOriginRequests() throws Exception {
        try (BenchmarkServer server = start()) {
            assertEquals(401, request(server, "/api/runs", "GET", null, false).statusCode());
            assertEquals(200, request(server, "/api/runs", "GET", null, true).statusCode());
            var crossOrigin = HttpRequest.newBuilder(uri(server, "/api/runs"))
                    .header("Authorization", "Bearer test-token").header("Origin", "http://another-host").GET().build();
            assertEquals(403, client.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, request(server, "/api/runs", "POST", "{\"unexpected\":1}", true).statusCode());
            assertEquals(400, request(server, "/api/runs", "POST", "{} {}", true).statusCode());
            assertEquals(404, request(server, "/api/runs/missing", "GET", null, true).statusCode());
            assertEquals(405, request(server, "/api/runs", "DELETE", null, true).statusCode());
        }
    }

    @Test
    void recoversUnfinishedMetadataAsInterrupted() throws Exception {
        Files.createDirectories(results.resolve("11111111-1111-1111-1111-111111111111/results"));
        var request = RunRequest.parse(RunStore.JSON.readTree(body("branch", "main", "success")));
        var run = new RunStore.Run("11111111-1111-1111-1111-111111111111", RunStore.State.RUNNING,
                "2026-10-18T10:00:00Z", null, latestCommit, "Running", request);
        RunStore.JSON.writeValue(results.resolve(run.id()).resolve("status.json").toFile(), run);
        try (BenchmarkServer server = start()) {
            JsonNode view = tree(request(server, "/api/runs/" + run.id(), "GET", null, true));
            assertEquals("INTERRUPTED", view.path("run").path("state").asText());
            assertFalse(view.path("run").path("finished").isNull());
        }
    }

    @Test
    void preventsTwoServicesFromUsingTheSameClone() throws Exception {
        try (BenchmarkServer server = start()) {
            ServerConfig other = config(temp.resolve("other results"));
            assertThrows(Exception.class, () -> new BenchmarkServer(other));
            assertEquals(200, request(server, "/api/runs", "GET", null, true).statusCode());
        }
    }

    private BenchmarkServer start() throws IOException {
        BenchmarkServer server = new BenchmarkServer(config(results));
        server.start();
        return server;
    }

    private ServerConfig config(Path output) {
        String maven = System.getProperty("os.name").startsWith("Windows") ? "mvn.cmd" : "mvn";
        return new ServerConfig("127.0.0.1", 0, repository, output, "git", maven,
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "test-token");
    }

    private HttpResponse<String> submit(BenchmarkServer server, String type, String revision, String name) throws Exception {
        return request(server, "/api/runs", "POST", body(type, revision, name), true);
    }

    private static String body(String type, String revision, String name) {
        return "{\"" + type + "\":\"" + revision + "\",\"plan\":{\"groups\":[{\"runs\":[{\"name\":\"" + name + "\"}]}]}}";
    }

    private HttpResponse<String> request(BenchmarkServer server, String path, String method, String body, boolean token)
            throws Exception {
        var builder = HttpRequest.newBuilder(uri(server, path)).timeout(Duration.ofSeconds(15));
        if (token) {
            builder.header("Authorization", "Bearer test-token");
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(BenchmarkServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpResponse<String> cookieRequest(BenchmarkServer server, String path, String method, String cookie)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(server, path)).timeout(Duration.ofSeconds(15))
                .header("Cookie", cookie).method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode tree(HttpResponse<String> response) throws IOException {
        return RunStore.JSON.readTree(response.body());
    }

    private void awaitFinished(BenchmarkServer server, String id) throws Exception {
        Path directory = results.resolve(id);
        await(directory, () -> {
            try {
                JsonNode view = tree(request(server, "/api/runs/" + id, "GET", null, true));
                return RunStore.State.valueOf(view.path("run").path("state").asText()).finished();
            } catch (Exception e) {
                throw new AssertionError("Cannot read run state", e);
            }
        });
    }

    /** Watch real state changes rather than assuming when a build or child JVM will be ready. */
    private static void await(Path directory, BooleanSupplier condition) throws Exception {
        try (WatchService watcher = directory.getFileSystem().newWatchService()) {
            directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            directory.resolve("results").register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("Timed out waiting for run: " + Files.readString(directory.resolve("status.json"))
                            + "\n" + (Files.exists(directory.resolve("run.log")) ? Files.readString(directory.resolve("run.log")) : ""));
                }
                var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                if (key != null) {
                    key.pollEvents();
                    key.reset();
                }
            }
        }
    }

    private static String git(Path directory, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private void writeRunner(String revision) throws IOException {
        Files.writeString(origin.resolve("prototype/benchmark/src/main/java/FixtureBenchmark.java"), """
                import java.nio.file.*;
                import java.util.concurrent.CountDownLatch;
                public final class FixtureBenchmark {
                  public static void main(String[] args) throws Exception {
                    if (args[0].equals("child")) {
                      Path ready = Path.of(args[1]);
                      Path temp = ready.resolveSibling("child.tmp");
                      Files.writeString(temp, Long.toString(ProcessHandle.current().pid()));
                      Files.move(temp, ready);
                      new CountDownLatch(1).await();
                      return;
                    }
                    String plan = Files.readString(Path.of(args[2]));
                    if (plan.contains("\\"fail\\"")) {
                      System.err.println("Fixture run failed");
                      System.exit(7);
                    }
                    if (plan.contains("\\"block\\"")) {
                      Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), "FixtureBenchmark", "child", "results/child.pid").inheritIO().start();
                      child.waitFor();
                      return;
                    }
                    Files.writeString(Path.of("results/result.json"), "{\\"revision\\":\\"%s\\"}");
                    Files.writeString(Path.of("results/result.html"), "<html>Benchmark results</html>");
                  }
                }
                """.formatted(revision));
    }
}
