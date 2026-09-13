package dev.example.mapi.internal.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.mapi.api.ServerStatusSnapshot;
import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.SnapshotResult;
import dev.example.mapi.internal.client.ClientStatusSnapshot;
import dev.example.mapi.internal.client.KeyActionResult;
import dev.example.mapi.internal.client.MapiClientOps;
import dev.example.mapi.internal.client.ScreenNode;
import dev.example.mapi.internal.client.ScreenshotResult;
import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.json.JsonParser;
import dev.example.mapi.internal.json.JsonParser.ParseException;
import dev.example.mapi.internal.json.JsonWriter;
import dev.example.mapi.internal.task.TaskManager;
import dev.example.mapi.internal.task.TaskManager.TaskSnapshot;
import dev.example.mapi.internal.ws.WebSocketConnection;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * Local HTTP API, implemented on the JDK's built-in
 * {@code com.sun.net.httpserver.HttpServer} (zero external dependencies).
 *
 * <p>Security model (see docs/security.md):
 * <ul>
 *   <li>binds to the loopback interface only</li>
 *   <li>requires a bearer token on every endpoint; authorization happens
 *       before request bodies are read or parsed</li>
 *   <li>rejects unexpected Host/Origin headers</li>
 *   <li>rate-limits per client; bounds request body size and worker
 *       concurrency</li>
 *   <li>never touches live game state off the owning thread; game reads use
 *       server-thread scheduling with a bounded wait</li>
 * </ul>
 *
 * <p>Methods: reads are {@code GET}; the small mutating surface uses
 * {@code POST} and {@code DELETE} (spec §7). Every response carries an
 * {@code X-MAPI-Request-Id}; errors include it as {@code requestId}.
 */
public final class HttpApiServer {

    /** Version of the HTTP protocol implemented by this server. */
    public static final int PROTOCOL_VERSION = 1;

    /** Maximum accepted request body size in bytes. */
    public static final int MAX_BODY_BYTES = 8192;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_PREFIX = "/api/v1/";
    private static final String TASKS_PREFIX = API_PREFIX + "tasks";
    private static final int BACKLOG = 32;

