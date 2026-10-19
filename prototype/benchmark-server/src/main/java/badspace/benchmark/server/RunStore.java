package badspace.benchmark.server;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Persistent runs, with a single worker so builds and benchmarks never overlap. */
final class RunStore implements AutoCloseable {

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final int LOG_TAIL_BYTES = 64 * 1024;
    private static final long PROCESS_STOP_SECONDS = 2;
    private static final long PROCESS_POLL_MILLIS = 200;

    enum State {
        ACCEPTED, FETCHING, BUILDING, RUNNING, STOPPING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED;

        boolean finished() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
        }
    }

    /** Saved metadata is immutable and contains the full request and the resolved commit. */
    record Run(String id, State state, String created, String finished, String commit,
            String message, RunRequest request) {
    }

    record Artifact(String name, long bytes) {
    }

    record View(Run run, String progress, List<Artifact> artifacts) {
    }

    private final ServerConfig config;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final FileChannel repositoryLockChannel;
    private final FileLock repositoryLock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // HTTP threads and the worker read/write these fields only under this object's monitor.
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private String active;
    private boolean stopping;
    private boolean closed;
    private boolean cleanupFailed;

    RunStore(ServerConfig config) throws IOException {
        this.config = config;
        Files.createDirectories(config.results());
        Path repository = config.repository().toRealPath();
        Path results = config.results().toRealPath();
        if (results.startsWith(repository) || repository.startsWith(results)) {
            throw new IllegalArgumentException("Repository and results directories must be separate");
        }
        lockChannel = FileChannel.open(results.resolve(".server.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) {
                throw new IOException("Another service uses " + results);
            }
            load();
        } catch (IOException | RuntimeException e) {
            if (acquired != null) {
                acquired.release();
            }
            lockChannel.close();
            worker.shutdown();
            throw e;
        }
        lock = acquired;
        // The clone is dedicated to this service. Its lock stays outside Git's working tree.
        FileChannel cloneChannel = null;
        try {
            cloneChannel = FileChannel.open(repository.resolve(".git/benchmark-server.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            repositoryLockChannel = cloneChannel;
            repositoryLock = repositoryLockChannel.tryLock();
            if (repositoryLock == null) {
                throw new IOException("Another service uses repository " + repository);
            }
        } catch (IOException | RuntimeException e) {
            if (cloneChannel != null) {
                cloneChannel.close();
            }
            lock.release();
            lockChannel.close();
            worker.shutdown();
            throw e;
        }
    }

    synchronized Run submit(RunRequest request) throws IOException {
        if (closed) {
            throw new IllegalStateException("Service is stopping");
        }
        if (active != null) {
            throw new IllegalStateException("A run is already active: " + active);
        }
        String id = UUID.randomUUID().toString();
        Path directory = directory(id);
        Files.createDirectory(directory);
        Files.createDirectory(directory.resolve("results"));
        JSON.writeValue(directory.resolve("plan.json").toFile(), request.plan());
        Run run = new Run(id, State.ACCEPTED, Instant.now().toString(), null, null, "Request accepted", request);
        save(run);
        runs.put(id, run);
        active = id;
        stopping = false;
        worker.submit(() -> execute(id));
        return run;
    }

    synchronized List<View> list() throws IOException {
        List<View> views = new ArrayList<>();
        for (Run run : runs.values().stream().sorted(Comparator.comparing(Run::created).reversed()).toList()) {
            views.add(view(run.id()));
        }
        return views;
    }

    synchronized View view(String id) throws IOException {
        Run run = require(id);
        String progress = "";
        for (String line : tail(id).split("\\R")) {
            if (line.startsWith("== ") || line.startsWith("# Run progress:")
                    || line.startsWith("# Benchmark:") || line.startsWith("Footprint ")) {
                progress = line;
            }
        }
        return new View(run, progress, artifacts(id));
    }

    synchronized Run cancel(String id) throws IOException {
        Run run = require(id);
        if (!run.state().finished() && !stopping) {
            stopping = true;
            update(id, State.STOPPING, run.commit(), "Stop requested");
        }
        return runs.get(id);
    }

    synchronized String tail(String id) throws IOException {
        require(id);
        Path log = directory(id).resolve("run.log");
        if (!Files.exists(log)) {
            return "";
        }
        try (FileChannel channel = FileChannel.open(log, StandardOpenOption.READ)) {
            long size = channel.size();
            channel.position(Math.max(0, size - LOG_TAIL_BYTES));
            var buffer = java.nio.ByteBuffer.allocate((int) Math.min(size, LOG_TAIL_BYTES));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Read only the tail, even when a benchmark writes a large log.
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        }
    }

    synchronized Path artifact(String id, String name) throws IOException {
        require(id);
        Path base = directory(id);
        Path path = base.resolve(name).normalize();
        Path output = base.resolve("results");
        if (!path.startsWith(output) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().startsWith(output.toRealPath())
                || !path.toRealPath().startsWith(base.toRealPath())
                || !(name.endsWith(".html") || name.endsWith(".json"))) {
            throw new NoSuchElementException("Unknown artifact: " + name);
        }
        return path;
    }

    private List<Artifact> artifacts(String id) throws IOException {
        List<Artifact> artifacts = new ArrayList<>();
        Path base = directory(id);
        try (Stream<Path> paths = Files.walk(base.resolve("results"))) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                String name = base.relativize(path).toString().replace('\\', '/');
                if ((name.endsWith(".html") || name.endsWith(".json"))
                        && path.toRealPath().startsWith(base.resolve("results").toRealPath())
                        && path.toRealPath().startsWith(base.toRealPath())) {
                    artifacts.add(new Artifact(name, Files.size(path)));
                }
            }
        }
        return List.copyOf(artifacts);
    }

    private void execute(String id) {
        try {
            RunRequest request;
            synchronized (this) {
                request = require(id).request();
            }
            phase(id, State.FETCHING, null, "Fetching " + request.remoteRef());
            String changes = command(id, config.repository(), config.git(), "status", "--porcelain");
            if (!changes.isBlank()) {
                throw new IOException("Repository has local changes; use a clean benchmark clone");
            }
            command(id, config.repository(), config.git(), "fetch", "--no-tags", "origin", request.remoteRef());
            String commit = command(id, config.repository(), config.git(), "rev-parse", "--verify", "FETCH_HEAD^{commit}").strip();
            if (!commit.matches("[0-9a-f]{40,64}")) {
                throw new IOException("Cannot resolve the fetched commit: " + commit);
            }
            command(id, config.repository(), config.git(), "checkout", "--detach", commit);
            phase(id, State.BUILDING, commit, "Building benchmark jar");
            command(id, config.repository(), config.maven(), "-B", "-f", "prototype/pom.xml", "-pl", "benchmark", "-am",
                    "clean", "package", "-DskipTests");
            Path jar = config.repository().resolve("prototype/benchmark/target/benchmarks.jar");
            if (!Files.isRegularFile(jar)) {
                throw new IOException("Build did not produce " + jar);
            }
            phase(id, State.RUNNING, commit, "Running benchmark plan");
            command(id, directory(id), config.java(), "--sun-misc-unsafe-memory-access=allow", "-jar", jar.toString(),
                    "run", "--plan", directory(id).resolve("plan.json").toString());
            finish(id, State.SUCCEEDED, "Plan completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(id, State.CANCELLED, "Run stopped");
        } catch (Exception e) {
            finish(id, State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            synchronized (this) {
                if (id.equals(active)) {
                    active = null;
                }
            }
        }
    }

    private synchronized void phase(String id, State state, String commit, String message)
            throws IOException, InterruptedException {
        checkStop();
        update(id, state, commit, message);
        Files.writeString(directory(id).resolve("run.log"), "\n== " + message + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Runs without a shell. All command output goes to disk, never to an unbounded memory buffer. */
    private String command(String id, Path directory, String... command) throws IOException, InterruptedException {
        Path log = directory(id).resolve("run.log");
        long offset;
        Process process;
        Map<Long, ProcessHandle> children = new LinkedHashMap<>();
        synchronized (this) {
            checkStop();
            offset = Files.exists(log) ? Files.size(log) : 0;
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("LC_ALL", "C");
            process = builder.start();
            process.getOutputStream().close();
        }
        try {
            while (!process.waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(child -> children.put(child.pid(), child));
                synchronized (this) {
                    checkStop();
                }
            }
            synchronized (this) {
                checkStop();
            }
            if (process.exitValue() != 0) {
                throw new IOException(command[0] + " exited with code " + process.exitValue() + "; see run.log");
            }
            // Only status and rev-parse need their output; cap it to the same size as the log tail.
            try (FileChannel channel = FileChannel.open(log, StandardOpenOption.READ)) {
                channel.position(offset);
                var buffer = java.nio.ByteBuffer.allocate((int) Math.min(channel.size() - offset, LOG_TAIL_BYTES));
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // Read only this command's output.
                }
                return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            }
        } finally {
            process.descendants().forEach(child -> children.put(child.pid(), child));
            try {
                stopProcess(process, List.copyOf(children.values()));
            } catch (IOException e) {
                synchronized (this) {
                    // Do not admit another measurement if a child may still be running.
                    cleanupFailed = true;
                    closed = true;
                }
                throw e;
            }
        }
    }

    /** Stops the entire process tree, including Maven and JMH forked JVMs, before freeing the worker. */
    private static void stopProcess(Process process, List<ProcessHandle> children) throws IOException {
        boolean interrupted = Thread.interrupted();
        process.destroy();
        children.reversed().forEach(ProcessHandle::destroy);
        try {
            try {
                process.waitFor(PROCESS_STOP_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            children.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                if (!process.waitFor(PROCESS_STOP_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException("Process did not stop: " + process.pid());
                }
                for (ProcessHandle child : children) {
                    if (child.isAlive()) {
                        try {
                            child.onExit().get(PROCESS_STOP_SECONDS, TimeUnit.SECONDS);
                        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                            throw new IOException("Child process did not stop: " + child.pid(), e);
                        }
                    }
                }
            } catch (InterruptedException e) {
                interrupted = true;
                throw new IOException("Interrupted while waiting for process cleanup", e);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void checkStop() throws InterruptedException {
        if (stopping || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Stop requested");
        }
    }

    private synchronized void finish(String id, State state, String message) {
        Run previous = require(id);
        try {
            State finalState = cleanupFailed ? State.FAILED : stopping ? State.CANCELLED : state;
            update(id, finalState, previous.commit(), stopping && !cleanupFailed ? "Run stopped" : message);
        } catch (IOException e) {
            // Keep the live state final. On restart, the last saved active state becomes INTERRUPTED.
            runs.put(id, new Run(id, State.FAILED, previous.created(), Instant.now().toString(), previous.commit(),
                    "Cannot save final state: " + e.getMessage(), previous.request()));
            System.err.println("Cannot save run " + id + ": " + e.getMessage());
        } finally {
            active = null;
        }
    }

    private void update(String id, State state, String commit, String message) throws IOException {
        Run previous = require(id);
        Run next = new Run(id, state, previous.created(), state.finished() ? Instant.now().toString() : null,
                commit, message, previous.request());
        save(next);
        runs.put(id, next);
    }

    private Run require(String id) {
        Run run = runs.get(id);
        if (run == null) {
            throw new NoSuchElementException("Unknown run: " + id);
        }
        return run;
    }

    private Path directory(String id) {
        return config.results().resolve(id);
    }

    private void save(Run run) throws IOException {
        Path temp = directory(run.id()).resolve("status.tmp");
        Path target = directory(run.id()).resolve("status.json");
        JSON.writeValue(temp.toFile(), run);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void load() throws IOException {
        try (Stream<Path> paths = Files.list(config.results())) {
            for (Path path : paths.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                Path status = path.resolve("status.json");
                if (!Files.exists(status)) {
                    continue;
                }
                Run run = JSON.readValue(status.toFile(), Run.class);
                if (!run.id().equals(path.getFileName().toString()) || !run.id().matches("[0-9a-f-]{36}")) {
                    throw new IOException("Invalid run directory: " + path);
                }
                runs.put(run.id(), run);
                if (!run.state().finished()) {
                    update(run.id(), State.INTERRUPTED, run.commit(), "Service stopped before the run finished");
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        IOException saveFailure = null;
        synchronized (this) {
            closed = true;
            if (active != null) {
                try {
                    cancel(active);
                } catch (IOException e) {
                    saveFailure = e;
                }
            }
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                    throw new IOException("Benchmark worker did not stop");
                }
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping benchmark worker", e);
        } finally {
            repositoryLock.release();
            repositoryLockChannel.close();
            lock.release();
            lockChannel.close();
        }
        if (saveFailure != null) {
            throw saveFailure;
        }
    }
}
