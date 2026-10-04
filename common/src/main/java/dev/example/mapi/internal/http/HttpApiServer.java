package dev.example.mapi.internal.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.mapi.api.ServerStatusSnapshot;
import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.SnapshotResult;
import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.json.JsonReader;
import dev.example.mapi.internal.json.JsonWriter;
import dev.example.mapi.internal.operation.ExecutionMode;
import dev.example.mapi.internal.operation.OperationDescriptor;
import dev.example.mapi.internal.operation.OperationGuard;
import dev.example.mapi.internal.operation.OperationRegistry;
import dev.example.mapi.internal.operation.Scope;
import dev.example.mapi.internal.operation.SideEffectClass;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.problem.ProblemJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * Local authenticated HTTP API, implemented on the JDK's built-in
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
    static final int PROTOCOL_VERSION = 1;

    /** Maximum accepted request body size in bytes. */
    public static final int MAX_BODY_BYTES = 8192;

    private static final String BEARER_PREFIX = "Bearer ";
    static final String API_PREFIX = "/api/v1/";
    private static final int BACKLOG = 32;

    final MapiConfig config;
    final MapiRuntime runtime;
    private final Logger logger;
    private final RateLimiter rateLimiter;
    private final dev.example.mapi.internal.operation.ScopeGrants scopeGrants;
    private final OperationRegistry operations = new OperationRegistry();
    private final OperationGuard guard = new OperationGuard();
    private final ServerApiHandler serverApi;
    private final ClientApiHandler clientApi;
    private final HttpApiRoutes routes;

    private HttpServer httpServer;
    private ThreadPoolExecutor workers;
    private java.util.concurrent.Semaphore streamPermits;
    private EventStreamHandler streamHandler;

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
        this.serverApi = new ServerApiHandler(this);
        this.clientApi = new ClientApiHandler(this);
        HttpApiOperationMetadata.register(operations);
        this.routes = new HttpApiRoutes(this, serverApi, clientApi);
    }



    /** @return the operation metadata registry (for introspection endpoints/tests) */
    public OperationRegistry operations() {
        return operations;
    }

    /** @return every GET route path (introspection for the OpenAPI drift test) */
    public java.util.Set<String> getRoutePaths() {
        return java.util.Set.copyOf(routes.getRoutes().keySet());
    }

    /** @return POST path to operation identifier mapping used for authorization */
    public Map<String, String> postRouteOperations() {
        return Map.copyOf(routes.postRouteOperations());
    }

    /** @return every POST route path (introspection for the OpenAPI drift test) */
    public java.util.Set<String> postRoutePaths() {
        return java.util.Set.copyOf(routes.getPostRoutes().keySet());
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
        // Long-lived SSE handlers occupy workers; queued requests cannot trigger
        // growth beyond the core size until the queue fills.
        workers = new ThreadPoolExecutor(2 + EventStreamHandler.MAX_CONCURRENT_STREAMS,
                2 + EventStreamHandler.MAX_CONCURRENT_STREAMS,
                30L, TimeUnit.SECONDS,
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
        streamPermits = new java.util.concurrent.Semaphore(EventStreamHandler.MAX_CONCURRENT_STREAMS);
        streamHandler = new EventStreamHandler(runtime.eventBus());
        httpServer.setExecutor(workers);
        httpServer.start();
        if (config.authRequired()) {
            logger.info("MAPI HTTP API listening on http://127.0.0.1:{} (loopback only, bearer token required)",
                    config.httpPort());
        } else {
            logger.warn("MAPI HTTP API listening on http://127.0.0.1:{} (loopback only, "
                    + "AUTHENTICATION DISABLED - blank http.token)", config.httpPort());
        }
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

    void streamMiddleware(HttpExchange exchange) throws IOException {
        try {
            prepareCors(exchange);
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
            if (config.authRequired()
                    && !authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"mapi\"");
                error(exchange, ProblemCode.UNAUTHORIZED, "Missing or invalid bearer token");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                error(exchange, ProblemCode.METHOD_NOT_ALLOWED, "Only GET requests are supported");
                return;
            }
            if (!streamPermits.tryAcquire()) {
                error(exchange, ProblemCode.RATE_LIMITED, "Too many concurrent streams");
                return;
            }
            try {
                streamHandler.handle(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                streamPermits.release();
            }
        } catch (ProblemException e) {
            try {
                error(exchange, e.code(), e.getMessage(), e.details());
            } catch (IOException ignored) {
                // Response already committed or socket gone.
            }
        } catch (RuntimeException e) {
            logger.error("MAPI HTTP: unexpected error in stream handler", e);
            try {
                error(exchange, ProblemCode.INTERNAL, "Internal server error");
            } catch (IOException ignored) {
                // Response already committed or socket gone.
            }
        }
    }

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
        prepareCors(exchange);
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
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            handlePreflight(exchange, origin);
            return;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (config.authRequired() && !authorized(authorization)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"mapi\"");
            error(exchange, ProblemCode.UNAUTHORIZED, "Missing or invalid bearer token");
            return;
        }
        if (HttpApiRequest.declaredBodyTooLarge(exchange)) {
            error(exchange, ProblemCode.PAYLOAD_TOO_LARGE,
                    "Request body exceeds " + MAX_BODY_BYTES + " bytes");
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = exchange.getRequestURI().getPath();
        if (method.equals("GET")) {
            routeGet(exchange, authorization, path);
        } else if (method.equals("POST")) {
            routePost(exchange, authorization, path);
        } else {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            error(exchange, ProblemCode.METHOD_NOT_ALLOWED,
                    "Only GET and POST requests are supported");
        }
    }



    private void routeGet(HttpExchange exchange, String token, String path) throws IOException {
        HttpApiRoutes.GetHandler handler = routes.getRoutes().get(path);
        if (handler != null) {
            handler.handle(exchange);
            return;
        }
        if (path.startsWith(API_PREFIX + "jobs/")) {
            serverApi.sendJobView(exchange, path.substring((API_PREFIX + "jobs/").length()));
            return;
        }
        error(exchange, ProblemCode.NOT_FOUND, "Unknown endpoint: " + path);
    }

    private void routePost(HttpExchange exchange, String token, String path) throws IOException {
        HttpApiRoutes.PostHandler handler = routes.getPostRoutes().get(path);
        if (handler == null) {
            exchange.getResponseHeaders().set("Allow", "GET");
            error(exchange, ProblemCode.METHOD_NOT_ALLOWED,
                    "This endpoint only supports GET");
            return;
        }
        Map<String, Object> body = HttpApiRequest.readJsonObject(exchange, path);
        java.util.Set<Scope> grants = scopeGrants.scopesForToken(
                HttpApiRequest.bearerToken(exchange.getRequestHeaders().getFirst("Authorization")));
        String operationId = routes.postRouteOperations().get(path);
        if (operationId == null) {
            throw new IllegalStateException("POST route has no operation metadata: " + path);
        }
        checkAccess(operationId, grants, body);
        OperationDescriptor descriptor = operations.find(operationId).orElseThrow();
        if (descriptor.requiresLease()) {
            String leaseId = HttpApiRequest.stringField(body, "leaseId");
            if (leaseId == null) {
                throw new ProblemException(ProblemCode.LEASE_REQUIRED,
                        "operation requires a valid control lease", Map.of("operation", operationId));
            }
            String topic = operationId.startsWith("server.ticks.") || operationId.startsWith("server.lan.")
                    ? dev.example.mapi.internal.tick.TickControlService.LEASE_TOPIC : "input";
            if (!runtime.leases().heldBy(topic, leaseId)) {
                throw new ProblemException(ProblemCode.LEASE_REQUIRED,
                        "lease is not the current holder for this operation",
                        Map.of("operation", operationId, "topic", topic));
            }
        }
        handler.handle(exchange, body, grants);
    }

    // ------------------------------------------------------------------
    // POST handlers (server operations)
    // ------------------------------------------------------------------























































    // ------------------------------------------------------------------
    // Client handlers (spec §3, §9, §20)
    // ------------------------------------------------------------------













    // ------------------------------------------------------------------
    // GET handlers (server observability)
    // ------------------------------------------------------------------



















    // ------------------------------------------------------------------
    // Helpers for the operation surface
    // ------------------------------------------------------------------





    void requireLease(String topic, String leaseId) {
        if (!runtime.leases().heldBy(topic, leaseId)) {
            throw new ProblemException(ProblemCode.LEASE_REQUIRED,
                    "lease is not the current holder for this operation",
                    Map.of("topic", topic));
        }
    }

    void checkAccess(String operationId, java.util.Set<Scope> grants, Map<String, Object> body) {
        OperationDescriptor descriptor = operations.find(operationId)
                .orElseThrow(() -> new IllegalStateException("unregistered operation " + operationId));
        ExecutionMode requested = null;
        if ("client.inventory.click".equals(operationId) && !(body.get("executionMode") instanceof String)) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "executionMode is required for client.inventory.click");
        }
        if (body.get("executionMode") instanceof String mode) {
            requested = java.util.Arrays.stream(ExecutionMode.values())
                    .filter(value -> value.wireName().equals(mode))
                    .findFirst()
                    .orElseThrow(() -> new ProblemException(ProblemCode.BAD_REQUEST,
                            "unknown executionMode: " + mode));
        }
        // §14: explicit destructive intent comes from the request body
        // ("confirm": true) — a request flag alone never grants permission,
        // but a grant without stated intent fails before the handler runs.
        boolean destructiveIntent = Boolean.TRUE.equals(body.get("confirm"));
        guard.checkAccess(descriptor, grants, destructiveIntent, requested);
    }

    Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("status", "ok");
        return body;
    }

    Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("name", "mapi");
        body.put("version", runtime.modVersion());
        body.put("apiVersion", runtime.apiVersion());
        body.put("minecraftVersion", runtime.minecraftVersion());
        body.put("platform", runtime.platform().id());
        body.put("platformVersion", runtime.platformVersion());
        body.put("runtimeArtifact", dev.example.mapi.internal.RuntimeArtifactIdentity.current().toMap());
        return body;
    }

    void sendServerStatus(HttpExchange exchange) throws IOException {
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
        String expected = "http://localhost:" + config.httpPort();
        String alt = "http://127.0.0.1:" + config.httpPort();
        return origin.equals(expected) || origin.equals(alt) || configuredOrigin(origin);
    }

    private boolean configuredOrigin(String origin) {
        return config.httpAllowedOrigins().contains(normalizeOrigin(origin));
    }

    private void prepareCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        exchange.getResponseHeaders().add("Vary", "Origin");
        if (config.httpAllowedOrigins().contains(normalizeOrigin(origin))) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", normalizeOrigin(origin));
            exchange.getResponseHeaders().set("Access-Control-Expose-Headers",
                    "Retry-After, X-MAPI-Protocol-Version");
        }
    }

    private static String normalizeOrigin(String origin) {
        try {
            java.net.URI uri = java.net.URI.create(origin);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!(scheme.equals("http") || scheme.equals("https")) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawAuthority().endsWith(":")
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                return "";
            }
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
            String normalizedHost = formatOriginHost(host);
            return scheme + "://" + normalizedHost
                    + (port == (scheme.equals("https") ? 443 : 80) ? "" : ":" + port);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String formatOriginHost(String host) {
        String unbracketed = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return unbracketed.contains(":") ? "[" + unbracketed + "]" : unbracketed;
    }

    private void handlePreflight(HttpExchange exchange, String origin) throws IOException {
        if (origin == null || !configuredOrigin(origin)
                || !exchange.getRequestURI().getPath().startsWith(API_PREFIX)) {
            error(exchange, ProblemCode.FORBIDDEN_ORIGIN, "Origin not allowed");
            return;
        }
        String requestedMethod = exchange.getRequestHeaders().getFirst("Access-Control-Request-Method");
        if (requestedMethod == null || !(requestedMethod.equalsIgnoreCase("GET")
                || requestedMethod.equalsIgnoreCase("POST"))) {
            exchange.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
            error(exchange, ProblemCode.METHOD_NOT_ALLOWED, "Preflight method not allowed");
            return;
        }
        String requestedHeaders = exchange.getRequestHeaders().getFirst("Access-Control-Request-Headers");
        if (requestedHeaders != null) {
            for (String header : requestedHeaders.split(",")) {
                String name = header.trim();
                if (!(name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("content-type"))) {
                    error(exchange, ProblemCode.BAD_REQUEST, "Preflight header not allowed");
                    return;
                }
            }
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
        if ("true".equalsIgnoreCase(exchange.getRequestHeaders()
                .getFirst("Access-Control-Request-Private-Network"))) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
        }
        exchange.sendResponseHeaders(204, -1);
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
