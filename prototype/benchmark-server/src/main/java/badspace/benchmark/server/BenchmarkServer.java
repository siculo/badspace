package badspace.benchmark.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone HTTP entry point for one benchmark machine. The API accepts one
 * run at a time; the same process serves the monitoring page and saved artifacts.
 * Shutdown cancels the active build or benchmark before closing the run store.
 */
public final class BenchmarkServer implements AutoCloseable {

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int DOWNLOAD_BUFFER_BYTES = 8192;
    private static final String RESULTS_COOKIE = "benchmark_results";
    private final String resultsSession = UUID.randomUUID().toString();
    private final ServerConfig config;
    private final RunStore runs;
    private final HttpServer server;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

    BenchmarkServer(ServerConfig config) throws IOException {
        this.config = config;
        runs = new RunStore(config);
        try {
            server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 16);
        } catch (IOException | RuntimeException e) {
            runs.close();
            requests.close();
            throw e;
        }
        server.setExecutor(requests);
        server.createContext("/", this::handle);
    }

    /** Starts a service from the given properties file, or server.properties in the current directory. */
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: java -jar benchmark-server.jar [server.properties]");
        }
        ServerConfig config = ServerConfig.read(Path.of(args.length == 0 ? "server.properties" : args[0]));
        Path location = Path.of(BenchmarkServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (location.toRealPath().startsWith(config.repository().toRealPath())) {
            throw new IllegalArgumentException("Install and start the server jar outside the repository");
        }
        BenchmarkServer service = new BenchmarkServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                service.close();
            } catch (IOException e) {
                System.err.println("Cannot stop service: " + e.getMessage());
            }
        }));
        service.start();
        System.out.println("Benchmark server listening on " + config.bind() + ":" + service.port());
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/") && method.equals("GET")) {
                exchange.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                                + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
                try (InputStream page = BenchmarkServer.class.getResourceAsStream("/index.html")) {
                    send(exchange, 200, "text/html; charset=utf-8", page.readAllBytes());
                }
                return;
            }
            if (!path.startsWith("/api/")) {
                json(exchange, 404, Map.of("error", "Unknown endpoint"));
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                json(exchange, 401, Map.of("error", "Bearer token required"));
                return;
            }
            // A browser on another site must not start builds on the local network.
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (origin != null && !origin.equals("http://" + host) && !origin.equals("https://" + host)) {
                json(exchange, 403, Map.of("error", "Cross-origin requests are not allowed"));
                return;
            }
            if (path.equals("/api/runs")) {
                switch (method) {
                    case "GET" -> {
                        if (!config.token().isEmpty()) {
                            exchange.getResponseHeaders().set("Set-Cookie", RESULTS_COOKIE + "=" + resultsSession
                                    + "; Path=/api/runs/; HttpOnly; SameSite=Strict");
                        }
                        json(exchange, 200, runs.list());
                    }
                    case "POST" -> {
                        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                            json(exchange, 415, Map.of("error", "Use Content-Type: application/json"));
                            return;
                        }
                        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                        if (body.length > MAX_REQUEST_BYTES) {
                            json(exchange, 413, Map.of("error", "Request exceeds 1 MiB"));
                            return;
                        }
                        RunStore.Run run = runs.submit(RunRequest.parse(RunStore.JSON.readTree(body)));
                        exchange.getResponseHeaders().set("Location", "/api/runs/" + run.id());
                        json(exchange, 202, run);
                    }
                    default -> methodNotAllowed(exchange, "GET, POST");
                }
                return;
            }
            String[] parts = path.split("/", 6);
            if (parts.length >= 4 && parts[1].equals("api") && parts[2].equals("runs")) {
                String id = parts[3];
                if (parts.length == 4) {
                    if (method.equals("GET")) {
                        json(exchange, 200, runs.view(id));
                    } else {
                        methodNotAllowed(exchange, "GET");
                    }
                    return;
                }
                if (parts.length == 5 && parts[4].equals("cancel")) {
                    if (method.equals("POST")) {
                        json(exchange, 202, runs.cancel(id));
                    } else {
                        methodNotAllowed(exchange, "POST");
                    }
                    return;
                }
                if (parts.length == 5 && parts[4].equals("log")) {
                    if (method.equals("GET")) {
                        send(exchange, 200, "text/plain; charset=utf-8", runs.tail(id).getBytes(StandardCharsets.UTF_8));
                    } else {
                        methodNotAllowed(exchange, "GET");
                    }
                    return;
                }
                if (parts.length == 6 && parts[4].equals("files")) {
                    if (method.equals("GET")) {
                        download(exchange, runs.artifact(id, parts[5]));
                    } else {
                        methodNotAllowed(exchange, "GET");
                    }
                    return;
                }
            }
            json(exchange, 404, Map.of("error", "Unknown endpoint"));
        } catch (JsonProcessingException e) {
            json(exchange, 400, Map.of("error", "Invalid JSON: " + e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            json(exchange, 400, Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            json(exchange, 404, Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            json(exchange, 409, Map.of("error", e.getMessage()));
        } catch (IOException e) {
            System.err.println("HTTP request failed: " + e.getMessage());
            json(exchange, 500, Map.of("error", "I/O failure; see service output"));
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        if (config.token().isEmpty()) {
            return true;
        }
        String supplied = exchange.getRequestHeaders().getFirst("Authorization");
        if (supplied != null) {
            return MessageDigest.isEqual(("Bearer " + config.token()).getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8));
        }
        // Browser navigation cannot send a bearer header. The cookie only grants access to result files.
        String[] parts = exchange.getRequestURI().getPath().split("/", 6);
        if (!exchange.getRequestMethod().equals("GET") || parts.length != 6
                || !parts[1].equals("api") || !parts[2].equals("runs") || !parts[4].equals("files")) {
            return false;
        }
        String cookies = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookies != null) {
            for (String cookie : cookies.split(";")) {
                if (MessageDigest.isEqual((RESULTS_COOKIE + "=" + resultsSession).getBytes(StandardCharsets.UTF_8),
                        cookie.strip().getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        json(exchange, 405, Map.of("error", "Method not allowed"));
    }

    private static void download(HttpExchange exchange, Path file) throws IOException {
        String name = file.getFileName().toString();
        boolean html = name.endsWith(".html");
        exchange.getResponseHeaders().set("Content-Type", html ? "text/html; charset=utf-8" : "application/octet-stream");
        // Reports contain scripts. A sandbox lets them work without access to the service API.
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'");
        if (!html) {
            exchange.getResponseHeaders().set("Content-Disposition", "attachment");
        }
        long size = Files.size(file);
        exchange.sendResponseHeaders(200, size == 0 ? -1 : size);
        if (size > 0) {
            try (var input = Files.newInputStream(file)) {
                // A live log can grow during the download; send only the announced snapshot.
                byte[] buffer = new byte[DOWNLOAD_BUFFER_BYTES];
                long remaining = size;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) {
                        break;
                    }
                    exchange.getResponseBody().write(buffer, 0, count);
                    remaining -= count;
                }
            }
        }
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", RunStore.JSON.writeValueAsBytes(value));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() throws IOException {
        server.stop(0);
        requests.close();
        runs.close();
    }
}
