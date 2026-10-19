package badspace.benchmark.server;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Configuration read once at startup; relative paths use the configuration directory. */
record ServerConfig(String bind, int port, Path repository, Path results,
        String git, String maven, String java, String token) {

    ServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        repository = repository.toAbsolutePath().normalize();
        results = results.toAbsolutePath().normalize();
        if (results.startsWith(repository) || repository.startsWith(results)) {
            throw new IllegalArgumentException("Repository and results directories must be separate");
        }
    }

    static ServerConfig read(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        Path base = file.toAbsolutePath().getParent();
        String repository = properties.getProperty("repository");
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException("Missing repository in " + file);
        }
        String java = properties.getProperty("java", "").strip();
        if (java.isEmpty()) {
            java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }
        return new ServerConfig(properties.getProperty("bind", "127.0.0.1"),
                Integer.parseInt(properties.getProperty("port", "8080")),
                base.resolve(repository), base.resolve(properties.getProperty("results", "results")),
                properties.getProperty("git", "git"), properties.getProperty("maven", "mvn"),
                java, properties.getProperty("token", ""));
    }
}
