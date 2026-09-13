package dev.example.mapi.internal.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.mapi.api.ServerStatusSnapshot;
import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.SnapshotResult;
import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.json.JsonWriter;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.problem.ProblemJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * Minimal read-only local HTTP API, implemented on the JDK's built-in
 * {@code com.sun.net.httpserver.HttpServer} (zero external dependencies).
 *
 * <p>Security model (see docs/security.md):
 * <ul>
 *   <li>binds to the loopback interface only</li>
 *   <li>requires a bearer token on every endpoint</li>
 *   <li>rejects unexpected Host/Origin headers</li>
 *   <li>rate-limits per client; bounds request body size and worker
 *       concurrency</li>
 *   <li>never touches live game state off the server thread; snapshots use
 *       server-thread scheduling with a bounded wait</li>
 * </ul>
 *
 * <p>The server starts with the Minecraft server lifecycle and is stopped
 * with it. Port conflicts are reported but never crash the game. If the
 * bounded worker queue saturates, new connections are dropped (clients
 * observe a connection failure) rather than queued without bound.
 */
public final class HttpApiServer {

    /** Version of the HTTP protocol implemented by this server. */
    public static final int PROTOCOL_VERSION = 1;

    /** Maximum accepted request body size in bytes. */
    public static final int MAX_BODY_BYTES = 8192;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_PREFIX = "/api/v1/";
    private static final int BACKLOG = 32;

    private final MapiConfig config;
    private final MapiRuntime runtime;
    private final Logger logger;
    private final RateLimiter rateLimiter;
    private final dev.example.mapi.internal.operation.ScopeGrants scopeGrants;

    private HttpServer httpServer;
    private ThreadPoolExecutor workers;

    /**
     * @param config  validated configuration (HTTP must be enabled)
     * @param runtime MAPI runtime used to obtain server snapshots safely
     * @param logger  platform logger
     */
    public HttpApiServer(MapiConfig config, MapiRuntime runtime, Logger logger) {
        this.config = config;
        this.runtime = runtime;
        this.logger = logger;
        this.rateLimiter = new RateLimiter(config.rateLimitPerMinute());
        this.scopeGrants = config::grantedScopes;
    }

    /**
     * @return the scope-grant source for presented bearer tokens (spec §14);
     *     operation routes consult it through {@code OperationGuard}
     */
    public dev.example.mapi.internal.operation.ScopeGrants scopeGrants() {
        return scopeGrants;
    }