    private final MapiConfig config;
    private final MapiRuntime runtime;
    private final Logger logger;
    private final RateLimiter rateLimiter;
    private final IdempotencyStore idempotencyStore = new IdempotencyStore();
    private final java.util.concurrent.atomic.AtomicLong frameCounter =
            new java.util.concurrent.atomic.AtomicLong();

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
    }

    /**
     * Starts the listener. Tries {@code http.port}, then (when
     * {@code http.portFallback > 0}) the consecutive ports above it. On total
     * bind failure the failure is logged and reported; the game keeps running
     * unless {@code http.failFast} is set (the caller decides what failing
     * fast means for the process).
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
        IOException lastFailure = null;
        int attempts = 1 + Math.max(0, config.portFallback());
        for (int attempt = 0; attempt < attempts; attempt++) {
            int port = config.httpPort() + attempt;
            try {
                // Bind explicitly to the IPv4 loopback. InetAddress.getLoopbackAddress()
                // may return ::1 when the JVM runs with
                // -Djava.net.preferIPv6Addresses=system (NeoForge dev runs do), which
                // would make 127.0.0.1 clients unable to connect. 127.0.0.1 is
                // deterministic on every platform and matches the documented
                // endpoint URLs.
                httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                        BACKLOG);
                if (attempt > 0) {
                    logger.info("MAPI HTTP API: primary port {} busy; bound fallback port {}", config.httpPort(),
                            port);
                }
                break;
            } catch (IOException e) {
                lastFailure = e;
                httpServer = null;
                logger.warn("MAPI HTTP API: failed to bind 127.0.0.1:{} ({})", port, e.toString());
            }
        }
        if (httpServer == null) {
            logger.error("MAPI HTTP API: failed to bind 127.0.0.1:{} ({}). Port conflict? Change http.port, "
                    + "set http.portFallback, or stop the other listener. The game will keep running.",
                    config.httpPort(), lastFailure == null ? "unknown" : lastFailure.toString());
            shutdownWorkers();
            return false;
        }
        httpServer.createContext("/", this::dispatch);
        httpServer.setExecutor(workers);
        httpServer.start();
        logger.info("MAPI HTTP API listening on http://127.0.0.1:{} (loopback only, bearer token required)",
                httpServer.getAddress().getPort());
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
        } catch (RuntimeException e) {
            logger.error("MAPI HTTP: unexpected error handling request", e);
            try {
                error(exchange, "unknown", 500, "INTERNAL", "Internal server error");
            } catch (IOException ignored) {
                // Response already committed or socket gone.
            }
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String requestId = resolveRequestId(exchange);
        exchange.getResponseHeaders().set("X-MAPI-Request-Id", requestId);
        if (!isAllowedHost(exchange.getRequestHeaders().getFirst("Host"))) {
            error(exchange, requestId, 403, "FORBIDDEN_HOST", "Host header not allowed");
            return;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !isAllowedOrigin(origin)) {
            error(exchange, requestId, 403, "FORBIDDEN_ORIGIN", "Origin not allowed");
            return;
        }
        String remote = String.valueOf(exchange.getRemoteAddress().getAddress());
        if (!rateLimiter.tryAcquire(remote)) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            error(exchange, requestId, 429, "RATE_LIMITED", "Too many requests; slow down");
            return;
        }
        if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"mapi\"");
            error(exchange, requestId, 401, "UNAUTHORIZED", "Missing or invalid bearer token");
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String requiredScope = requiredScope(method, exchange.getRequestURI().getPath());
        if (requiredScope != null && !config.scopes().contains(requiredScope)) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("required", requiredScope);
            error(exchange, requestId, 403, "FORBIDDEN_SCOPE",
                    "Token lacks the scope required for this effect", extra);
            return;
        }
        if (!method.equals("GET") && !method.equals("POST") && !method.equals("DELETE")) {
            exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
            error(exchange, requestId, 405, "METHOD_NOT_ALLOWED", "Only GET, POST, and DELETE are supported");
            return;
        }
        byte[] body = readBody(exchange);
        if (body == null) {
            error(exchange, requestId, 413, "PAYLOAD_TOO_LARGE",
                    "Request body exceeds " + MAX_BODY_BYTES + " bytes");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        switch (method) {
            case "GET" -> routeGet(exchange, requestId, path);
            case "POST" -> routePost(exchange, requestId, path, body);
            case "DELETE" -> routeDelete(exchange, requestId, path);
            default -> throw new IllegalStateException("unreachable method " + method);
        }
    }

    /**
     * Effect-based scope requirement (spec §4.2: authorize the effect, not
     * the route). {@code null} means no additional scope beyond a valid
     * token (currently unused — every route maps to a scope).
     */
    private String requiredScope(String method, String path) {
        if (path.startsWith(API_PREFIX + "server/world/")) {
            return dev.example.mapi.internal.auth.Scope.WORLD_READ;
        }
        if (path.equals(API_PREFIX + "client/input/key")) {
            return dev.example.mapi.internal.auth.Scope.CLIENT_CONTROL;
        }
        return dev.example.mapi.internal.auth.Scope.OBSERVE;
    }

    private void routeGet(HttpExchange exchange, String requestId, String path) throws IOException {
        if (path.equals(TASKS_PREFIX)) {
            listTasks(exchange, requestId);
            return;
        }
        if (path.startsWith(TASKS_PREFIX + "/") && path.length() > TASKS_PREFIX.length() + 1) {
            getTask(exchange, requestId, path.substring(TASKS_PREFIX.length() + 1));
            return;
        }
        if (path.equals(API_PREFIX + "leases")) {
            listLeases(exchange, requestId);
            return;
        }
        switch (path) {
            case API_PREFIX + "health" -> respond(exchange, requestId, 200, JsonWriter.write(health()));
            case API_PREFIX + "live" -> respond(exchange, requestId, 200, JsonWriter.write(live()));
            case API_PREFIX + "ready" -> respond(exchange, requestId, 200, JsonWriter.write(ready()));
            case API_PREFIX + "time" -> sendTime(exchange, requestId);
            case API_PREFIX + "info" -> respond(exchange, requestId, 200, JsonWriter.write(info()));
            case API_PREFIX + "events" -> listEvents(exchange, requestId);
            case API_PREFIX + "server/status" -> sendServerStatus(exchange, requestId);
            case API_PREFIX + "server/players" -> sendPlayers(exchange, requestId);
            case API_PREFIX + "server/world/block" -> sendBlock(exchange, requestId);
            case API_PREFIX + "server/world/time" -> sendWorldTime(exchange, requestId);
            case API_PREFIX + "client/status" -> sendClientStatus(exchange, requestId);
            case API_PREFIX + "client/screen/tree" -> sendScreenTree(exchange, requestId);
            default -> error(exchange, requestId, 404, "NOT_FOUND", "Unknown endpoint: " + path);
        }
    }

    private void routePost(HttpExchange exchange, String requestId, String path, byte[] body) throws IOException {
        if (path.equals(TASKS_PREFIX)) {
            postTask(exchange, requestId, body);
            return;
        }
        if (path.equals(API_PREFIX + "events/ticket")) {
            mintTicket(exchange, requestId);
            return;
        }
        if (path.equals(API_PREFIX + "client/input/key")) {
            pressClientKey(exchange, requestId, body);
            return;
        }
        if (path.equals(API_PREFIX + "client/screenshot")) {
            captureScreenshot(exchange, requestId);
            return;
        }
        if (path.equals(API_PREFIX + "leases")) {
            acquireLease(exchange, requestId, body);
            return;
        }
        if (path.startsWith(API_PREFIX + "leases/") && path.endsWith("/renew")) {
            renewLease(exchange, requestId, path.substring((API_PREFIX + "leases/").length(),
                    path.length() - "/renew".length()), body);
            return;
        }
        error(exchange, requestId, 404, "NOT_FOUND", "Unknown endpoint: " + path);
    }

    private void routeDelete(HttpExchange exchange, String requestId, String path) throws IOException {
        if (path.startsWith(TASKS_PREFIX + "/") && path.length() > TASKS_PREFIX.length() + 1) {
            String id = path.substring(TASKS_PREFIX.length() + 1);
            if (!id.matches("[0-9a-fA-F-]{8,64}")) {
                error(exchange, requestId, 404, "NOT_FOUND", "Unknown task: " + id);
                return;
            }
            cancelTask(exchange, requestId, id);
            return;
        }
        if (path.startsWith(API_PREFIX + "leases/") && path.length() > (API_PREFIX + "leases/").length()) {
            String id = path.substring((API_PREFIX + "leases/").length());
            if (!id.matches("[0-9a-fA-F-]{8,64}")) {
                error(exchange, requestId, 404, "NOT_FOUND", "Unknown lease: " + id);
                return;
            }
            releaseLease(exchange, requestId, id);
            return;
        }
        error(exchange, requestId, 404, "NOT_FOUND", "Unknown endpoint: " + path);
    }

    // ------------------------------------------------------------------
    // Read endpoints
    // ------------------------------------------------------------------

    private Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("status", "ok");
        return body;
    }

    private Map<String, Object> live() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("live", true);
        return body;
    }

    private Map<String, Object> ready() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("readiness", runtime.readiness());
        Map<String, Object> states = new LinkedHashMap<>();
        states.put("http", true);
        states.put("worldReady", runtime.serverRunning());
        states.put("clientJoined", false);
        body.put("states", states);
        return body;
    }

    private void sendTime(HttpExchange exchange, String requestId) throws IOException {
        SnapshotResult result = runtime.trySnapshot();
        if (result.timedOut()) {
            error(exchange, requestId, 503, "SERVER_BUSY",
                    "Server thread busy; time snapshot timed out. Retry shortly.");
            return;
        }
        Map<String, Object> serverTick = new LinkedHashMap<>();
        if (result.serverRunning() && result.snapshot() != null) {
            serverTick.put("available", true);
            serverTick.put("value", result.snapshot().tickCount());
        } else {
            serverTick.put("available", false);
            serverTick.put("reason", "SERVER_NOT_RUNNING");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("wallClock", System.currentTimeMillis());
        body.put("monotonicNanos", System.nanoTime());
        body.put("serverTick", serverTick);
        respond(exchange, requestId, 200, JsonWriter.write(body));
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
        body.put("instanceId", config.instanceId());
        body.put("processSessionId", runtime.processSessionId());
        runtime.worldSessionId().ifPresent(id -> body.put("worldSessionId", id));
        body.put("physicalSide", runtime.physicalSide().id());
        body.put("availableLogicalSides", runtime.availableLogicalSides());
        body.put("scopes", java.util.List.copyOf(config.scopes()));
        Map<String, Object> support = new LinkedHashMap<>();
        support.put("minecraftVersion", runtime.minecraftVersion());
        support.put("javaVersion", Runtime.version().feature());
        support.put("platform", runtime.platform().id());
        support.put("platformVersion", runtime.platformVersion());
        body.put("support", support);
        int wsPort = runtime.wsBoundPort();
        if (wsPort >= 0) {
            Map<String, Object> events = new LinkedHashMap<>();
            events.put("scheme", "ws");
            events.put("host", "127.0.0.1");
            events.put("port", wsPort);
            body.put("events", events);
        }
        return body;
    }

    private void listEvents(HttpExchange exchange, String requestId) throws IOException {
        Map<String, List<String>> query = splitQuery(exchange.getRequestURI().getRawQuery());
        long after = 0;
        String afterRaw = single(query, "after");
        if (afterRaw != null) {
            try {
                after = Long.parseLong(afterRaw);
            } catch (NumberFormatException e) {
                error(exchange, requestId, 400, "INVALID_QUERY", "after must be an integer sequence number");
                return;
            }
        }
        if (after < 0) {
            error(exchange, requestId, 400, "INVALID_QUERY", "after must be >= 0");
            return;
        }
        int limit = 100;
        String limitRaw = single(query, "limit");
        if (limitRaw != null) {
            try {
                limit = Integer.parseInt(limitRaw);
            } catch (NumberFormatException e) {
                error(exchange, requestId, 400, "INVALID_QUERY", "limit must be an integer");
                return;
            }
        }
        limit = Math.clamp(limit, 1, 200);
        var eventLog = runtime.eventLog();
        List<dev.example.mapi.internal.events.EventLog.EventRecord> records = eventLog.eventsAfter(after);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        long oldest = eventLog.oldestSeq();
        if (after >= 0 && (after + 1) < oldest && !records.isEmpty()) {
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("from", after + 1);
            gap.put("to", records.getFirst().seq() - 1);
            body.put("gap", gap);
        }
        boolean truncated = records.size() > limit;
        List<Map<String, Object>> page = records.subList(0, Math.min(limit, records.size())).stream()
                .map(WebSocketConnection::eventObject)
                .toList();
        body.put("events", page);
        body.put("truncated", truncated);
        body.put("headSeq", eventLog.headSeq());
        body.put("oldestSeq", oldest);
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    private void mintTicket(HttpExchange exchange, String requestId) throws IOException {
        String ticket = runtime.ticketStore().mint();
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("scheme", "ws");
        ws.put("host", "127.0.0.1");
        ws.put("port", runtime.wsBoundPort());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("ticket", ticket);
        body.put("expiresAtEpochMs", System.currentTimeMillis() + TicketStore.TTL_MS);
        body.put("events", ws);
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    private void sendServerStatus(HttpExchange exchange, String requestId) throws IOException {
        SnapshotResult result = runtime.trySnapshot();
        if (result.timedOut()) {
            error(exchange, requestId, 503, "SERVER_BUSY",
                    "Server thread busy; status snapshot timed out. Retry shortly.");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        if (!result.serverRunning() || result.snapshot() == null) {
            body.put("running", false);
            respond(exchange, requestId, 200, JsonWriter.write(body));
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
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    // ------------------------------------------------------------------
    // Server read endpoints (owning-thread reads with bounded waits)
    // ------------------------------------------------------------------

    private void sendPlayers(HttpExchange exchange, String requestId) throws IOException {
        Map<String, List<String>> query = splitQuery(exchange.getRequestURI().getRawQuery());
        int limit = intParam(query, "limit", 50, exchange, requestId);
        if (limit < 0) {
            return;
        }
        int offset = intParam(query, "offset", 0, exchange, requestId);
        if (offset < 0) {
            return;
        }
        java.util.Set<String> fields = fieldSet(single(query, "fields"),
                java.util.Set.of("name", "id", "dimension", "position"), exchange, requestId);
        if (fields == null) {
            return;
        }
        var result = runtime.tryReadOnServerThread(() -> runtime.serverHandle().playersSupplier().get());
        if (!handleReadOutcome(exchange, requestId, result)) {
            return;
        }
        List<dev.example.mapi.internal.RawPlayerSnapshot> players = result.value();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("dataVersion", readDataVersion());
        List<Map<String, Object>> page = new java.util.ArrayList<>();
        int from = Math.min(offset, players.size());
        int to = Math.min(offset + limit, players.size());
        for (dev.example.mapi.internal.RawPlayerSnapshot player : players.subList(from, to)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (fields.contains("name")) {
                entry.put("name", player.name());
            }
            if (fields.contains("id")) {
                entry.put("id", player.id().toString());
            }
            if (fields.contains("dimension")) {
                entry.put("dimension", player.dimension());
            }
            if (fields.contains("position")) {
                entry.put("position", position(player.x(), player.y(), player.z()));
            }
            page.add(entry);
        }
        body.put("players", page);
        body.put("total", players.size());
        body.put("limit", limit);
        body.put("offset", offset);
        body.put("truncated", to < players.size());
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    private void sendBlock(HttpExchange exchange, String requestId) throws IOException {
        Map<String, List<String>> query = splitQuery(exchange.getRequestURI().getRawQuery());
        String dimension = single(query, "dimension");
        if (dimension == null || dimension.isBlank()) {
            error(exchange, requestId, 400, "INVALID_QUERY", "dimension is required");
            return;
        }
        Integer x = coord(query, "x");
        Integer y = coord(query, "y");
        Integer z = coord(query, "z");
        if (x == null || y == null || z == null) {
            error(exchange, requestId, 400, "INVALID_QUERY", "integer x, y, and z are required");
            return;
        }
        var result = runtime.tryReadOnServerThread(() -> runtime.serverHandle().blockSupplier(dimension, x, y, z)
                .get());
        if (result.failure() instanceof dev.example.mapi.internal.UnknownDimensionException) {
            error(exchange, requestId, 404, "DIMENSION_NOT_FOUND", result.failure().getMessage());
            return;
        }
        if (!handleReadOutcome(exchange, requestId, result)) {
            return;
        }
        dev.example.mapi.internal.RawBlockRead block = result.value();
        if (block == null) {
            error(exchange, requestId, 409, "CHUNK_UNLOADED",
                    "The containing chunk is not loaded (loaded-only policy). Load it or use a different "
                            + "chunk policy once available.");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("dataVersion", readDataVersion());
        body.put("dimension", block.dimension());
        body.put("position", position(block.x(), block.y(), block.z()));
        body.put("blockId", block.blockId());
        body.put("properties", block.properties());
        body.put("policy", "loadedOnly");
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    private void sendWorldTime(HttpExchange exchange, String requestId) throws IOException {
        Map<String, List<String>> query = splitQuery(exchange.getRequestURI().getRawQuery());
        String dimension = single(query, "dimension");
        if (dimension == null || dimension.isBlank()) {
            error(exchange, requestId, 400, "INVALID_QUERY", "dimension is required");
            return;
        }
        var result = runtime.tryReadOnServerThread(() -> runtime.serverHandle().timeSupplier(dimension).get());
        if (result.failure() instanceof dev.example.mapi.internal.UnknownDimensionException) {
            error(exchange, requestId, 404, "DIMENSION_NOT_FOUND", result.failure().getMessage());
            return;
        }
        if (!handleReadOutcome(exchange, requestId, result)) {
            return;
        }
        dev.example.mapi.internal.RawWorldTime time = result.value();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("dimension", dimension.trim());
        body.put("gameTime", time.gameTime());
        body.put("overworldClockTime", time.overworldClockTime());
        body.put("defaultClockTime", time.defaultClockTime());
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    private <T> boolean handleReadOutcome(HttpExchange exchange, String requestId,
            MapiRuntime.ReadResult<T> result) throws IOException {
        if (result.failure() != null) {
            logger.warn("MAPI: server read failed", result.failure());
            error(exchange, requestId, 500, "INTERNAL", "Server read failed");
            return false;
        }
        if (result.timedOut()) {
            error(exchange, requestId, 503, "SERVER_BUSY",
                    "Server thread busy; read timed out. Retry shortly.");
            return false;
        }
        if (!result.serverRunning()) {
            error(exchange, requestId, 409, "WRONG_STATE", "No active server session.");
            return false;
        }
        return true;
    }

    private Map<String, Object> position(double x, double y, double z) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("x", x);
        position.put("y", y);
        position.put("z", z);
        return position;
    }

    private int readDataVersion() {
        var result = runtime.tryReadOnServerThread(() -> runtime.serverHandle().dataVersionSupplier().get());
        return result.ok() && result.value() != null ? result.value() : -1;
    }

    private Integer coord(Map<String, List<String>> query, String name) {
        String raw = single(query, name);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intParam(Map<String, List<String>> query, String name, int fallback, HttpExchange exchange,
            String requestId) throws IOException {
        String raw = single(query, name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            error(exchange, requestId, 400, "INVALID_QUERY", name + " must be an integer");
            return -1;
        }
    }

    private java.util.Set<String> fieldSet(String raw, java.util.Set<String> allowed, HttpExchange exchange,
            String requestId) throws IOException {
        if (raw == null || raw.isBlank()) {
            return allowed;
        }
        java.util.Set<String> requested = new java.util.LinkedHashSet<>();
        for (String field : raw.split(",")) {
            String name = field.trim();
            if (!allowed.contains(name)) {
                error(exchange, requestId, 400, "INVALID_QUERY",
                        "Unknown field '" + name + "'; allowed: " + allowed);
                return null;
            }
            requested.add(name);
        }
        return requested;
    }

    // ------------------------------------------------------------------
    // Client read endpoints (owning-thread reads with bounded waits)
    // ------------------------------------------------------------------

    private void sendClientStatus(HttpExchange exchange, String requestId) throws IOException {
        var ops = runtime.clientOps();
        if (ops == null) {
            error(exchange, requestId, 409, "WRONG_STATE",
                    "No client operations on this process (dedicated server or client not initialized).");
            return;
        }
        var result = runtime.<dev.example.mapi.internal.client.ClientStatusSnapshot>tryReadOnClientThread(
                ops::status);
        if (!handleReadOutcome(exchange, requestId, result)) {
            return;
        }
        dev.example.mapi.internal.client.ClientStatusSnapshot status = result.value();
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("width", status.windowWidth());
        window.put("height", status.windowHeight());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("window", window);
        body.put("guiScale", status.guiScale());
        if (status.currentScreenClass() != null) {
            body.put("currentScreenClass", status.currentScreenClass());
        }
        body.put("playerPresent", status.playerPresent());
        if (status.dimension() != null) {
            body.put("dimension", status.dimension());
        }
        if (status.gameTime() != null) {
            body.put("gameTime", status.gameTime());
        }
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    private void sendScreenTree(HttpExchange exchange, String requestId) throws IOException {
        var ops = runtime.clientOps();
        if (ops == null) {
            error(exchange, requestId, 409, "WRONG_STATE",
                    "No client operations on this process (dedicated server or client not initialized).");
            return;
        }
        var result = runtime.<dev.example.mapi.internal.client.ScreenNode>tryReadOnClientThread(ops::screenTree);
        if (!handleReadOutcome(exchange, requestId, result)) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("coverage", "best-effort");
        body.put("root", screenNodeJson(result.value()));
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    private Map<String, Object> screenNodeJson(dev.example.mapi.internal.client.ScreenNode node) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (node.widgetClass() != null) {
            body.put("widgetClass", node.widgetClass());
        }
        if (node.label() != null) {
            body.put("label", node.label());
        }
        if (node.x() != null) {
            body.put("x", node.x());
        }
        if (node.y() != null) {
            body.put("y", node.y());
        }
        if (node.width() != null) {
            body.put("width", node.width());
        }
        if (node.height() != null) {
            body.put("height", node.height());
        }
        body.put("children", node.children().stream().map(this::screenNodeJson).toList());
        return body;
    }

    private void pressClientKey(HttpExchange exchange, String requestId, byte[] body) throws IOException {
        var ops = runtime.clientOps();
        if (ops == null) {
            error(exchange, requestId, 409, "WRONG_STATE",
                    "No client operations on this process (dedicated server or client not initialized).");
            return;
        }
        Object parsed;
        try {
            parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        } catch (ParseException e) {
            error(exchange, requestId, 400, "INVALID_JSON", e.getMessage());
            return;
        }
        if (!(parsed instanceof Map<?, ?> request)) {
            error(exchange, requestId, 400, "INVALID_JSON", "Request body must be a JSON object");
            return;
        }
        if (!sessionPreconditionsHold(exchange, requestId, request)) {
            return;
        }
        Object mode = request.get("mode");
        if (mode != null && !"input".equals(mode)) {
            error(exchange, requestId, 400, "INVALID_PAYLOAD",
                    "This endpoint is input-mode only; mode must be 'input' or absent (no silent fallbacks).");
            return;
        }
        Object mappingObj = request.get("mapping");
        Object actionObj = request.get("action");
        if (!(mappingObj instanceof String mapping) || mapping.isBlank()
                || !(actionObj instanceof String action) || action.isBlank()) {
            error(exchange, requestId, 400, "INVALID_PAYLOAD",
                    "Fields 'mapping' (string) and 'action' (press|release|tap) are required");
            return;
        }
        var result = runtime.<dev.example.mapi.internal.client.KeyActionResult>tryReadOnClientThread(
                () -> ops.pressKey(mapping, action));
        if (result.failure() instanceof MapiClientOps.UnknownMappingException
                || result.failure() instanceof IllegalArgumentException) {
            error(exchange, requestId, 400, "INVALID_PAYLOAD", result.failure().getMessage());
            return;
        }
        if (!handleReadOutcome(exchange, requestId, result)) {
            return;
        }
        dev.example.mapi.internal.client.KeyActionResult actionResult = result.value();
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("protocolVersion", PROTOCOL_VERSION);
        responseBody.put("mode", "input");
        responseBody.put("mapping", actionResult.mapping());
        responseBody.put("action", actionResult.action());
        if (actionResult.isDown() != null) {
            responseBody.put("isDown", actionResult.isDown());
        }
        runtime.eventLog().publish("client.input.key", "api-originated", Map.of(
                "mapping", actionResult.mapping(),
                "action", actionResult.action()));
        respond(exchange, requestId, 200, JsonWriter.write(responseBody));
    }

    private void captureScreenshot(HttpExchange exchange, String requestId) throws IOException {
        var ops = runtime.clientOps();
        if (ops == null) {
            error(exchange, requestId, 409, "WRONG_STATE",
                    "No client operations on this process (dedicated server or client not initialized).");
            return;
        }
        long frameId = frameCounter.incrementAndGet();
        var result = runtime.<dev.example.mapi.internal.client.ScreenshotResult>tryReadOnClientThread(
                () -> ops.captureScreenshot(frameId));
        if (!handleReadOutcome(exchange, requestId, result)) {
            return;
        }
        dev.example.mapi.internal.client.ScreenshotResult screenshot = result.value();
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("protocolVersion", PROTOCOL_VERSION);
        responseBody.put("frameId", screenshot.frameId());
        responseBody.put("path", screenshot.path());
        responseBody.put("width", screenshot.width());
        responseBody.put("height", screenshot.height());
        responseBody.put("bytes", screenshot.bytes());
        runtime.eventLog().publish("client.screenshot", "api-originated", Map.of(
                "frameId", screenshot.frameId(),
                "path", screenshot.path()));
        respond(exchange, requestId, 200, JsonWriter.write(responseBody));
    }

    // ------------------------------------------------------------------
    // Lease endpoints (spec §5.3)
    // ------------------------------------------------------------------

    private String holderFingerprint() {
        return sha256Hex(config.httpToken().getBytes(StandardCharsets.UTF_8)).substring(0, 8);
    }

    private boolean scopeCoversLease(String leaseType) {
        String required = switch (leaseType) {
            case "client.input", "client.ui", "client.camera" ->
                    dev.example.mapi.internal.auth.Scope.CLIENT_CONTROL;
            case "world.bulkEdit" -> dev.example.mapi.internal.auth.Scope.WORLD_WRITE;
            case "server.tick" -> dev.example.mapi.internal.auth.Scope.LIFECYCLE_MANAGE;
            default -> dev.example.mapi.internal.auth.Scope.OBSERVE;
        };
        return config.scopes().contains(required);
    }

    private void acquireLease(HttpExchange exchange, String requestId, byte[] body) throws IOException {
        Object parsed;
        try {
            parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        } catch (ParseException e) {
            error(exchange, requestId, 400, "INVALID_JSON", e.getMessage());
            return;
        }
        if (!(parsed instanceof Map<?, ?> request)) {
            error(exchange, requestId, 400, "INVALID_JSON", "Request body must be a JSON object");
            return;
        }
        Object leaseObj = request.get("lease");
        if (!(leaseObj instanceof String leaseType) || leaseType.isBlank()) {
            error(exchange, requestId, 400, "INVALID_PAYLOAD", "Field 'lease' (string) is required");
            return;
        }
        if (!scopeCoversLease(leaseType)) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("required", "scope for " + leaseType);
            error(exchange, requestId, 403, "FORBIDDEN_SCOPE",
                    "Token lacks the scope required for lease " + leaseType, extra);
            return;
        }
        Long ttlMs = request.get("ttlMs") instanceof Number number ? number.longValue() : null;
        String conflict = request.get("conflict") instanceof String s ? s : null;
        try {
            var snapshot = runtime.leaseManager().acquire(leaseType, ttlMs, conflict, holderFingerprint());
            respond(exchange, requestId, 200, JsonWriter.write(leaseJson(snapshot)));
        } catch (dev.example.mapi.internal.lease.LeaseManager.LeaseHeldException e) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("held", leaseJson(e.held));
            error(exchange, requestId, 409, "LEASE_HELD", e.getMessage(), extra);
        } catch (IllegalArgumentException e) {
            error(exchange, requestId, 400, "INVALID_PAYLOAD", e.getMessage());
        }
    }

    private void renewLease(HttpExchange exchange, String requestId, String id, byte[] body) throws IOException {
        Long ttlMs = null;
        try {
            Object parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> request && request.get("ttlMs") instanceof Number number) {
                ttlMs = number.longValue();
            }
        } catch (ParseException e) {
            error(exchange, requestId, 400, "INVALID_JSON", e.getMessage());
            return;
        }
        try {
            var snapshot = runtime.leaseManager().renew(id, ttlMs);
            respond(exchange, requestId, 200, JsonWriter.write(leaseJson(snapshot)));
        } catch (IllegalArgumentException e) {
            error(exchange, requestId, 404, "NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            error(exchange, requestId, 409, "WRONG_STATE", e.getMessage());
        }
    }

    private void releaseLease(HttpExchange exchange, String requestId, String id) throws IOException {
        try {
            var snapshot = runtime.leaseManager().release(id);
            respond(exchange, requestId, 200, JsonWriter.write(leaseJson(snapshot)));
        } catch (IllegalArgumentException e) {
            error(exchange, requestId, 404, "NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            error(exchange, requestId, 409, "WRONG_STATE", e.getMessage());
        }
    }

    private void listLeases(HttpExchange exchange, String requestId) throws IOException {
        var leases = runtime.leaseManager().list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("leases", leases.stream().map(this::leaseJson).toList());
        body.put("total", leases.size());
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    private Map<String, Object> leaseJson(dev.example.mapi.internal.lease.LeaseManager.Snapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", snapshot.id());
        body.put("lease", snapshot.lease());
        body.put("state", snapshot.state());
        body.put("acquiredAtEpochMs", snapshot.acquiredAtEpochMs());
        body.put("expiresAtEpochMs", snapshot.expiresAtEpochMs());
        body.put("holder", snapshot.holder());
        return body;
    }

    // ------------------------------------------------------------------
    // Task endpoints
    // ------------------------------------------------------------------

    private void listTasks(HttpExchange exchange, String requestId) throws IOException {
        Map<String, List<String>> query = splitQuery(exchange.getRequestURI().getRawQuery());
        String state = single(query, "state");
        if (state != null && TaskManager.State.fromWireName(state) == null) {
            error(exchange, requestId, 400, "INVALID_QUERY", "Unknown state filter: " + state);
            return;
        }
        int limit = 50;
        String limitRaw = single(query, "limit");
        if (limitRaw != null) {
            try {
                limit = Integer.parseInt(limitRaw);
            } catch (NumberFormatException e) {
                error(exchange, requestId, 400, "INVALID_QUERY", "limit must be an integer");
                return;
            }
        }
        TaskManager.Listing listing = runtime.taskManager().list(state, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("tasks", listing.tasks().stream().map(this::taskJson).toList());
        body.put("truncated", listing.truncated());
        body.put("total", listing.total());
        respond(exchange, requestId, 200, JsonWriter.write(body));
    }

    private void getTask(HttpExchange exchange, String requestId, String id) throws IOException {
        TaskSnapshot snapshot = runtime.taskManager().get(id);
        if (snapshot == null) {
            error(exchange, requestId, 404, "NOT_FOUND", "Unknown task: " + id);
            return;
        }
        respond(exchange, requestId, 200, JsonWriter.write(taskJson(snapshot)));
    }

    private void cancelTask(HttpExchange exchange, String requestId, String id) throws IOException {
        TaskSnapshot existing = runtime.taskManager().get(id);
        if (existing == null) {
            error(exchange, requestId, 404, "NOT_FOUND", "Unknown task: " + id);
            return;
        }
        if (TaskManager.State.fromWireName(existing.state()).isTerminalState()) {
            error(exchange, requestId, 409, "WRONG_STATE",
                    "Task already finished with state " + existing.state() + "; cancellation has no effect.");
            return;
        }
        TaskSnapshot snapshot = runtime.taskManager().cancel(id);
        respond(exchange, requestId, 200, JsonWriter.write(taskJson(snapshot)));
    }

    /**
     * Session preconditions (spec §1.1/§2.3): mutations may carry
     * {@code expectedWorldSessionId} / {@code expectedConnectionSessionId};
     * a mismatch rejects with 409 STALE_SESSION carrying the current ids.
     *
     * @return true when the preconditions hold (or none were supplied)
     */
    private boolean sessionPreconditionsHold(HttpExchange exchange, String requestId, Map<?, ?> request)
            throws IOException {
        if (request.get("expectedWorldSessionId") instanceof String expected) {
            String current = runtime.worldSessionId().orElse(null);
            if (!expected.equals(current)) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("currentWorldSessionId", current);
                error(exchange, requestId, 409, "STALE_SESSION",
                        "World session changed since the request was formed.", extra);
                return false;
            }
        }
        if (request.get("expectedConnectionSessionId") instanceof String expectedConnection) {
            // Client connection sessions do not exist yet (slice 0.6 open
            // item); any supplied expectation therefore cannot match.
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("currentConnectionSessionId", null);
            extra.put("reason", "CONNECTION_SESSION_UNAVAILABLE");
            error(exchange, requestId, 409, "STALE_SESSION",
                    "Connection sessions are not tracked yet; expected '"
                            + expectedConnection + "' cannot match.", extra);
            return false;
        }
        return true;
    }

    private void postTask(HttpExchange exchange, String requestId, byte[] body) throws IOException {
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String bodyHash = sha256Hex(body);
        String scopeKey = tokenScope();
        if (idempotencyKey != null) {
            String key = idempotencyKey.trim();
            if (key.isEmpty() || key.length() > 128) {
                error(exchange, requestId, 400, "INVALID_HEADER",
                        "Idempotency-Key must be 1..128 characters");
                return;
            }
            IdempotencyStore.Outcome prior = idempotencyStore.find(scopeKey, key, bodyHash);
            if (prior == IdempotencyStore.Outcome.MISMATCH) {
                error(exchange, requestId, 422, "IDEMPOTENCY_MISMATCH",
                        "This Idempotency-Key was already used with a different request body.");
                return;
            }
            if (prior != null) {
                exchange.getResponseHeaders().set("Idempotent-Replay", "true");
                if (prior.locationHeader() != null) {
                    exchange.getResponseHeaders().set("Location", prior.locationHeader());
                }
                respond(exchange, requestId, prior.status(), prior.responseBody());
                return;
            }
        }
        Object parsed;
        try {
            parsed = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        } catch (ParseException e) {
            error(exchange, requestId, 400, "INVALID_JSON", e.getMessage());
            return;
        }
        if (!(parsed instanceof Map<?, ?> request)) {
            error(exchange, requestId, 400, "INVALID_JSON", "Request body must be a JSON object");
            return;
        }
        if (!sessionPreconditionsHold(exchange, requestId, request)) {
            return;
        }
        Object kindObj = request.get("kind");
        if (!(kindObj instanceof String kind) || kind.isBlank()) {
            error(exchange, requestId, 400, "INVALID_PAYLOAD", "Field 'kind' (string) is required");
            return;
        }
        Object payloadObj = request.get("payload");
        if (payloadObj != null && !(payloadObj instanceof Map)) {
            error(exchange, requestId, 400, "INVALID_PAYLOAD", "Field 'payload' must be an object");
            return;
        }
        Long deadlineMs = null;
        if (request.get("deadlineMs") instanceof Number number) {
            deadlineMs = number.longValue();
        } else if (request.get("deadlineMs") != null) {
            error(exchange, requestId, 400, "INVALID_PAYLOAD", "Field 'deadlineMs' must be a number");
            return;
        }
        TaskSnapshot snapshot;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) payloadObj;
            snapshot = runtime.taskManager().submit(kind, payload, deadlineMs);
        } catch (IllegalArgumentException e) {
            error(exchange, requestId, 400, "UNSUPPORTED", e.getMessage());
            return;
        }
        String json = JsonWriter.write(taskJson(snapshot));
        String location = TASKS_PREFIX + "/" + snapshot.id();
        if (idempotencyKey != null) {
            idempotencyStore.store(scopeKey, idempotencyKey.trim(), bodyHash,
                    IdempotencyStore.fingerprint(scopeKey, idempotencyKey.trim()), 202, json, location);
        }
        exchange.getResponseHeaders().set("Location", location);
        respond(exchange, requestId, 202, json);
    }

    private Map<String, Object> taskJson(TaskSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", snapshot.id());
        body.put("kind", snapshot.kind());
        body.put("state", snapshot.state());
        body.put("createdAtEpochMs", snapshot.createdAtEpochMs());
        body.put("deadlineEpochMs", snapshot.deadlineEpochMs());
        if (snapshot.finishedAtEpochMs() != null) {
            body.put("finishedAtEpochMs", snapshot.finishedAtEpochMs());
        }
        if (snapshot.progress() != null) {
            body.put("progress", snapshot.progress());
        }
        if (snapshot.result() != null) {
            body.put("result", snapshot.result());
        }
        body.put("partialEffects", snapshot.partialEffects());
        body.put("cleanup", snapshot.cleanup());
        if (snapshot.error() != null) {
            body.put("error", snapshot.error());
        }
        return body;
    }

    private Map<String, List<String>> splitQuery(String rawQuery) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            query.computeIfAbsent(urlDecode(key), k -> new java.util.ArrayList<>()).add(urlDecode(value));
        }
        return query;
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String single(Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
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

    private String tokenScope() {
        return sha256Hex(config.httpToken().getBytes(StandardCharsets.UTF_8)).substring(0, 16)
                + '|' + runtime.processSessionId();
    }

    private static String resolveRequestId(HttpExchange exchange) {
        String incoming = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (incoming != null) {
            String trimmed = incoming.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= 64 && trimmed.matches("[\\x21-\\x7E]+")) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
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
        String expected = "http://localhost:" + boundPort();
        String alt = "http://127.0.0.1:" + boundPort();
        return origin.equals(expected) || origin.equals(alt);
    }

    private int boundPort() {
        return httpServer != null ? httpServer.getAddress().getPort() : config.httpPort();
    }

    /**
     * Reads the request body with the documented size bound. Declared
     * Content-Length is validated before any byte is read.
     *
     * @return the body bytes, or {@code null} when the body exceeds the limit
     */
    private static byte[] readBody(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            long value;
            try {
                value = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (value > MAX_BODY_BYTES) {
                return null;
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = in.readNBytes(MAX_BODY_BYTES + 1);
            if (buffer.length > MAX_BODY_BYTES) {
                return null;
            }
            return buffer;
        }
    }

    // ------------------------------------------------------------------
    // Response helpers
    // ------------------------------------------------------------------

    private static void respond(HttpExchange exchange, String requestId, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-MAPI-Protocol-Version", String.valueOf(PROTOCOL_VERSION));
        exchange.getResponseHeaders().set("X-MAPI-Request-Id", requestId);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void error(HttpExchange exchange, String requestId, int status, String code, String message)
            throws IOException {
        error(exchange, requestId, status, code, message, Map.of());
    }

    private static void error(HttpExchange exchange, String requestId, int status, String code, String message,
            Map<String, Object> extra) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("requestId", requestId);
        error.putAll(extra);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("protocolVersion", PROTOCOL_VERSION);
        respond(exchange, requestId, status, JsonWriter.write(body));
    }
}
