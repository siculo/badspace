package badspace.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The version of the code that a run measures. A jar that is older than the
 * sources measures old code, for example after a {@code git pull} without a
 * new build: the results look valid, but they are not.
 */
final class SourceVersion {

    private SourceVersion() {
    }

    /**
     * Prints a warning if a source file of the prototype is newer than the
     * jar. It checks the {@code src} directories and the {@code pom.xml}
     * files. It does nothing when the code does not run from a jar, for
     * example in the IDE.
     */
    static void warnIfJarIsOld() {
        Optional<Path> jar = jar();
        if (jar.isEmpty()) {
            return;
        }
        // The jar is in prototype/benchmark/target.
        Path prototype = jar.get().getParent().getParent().getParent();
        try {
            FileTime built = Files.getLastModifiedTime(jar.get());
            Optional<Path> newest = sources(prototype).stream()
                    .max(Comparator.comparing(SourceVersion::modified));
            if (newest.isPresent() && modified(newest.get()).compareTo(built) > 0) {
                System.err.println("WARNING: the jar is older than " + prototype.relativize(newest.get())
                        + ", so the run measures old code.");
                System.err.println("WARNING: build the jar again with: mvn -f .. install -DskipTests");
            }
        } catch (IOException | UncheckedIOException e) {
            System.err.println("WARNING: cannot check if the jar is older than the sources: " + e.getMessage());
        }
    }

    /**
     * Returns the commit of the prototype, with {@code -dirty} at the end if
     * there are changes that are not committed, or {@code unknown} if git
     * cannot tell it. The results are not counted as changes.
     */
    static String commit() {
        Optional<Path> jar = jar();
        Path directory = jar.isPresent() ? jar.get().getParent().getParent().getParent() : Path.of("..");
        Optional<String> commit = git(directory, "rev-parse", "--short=12", "HEAD");
        if (commit.isEmpty() || commit.get().isEmpty()) {
            return "unknown";
        }
        Optional<String> changes = git(directory, "status", "--porcelain", "--untracked-files=no",
                "--", ".", ":(exclude)benchmark/results");
        return changes.isPresent() && !changes.get().isEmpty() ? commit.get() + "-dirty" : commit.get();
    }

    /** Returns the path of the jar that contains this class, if the class comes from a jar. */
    private static Optional<Path> jar() {
        try {
            Path location = Path.of(SourceVersion.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.toString().endsWith(".jar") ? Optional.of(location) : Optional.empty();
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** Returns the files in the src directories of the modules, and the pom.xml files. */
    private static List<Path> sources(Path prototype) throws IOException {
        List<Path> sources = new ArrayList<>();
        sources.add(prototype.resolve("pom.xml"));
        List<Path> modules;
        try (Stream<Path> files = Files.list(prototype)) {
            modules = files.filter(Files::isDirectory).toList();
        }
        for (Path module : modules) {
            if (Files.isRegularFile(module.resolve("pom.xml"))) {
                sources.add(module.resolve("pom.xml"));
            }
            if (Files.isDirectory(module.resolve("src"))) {
                try (Stream<Path> files = Files.walk(module.resolve("src"))) {
                    files.filter(Files::isRegularFile).forEach(sources::add);
                }
            }
        }
        return sources;
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs git in the directory and returns its output, or nothing if git fails. */
    private static Optional<String> git(Path directory, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