    /**
     * Starts the listener. On bind failure (for example a port conflict) the
     * failure is logged and reported; the game keeps running.
     *
     * @return true if the listener is running
     */
    public boolean start() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "mapi-http-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) ->
                    logger.error("MAPI HTTP: uncaught exception in worker", e));
            return thread;
        };
        workers = new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), factory, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        try {
            // Bind explicitly to the IPv4 loopback. InetAddress.getLoopbackAddress()
            // may return ::1 when the JVM runs with
            // -Djava.net.preferIPv6Addresses=system (NeoForge dev runs do), which
            // would make 127.0.0.1 clients unable to connect. 127.0.0.1 is
            // deterministic on every platform and matches the documented
            // endpoint URLs.
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                    config.httpPort()), BACKLOG);
        } catch (IOException e) {
            logger.error("MAPI HTTP API: failed to bind 127.0.0.1:{} ({}). Port conflict? Change http.port or "
                    + "stop the other listener. The game will keep running.", config.httpPort(), e.toString());
            shutdownWorkers();
            return false;
        }
        httpServer.createContext("/", this::dispatch);
        httpServer.setExecutor(workers);
        httpServer.start();
        logger.info("MAPI HTTP API listening on http://127.0.0.1:{} (loopback only, bearer token required)",
                config.httpPort());
        return true;
    }

    /**
     * @return the bound listener address, for tests; internal accessor
     */
    public java.net.InetSocketAddress boundAddress() {
        return httpServer == null ? null : httpServer.getAddress();
    }

    /**
     * Stops the listener and its worker threads. Safe to call more than once.
     */
    public void stop() {
        if (httpServer != null) {
            try {
                httpServer.stop(0);
            } finally {
                httpServer = null;
            }
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        logger.info("MAPI HTTP API stopped");
    }

    private void shutdownWorkers() {
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }

    // ------------------------------------------------------------------
    // Routing and middleware
    // ------------------------------------------------------------------

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (IOException e) {
            // Client disconnected or socket error: nothing useful to send.
            logger.debug("MAPI HTTP: I/O error serving request", e);
            throw e;
        } catch (ProblemException e) {
            logger.warn("MAPI HTTP: problem {} on {}", e.code().wireCode(), exchange.getRequestURI().getPath());
            try {
                error(exchange, e.code(), e.getMessage(), e.details());
            } catch (IOException ignored) {
                // Response already committed or socket gone.
            }
        } catch (RuntimeException e) {
            logger.error("MAPI HTTP: unexpected error handling request", e);
            try {
                error(exchange, ProblemCode.INTERNAL, "Internal server error");
            } catch (IOException ignored) {
                // Response already committed or socket gone.
            }
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        if (!isAllowedHost(exchange.getRequestHeaders().getFirst("Host"))) {
            error(exchange, ProblemCode.FORBIDDEN_HOST, "Host header not allowed");
            return;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !isAllowedOrigin(origin)) {
            error(exchange, ProblemCode.FORBIDDEN_ORIGIN, "Origin not allowed");
            return;
        }
        String remote = String.valueOf(exchange.getRemoteAddress().getAddress());
        if (!rateLimiter.tryAcquire(remote)) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            error(exchange, ProblemCode.RATE_LIMITED, "Too many requests; slow down");
            return;
        }
        if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"mapi\"");
            error(exchange, ProblemCode.UNAUTHORIZED, "Missing or invalid bearer token");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            error(exchange, ProblemCode.METHOD_NOT_ALLOWED, "Only GET requests are supported");
            return;
        }
        if (bodyTooLarge(exchange)) {
            error(exchange, ProblemCode.PAYLOAD_TOO_LARGE,
                    "Request body exceeds " + MAX_BODY_BYTES + " bytes");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case API_PREFIX + "health" -> respond(exchange, 200, JsonWriter.write(health()));
            case API_PREFIX + "info" -> respond(exchange, 200, JsonWriter.write(info()));
            case API_PREFIX + "server/status" -> sendServerStatus(exchange);
            default -> error(exchange, ProblemCode.NOT_FOUND, "Unknown endpoint: " + path);
        }
    }

    private Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("status", "ok");
        return body;
    }

    private Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("name", "mapi");
        body.put("version", runtime.modVersion());
        body.put("apiVersion", runtime.apiVersion());
        body.put("minecraftVersion", runtime.minecraftVersion());
        body.put("platform", runtime.platform().id());
        body.put("platformVersion", runtime.platformVersion());
        return body;
    }

    private void sendServerStatus(HttpExchange exchange) throws IOException {
        SnapshotResult result = runtime.trySnapshot();
        if (result.timedOut()) {
            error(exchange, ProblemCode.SERVER_BUSY,
                    "Server thread busy; status snapshot timed out. Retry shortly.");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        if (!result.serverRunning() || result.snapshot() == null) {
            body.put("running", false);
            respond(exchange, 200, JsonWriter.write(body));
            return;
        }
        ServerStatusSnapshot snapshot = result.snapshot();
        body.put("running", true);
        body.put("capturedAtEpochMs", snapshot.capturedAtEpochMs());
        body.put("startedAtEpochMs", snapshot.startedAtEpochMs());
        body.put("uptimeMs", snapshot.uptimeMs());
        body.put("playerCount", snapshot.playerCount());
        body.put("maxPlayers", snapshot.maxPlayers());
        body.put("tickCount", snapshot.tickCount());
        body.put("averageTickTimeMs", snapshot.averageTickTimeMs());
        body.put("motd", snapshot.motd());
        respond(exchange, 200, JsonWriter.write(body));
    }

    // ------------------------------------------------------------------
    // Security checks
    // ------------------------------------------------------------------

    private boolean authorized(String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        String candidate = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        byte[] expected = config.httpToken().getBytes(StandardCharsets.UTF_8);
        byte[] provided = candidate.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    private static boolean isAllowedHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        int lastColon = host.lastIndexOf(':');
        int lastBracket = host.lastIndexOf(']');
        if (lastColon > lastBracket) {
            host = host.substring(0, lastColon);
        }
        return Arrays.asList("localhost", "127.0.0.1", "[::1]", "::1").contains(host);
    }

    private boolean isAllowedOrigin(String origin) {
        // CORS stays disabled: any Origin must match the loopback listener
        // exactly, and no CORS response headers are ever emitted.
        String expected = "http://localhost:" + config.httpPort();
        String alt = "http://127.0.0.1:" + config.httpPort();
        return origin.equals(expected) || origin.equals(alt);
    }

    private static boolean bodyTooLarge(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            long value;
            try {
                value = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                return true;
            }
            if (value > MAX_BODY_BYTES) {
                return true;
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Response helpers
    // ------------------------------------------------------------------

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-MAPI-Protocol-Version", String.valueOf(PROTOCOL_VERSION));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void error(HttpExchange exchange, ProblemCode code, String message) throws IOException {
        error(exchange, code, message, null);
    }

    private static void error(HttpExchange exchange, ProblemCode code, String message,
            Map<String, Object> details) throws IOException {
        respond(exchange, code.httpStatus(),
                JsonWriter.write(ProblemJson.errorBody(code, message, details, PROTOCOL_VERSION)));
    }
}
