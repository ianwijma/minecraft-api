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
    private final OperationRegistry operations = new OperationRegistry();
    private final OperationGuard guard = new OperationGuard();

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
        registerOperations();
        initRoutes();
    }

    private void registerOperations() {
        Set<ExecutionMode> privileged = Set.of(ExecutionMode.PRIVILEGED);
        Set<ExecutionMode> rawInput = Set.of(ExecutionMode.RAW_INPUT);
        operations.register(new OperationDescriptor("server.ticks.lease",
                "Acquire the exclusive tick-control lease",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.LOCAL, false, privileged));
        operations.register(new OperationDescriptor("server.ticks.freeze",
                "Freeze the server tick loop",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.unfreeze",
                "Unfreeze the server tick loop",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.rate",
                "Set the target tick rate within configured bounds",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.step",
                "Step a bounded number of simulation ticks",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.step-and-observe",
                "Step a bounded number of simulation ticks and capture a bounded observation",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.sprint",
                "Sprint a bounded number of simulation ticks",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.stop",
                "Stop stepping or sprinting",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.snapshots.capture",
                "Capture and retain a bounded observation at the current boundary",
                Set.of(), false, SideEffectClass.READ_ONLY, false, Set.of()));
        operations.register(new OperationDescriptor("server.snapshots.diff",
                "Compare two retained snapshots",
                Set.of(), false, SideEffectClass.READ_ONLY, false, Set.of()));
        operations.register(new OperationDescriptor("server.commands.dispatch",
                "Dispatch a server command in the console context (administrative access)",
                Set.of(Scope.OPERATIONS_UNRESTRICTED), false, SideEffectClass.UNRESTRICTED,
                false, privileged));
        operations.register(new OperationDescriptor("process.shutdown",
                "Request a graceful local shutdown of the process (administrative access)",
                Set.of(Scope.OPERATIONS_UNRESTRICTED), false, SideEffectClass.UNRESTRICTED,
                false, privileged));
    }

    /** @return the operation metadata registry (for introspection endpoints/tests) */
    public OperationRegistry operations() {
        return operations;
    }

    /** @return every GET route path (introspection for the OpenAPI drift test) */
    public java.util.Set<String> getRoutePaths() {
        return java.util.Set.copyOf(getRoutes.keySet());
    }

    /** @return every POST route path (introspection for the OpenAPI drift test) */
    public java.util.Set<String> postRoutePaths() {
        return java.util.Set.copyOf(postRoutes.keySet());
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
        workers = new ThreadPoolExecutor(2, 2 + EventStreamHandler.MAX_CONCURRENT_STREAMS,
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

    private void streamMiddleware(HttpExchange exchange) throws IOException {
        try {
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
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (config.authRequired() && !authorized(authorization)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"mapi\"");
            error(exchange, ProblemCode.UNAUTHORIZED, "Missing or invalid bearer token");
            return;
        }
        if (declaredBodyTooLarge(exchange)) {
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

    /** A GET handler: receives the exchange (query params parsed per handler). */
    private interface GetHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** A POST handler: receives the exchange, parsed JSON body, and grants. */
    private interface PostHandler {
        void handle(HttpExchange exchange, Map<String, Object> body,
                java.util.Set<Scope> grants) throws IOException;
    }

    private final Map<String, GetHandler> getRoutes = new LinkedHashMap<>();
    private final Map<String, PostHandler> postRoutes = new LinkedHashMap<>();

    private void initRoutes() {
        getRoutes.put(API_PREFIX + "health", exchange -> respond(exchange, 200, JsonWriter.write(health())));
        getRoutes.put(API_PREFIX + "info", exchange -> respond(exchange, 200, JsonWriter.write(info())));
        getRoutes.put(API_PREFIX + "operations", exchange -> respond(exchange, 200, JsonWriter.write(operations.toMap())));
        getRoutes.put(API_PREFIX + "server/status", this::sendServerStatus);
        getRoutes.put(API_PREFIX + "server/world", this::sendWorldInfo);
        getRoutes.put(API_PREFIX + "client", this::sendClientInfo);
        getRoutes.put(API_PREFIX + "client/window", this::sendWindowInfo);
        getRoutes.put(API_PREFIX + "client/screenshots", this::sendScreenshot);
        getRoutes.put(API_PREFIX + "client/screen", this::sendScreenInfo);
        getRoutes.put(API_PREFIX + "client/worlds", this::sendWorldList);
        getRoutes.put(API_PREFIX + "logs", this::sendLogs);
        getRoutes.put(API_PREFIX + "server/ticks", this::sendTickState);
        getRoutes.put(API_PREFIX + "server/queries/players", this::sendPlayerQuery);
        getRoutes.put(API_PREFIX + "server/queries/entities", this::sendEntityQuery);
        getRoutes.put(API_PREFIX + "server/queries/block", this::sendBlockQuery);
        getRoutes.put(API_PREFIX + "server/queries/registries", this::sendRegistryList);
        getRoutes.put(API_PREFIX + "server/queries/registry", this::sendRegistryEntries);
        getRoutes.put(API_PREFIX + "events/stream", this::streamMiddleware);

        postRoutes.put(API_PREFIX + "server/ticks/lease", this::handleTickLease);
        postRoutes.put(API_PREFIX + "server/ticks/freeze", this::handleFreeze);
        postRoutes.put(API_PREFIX + "server/ticks/unfreeze", this::handleUnfreeze);
        postRoutes.put(API_PREFIX + "server/ticks/rate", this::handleTickRate);
        postRoutes.put(API_PREFIX + "server/ticks/step",
                (exchange, body, grants) -> handleTickStep(exchange, body, grants, false));
        postRoutes.put(API_PREFIX + "server/ticks/step-and-observe",
                (exchange, body, grants) -> handleTickStep(exchange, body, grants, true));
        postRoutes.put(API_PREFIX + "server/ticks/sprint", this::handleTickSprint);
        postRoutes.put(API_PREFIX + "server/ticks/stop", this::handleTickStop);
        postRoutes.put(API_PREFIX + "server/snapshots", this::handleSnapshotCapture);
        postRoutes.put(API_PREFIX + "server/snapshot-diffs", this::handleSnapshotDiff);
        postRoutes.put(API_PREFIX + "server/commands", this::handleCommand);
        postRoutes.put(API_PREFIX + "client/actions/hold-key", this::handleHoldKey);
        postRoutes.put(API_PREFIX + "client/movement/waypoints", this::handleWaypoints);
        postRoutes.put(API_PREFIX + "client/actions/click", this::handleClick);
        postRoutes.put(API_PREFIX + "client/worlds/load", this::handleWorldLoad);
        postRoutes.put(API_PREFIX + "client/worlds/delete", this::handleWorldDelete);
        postRoutes.put(API_PREFIX + "client/connect", this::handleConnect);
        postRoutes.put(API_PREFIX + "client/window/set-windowed",
                (exchange, body, grants) -> handleWindowSet(exchange, body, grants, "windowed"));
        postRoutes.put(API_PREFIX + "client/window/set-fullscreen",
                (exchange, body, grants) -> handleWindowSet(exchange, body, grants, "fullscreen"));
        postRoutes.put(API_PREFIX + "client/window/set-gui-scale",
                (exchange, body, grants) -> handleWindowSet(exchange, body, grants, "gui-scale"));
        postRoutes.put(API_PREFIX + "process/shutdown", this::handleShutdown);
        postRoutes.put(API_PREFIX + "server/lan", this::handleLanPublish);
        postRoutes.put(API_PREFIX + "server/lan/stop", this::handleLanStop);
    }

    private void routeGet(HttpExchange exchange, String token, String path) throws IOException {
        GetHandler handler = getRoutes.get(path);
        if (handler != null) {
            handler.handle(exchange);
            return;
        }
        if (path.startsWith(API_PREFIX + "jobs/")) {
            sendJobView(exchange, path.substring((API_PREFIX + "jobs/").length()));
            return;
        }
        error(exchange, ProblemCode.NOT_FOUND, "Unknown endpoint: " + path);
    }

    private void routePost(HttpExchange exchange, String token, String path) throws IOException {
        PostHandler handler = postRoutes.get(path);
        if (handler == null) {
            exchange.getResponseHeaders().set("Allow", "GET");
            error(exchange, ProblemCode.METHOD_NOT_ALLOWED,
                    "This endpoint only supports GET");
            return;
        }
        Map<String, Object> body = readJsonObject(exchange, path);
        java.util.Set<Scope> grants = scopeGrants.scopesForToken(
                bearerToken(exchange.getRequestHeaders().getFirst("Authorization")));
        handler.handle(exchange, body, grants);
    }

    // ------------------------------------------------------------------
    // POST handlers (server operations)
    // ------------------------------------------------------------------

    private void handleFreeze(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.ticks.freeze", grants, body);
        var service = requireTickControl();
        var lease = service.holderFor(stringField(body, "leaseId"));
        var state = runtime.callOnServerThread(() -> service.freeze(lease));
        respond(exchange, 200, JsonWriter.write(tickStateMap(state)));
    }

    private void handleUnfreeze(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.ticks.unfreeze", grants, body);
        var service = requireTickControl();
        var lease = service.holderFor(stringField(body, "leaseId"));
        var state = runtime.callOnServerThread(() -> service.unfreeze(lease));
        respond(exchange, 200, JsonWriter.write(tickStateMap(state)));
    }

    private void handleTickLease(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.ticks.lease", grants, body);
        var service = requireTickControl();
        long ttlSeconds = longField(body, "ttlSeconds", 300);
        if (ttlSeconds < 1 || ttlSeconds > 3600) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ttlSeconds must be between 1 and 3600");
        }
        String owner = "http:" + exchange.getRemoteAddress().getAddress();
        var lease = runtime.callOnServerThread(() -> service.acquireLease(owner, ttlSeconds * 1000));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("leaseId", lease.id());
        result.put("topic", lease.topic());
        result.put("expiresAtEpochMs", lease.expiresAtEpochMs());
        respond(exchange, 200, JsonWriter.write(result));
    }

    private void handleTickRate(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.ticks.rate", grants, body);
        var service = requireTickControl();
        Object rateValue = body.get("rate");
        if (!(rateValue instanceof Number rate) || !Float.isFinite(rate.floatValue())) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "rate must be a finite number");
        }
        var lease = service.holderFor(stringField(body, "leaseId"));
        float applied = runtime.callOnServerThread(() -> service.setTickRate(lease, rate.floatValue()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appliedTickRate", applied);
        result.put("rateBounds", service.rateBounds());
        respond(exchange, 200, JsonWriter.write(result));
    }

    private void handleTickStep(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants, boolean observe) throws IOException {
        checkAccess(observe ? "server.ticks.step-and-observe" : "server.ticks.step", grants, body);
        var service = requireTickControl();
        long ticks = longField(body, "ticks", -1);
        if (ticks < 1 || ticks > 10_000) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ticks must be between 1 and 10000");
        }
        var lease = service.holderFor(stringField(body, "leaseId"));
        String worldSessionId = runtime.worldLifecycle().currentSessionId().orElse(null);
        long deadline = System.currentTimeMillis() + 60_000;
        int stepTicks = (int) ticks;
        String label = observe ? stringField(body, "label") : null;

        var job = runtime.jobs().submit(observe ? "ticks.step-and-observe" : "ticks.step",
                java.util.Optional.ofNullable(worldSessionId), deadline, context -> {
                    var result = runtime.callOnServerThread(
                            () -> service.step(lease, stepTicks), 30_000);
                    context.milestone("stepped", Map.of(
                            "requested", result.requested(),
                            "completed", result.completed(),
                            "boundary", result.boundaryTickCount()));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("requested", result.requested());
                    out.put("completed", result.completed());
                    out.put("boundary", result.boundaryTickCount());
                    if (observe) {
                        var capture = runtime.snapshotCapture();
                        if (capture.isPresent()) {
                            var captured = capture.get().capture(
                                    label == null ? "step-and-observe" : label, 10, 50);
                            out.put("snapshotId", captured.id());
                            out.put("snapshotBoundary", captured.boundary());
                        } else {
                            out.put("snapshotCaptured", false);
                        }
                    }
                    return out;
                });
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("jobId", job.id());
        accepted.put("state", "PENDING");
        accepted.put("worldSessionId", worldSessionId);
        respond(exchange, 202, JsonWriter.write(accepted));
    }

    private void handleTickSprint(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.ticks.sprint", grants, body);
        var service = requireTickControl();
        long ticks = longField(body, "ticks", -1);
        if (ticks < 1 || ticks > 10_000) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ticks must be between 1 and 10000");
        }
        var lease = service.holderFor(stringField(body, "leaseId"));
        int sprintTicks = (int) ticks;
        dev.example.mapi.internal.tick.TickControlBackend.StepResult result =
                runtime.callOnServerThread(() -> service.sprint(lease, sprintTicks));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", result.requested());
        out.put("completed", result.completed());
        out.put("boundary", result.boundaryTickCount());
        out.put("note", "sprinting is asynchronous in vanilla; poll GET /server/ticks for completion");
        respond(exchange, 200, JsonWriter.write(out));
    }

    private void handleTickStop(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.ticks.stop", grants, body);
        var service = requireTickControl();
        var lease = service.holderFor(stringField(body, "leaseId"));
        boolean stopped = runtime.callOnServerThread(() -> {
            boolean a = service.stopStepping(lease);
            boolean b = service.stopSprinting(lease);
            return a || b;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stopped", stopped);
        out.put("state", tickStateMap(service.state()));
        respond(exchange, 200, JsonWriter.write(out));
    }

    private void handleSnapshotCapture(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.snapshots.capture", grants, body);
        var capture = runtime.snapshotCapture()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "world queries are not available on this loader bridge"));
        var captured = capture.capture(stringField(body, "label"),
                (int) longField(body, "maxPlayers", 10),
                (int) longField(body, "maxEntities", 50));
        respond(exchange, 200, JsonWriter.write(captured.toMap()));
    }

    private void handleSnapshotDiff(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.snapshots.diff", grants, body);
        String firstId = stringField(body, "firstId");
        String secondId = stringField(body, "secondId");
        if (firstId == null || secondId == null) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "firstId and secondId are required");
        }
        @SuppressWarnings("unchecked")
        java.util.List<String> includePaths = body.get("includePaths") instanceof java.util.List<?> list
                ? (java.util.List<String>) list
                : java.util.List.of();
        long maxChanges = longField(body, "maxChanges", 1000);
        var result = runtime.snapshots().diff(firstId, secondId,
                new dev.example.mapi.internal.snapshot.DiffOptions(Set.copyOf(includePaths),
                        (int) maxChanges),
                System.currentTimeMillis());
        respond(exchange, 200, JsonWriter.write(result.toMap()));
    }

    private void handleCommand(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.commands.dispatch", grants, body);
        var service = runtime.commands()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "command dispatch is not available on this loader bridge"));
        respond(exchange, 200, JsonWriter.write(service.dispatch(stringField(body, "command"))));
    }

    private void handleClick(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("client.ui.click", grants, body);
        var ui = runtime.clientBridge().ui()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "UI dispatch is not available on this process"));
        int x = (int) longField(body, "x", -1);
        int y = (int) longField(body, "y", -1);
        if (x < 0 || y < 0) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "x and y are required");
        }
        boolean consumed = runtime.callOnClientThread(() -> ui.click(x, y));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("screenId", ui.screenId());
        out.put("consumed", consumed);
        respond(exchange, 200, JsonWriter.write(out));
    }

    private void handleWorldLoad(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("client.worlds.load", grants, body);
        var worlds = runtime.clientBridge().worlds()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "world management is not available on this process"));
        String levelId = stringField(body, "levelId");
        if (levelId == null) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "levelId is required");
        }
        runtime.callOnClientThread(() -> {
            try {
                worlds.loadWorld(levelId);
            } catch (Exception e) {
                throw new ProblemException(ProblemCode.INTERNAL, "world load failed: " + e);
            }
            return null;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("levelId", levelId);
        out.put("note", "world load started asynchronously; poll GET /api/v1/server/world for phase ACTIVE");
        respond(exchange, 202, JsonWriter.write(out));
    }

    private void handleWorldDelete(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("client.worlds.delete", grants, body);
        var worlds = runtime.clientBridge().worlds()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "world management is not available on this process"));
        String levelId = stringField(body, "levelId");
        if (levelId == null) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "levelId is required");
        }
        // §14: destructive = grant + explicit intent.
        if (body.get("confirm") != Boolean.TRUE) {
            throw new ProblemException(ProblemCode.DESTRUCTIVE_INTENT_REQUIRED,
                    "deleting a world requires \"confirm\": true");
        }
        runtime.callOnClientThread(() -> {
            try {
                worlds.deleteWorld(levelId);
            } catch (Exception e) {
                throw new ProblemException(ProblemCode.INTERNAL, "world delete failed: " + e);
            }
            return null;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("levelId", levelId);
        out.put("deleted", true);
        respond(exchange, 200, JsonWriter.write(out));
    }

    private void handleConnect(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("client.connect", grants, body);
        var connect = runtime.clientBridge().connect()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "direct connection is not available on this process"));
        String address = stringField(body, "address");
        if (address == null || !address.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "address must be host[:port]");
        }
        // §9.2: allowlist enforced at the final connect boundary (host match;
        // host entries match any port, host:port entries match exactly).
        java.util.List<String> allowlist = runtime.config().clientConnectAllowlist();
        String host = address.contains(":")
                ? address.substring(0, address.indexOf(':')) : address;
        boolean allowed = allowlist.stream().anyMatch(entry ->
                entry.equalsIgnoreCase(address) || entry.equalsIgnoreCase(host)
                        || (entry.endsWith(".*") && host.startsWith(
                                entry.substring(0, entry.length() - 1))));
        if (!allowed) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE,
                    "address is not on the client.connect.allowlist",
                    Map.of("address", address));
        }
        runtime.callOnClientThread(() -> {
            try {
                connect.join(address);
            } catch (Exception e) {
                throw new ProblemException(ProblemCode.INTERNAL, "connect failed: " + e);
            }
            return null;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("address", address);
        out.put("note", "connection started; poll GET /api/v1/server/world for phase transitions");
        respond(exchange, 202, JsonWriter.write(out));
    }

    private void handleWaypoints(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        var service = runtime.movement()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "movement is not available on this process"));
        if (!(body.get("waypoints") instanceof java.util.List<?> rawWaypoints)) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "waypoints list is required");
        }
        if (rawWaypoints.isEmpty() || rawWaypoints.size() > 64) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "waypoints must be 1..64");
        }
        var waypoints = new java.util.ArrayList<dev.example.mapi.internal.client.MovementService.Waypoint>();
        for (Object raw : rawWaypoints) {
            if (!(raw instanceof Map<?, ?> waypoint)) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "each waypoint must be an object");
            }
            try {
                double yaw = waypoint.get("yaw") instanceof Number yawNumber
                        ? yawNumber.doubleValue() : 0d;
                double pitch = waypoint.get("pitch") instanceof Number pitchNumber
                        ? pitchNumber.doubleValue() : 0d;
                int ticks = waypoint.get("ticks") instanceof Number ticksNumber
                        ? ticksNumber.intValue() : 20;
                waypoints.add(new dev.example.mapi.internal.client.MovementService.Waypoint(
                        yaw, pitch, ticks));
            } catch (IllegalArgumentException e) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, e.getMessage());
            }
        }

        long deadline = longField(body, "deadlineEpochMs", System.currentTimeMillis() + 30_000);
        try {
            var receipt = service.executeWaypoints(waypoints, grants, deadline);
            respond(exchange, 200, JsonWriter.write(receipt));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ProblemException problem) {
                throw problem;
            }
            throw new ProblemException(ProblemCode.INTERNAL, "waypoints failed: " + e);
        }
    }

    private void handleLanPublish(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.lan.publish", grants, body);
        var backend = runtime.clientBridge().lan()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "LAN publication is not available on this process (integrated server only)"));
        if (!runtime.config().serverLanEnabled()) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE,
                    "LAN publication is disabled by server.lan.enabled=false (spec §9.3)");
        }
        runtime.worldLifecycle().requireActive(java.util.Optional.empty());
        int port = (int) longField(body, "port", 0);
        if (port < 0 || port > 65535) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "port must be 0..65535 (0 = game-assigned)");
        }
        String gamemode = stringField(body, "gamemode");
        boolean cheats = body.get("cheats") instanceof Boolean requested && requested;
        boolean published = runtime.callOnClientThread(() ->
                backend.publish(port, gamemode == null ? "" : gamemode, cheats));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("published", published);
        out.put("note", "the game port is now exposed on LAN independently of API authentication");
        respond(exchange, published ? 200 : 503, JsonWriter.write(out));
    }

    private void handleLanStop(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("server.lan.publish", grants, body);
        var backend = runtime.clientBridge().lan()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "LAN publication is not available on this process (integrated server only)"));
        if (!runtime.config().serverLanEnabled()) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE,
                    "LAN publication is disabled by server.lan.enabled=false (spec §9.3)");
        }
        boolean unpublished = runtime.callOnClientThread(backend::unpublish);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("unpublished", unpublished);
        respond(exchange, 200, JsonWriter.write(out));
    }

    private void sendScreenInfo(HttpExchange exchange) throws IOException {
        var ui = runtime.clientBridge().ui()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "UI inspection is not available on this process"));
        var widgets = runtime.callOnClientThread(ui::widgets);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("screenId", runtime.callOnClientThread(ui::screenId));
        java.util.List<Map<String, Object>> widgetMaps = new java.util.ArrayList<>();
        for (dev.example.mapi.internal.client.ClientBridge.UiBackend.WidgetNode node : widgets) {
            widgetMaps.add(node.toMap());
        }
        out.put("widgets", widgetMaps);
        respond(exchange, 200, JsonWriter.write(out));
    }

    private void sendWorldList(HttpExchange exchange) throws IOException {
        var worlds = runtime.clientBridge().worlds()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "world management is not available on this process"));
        try {
            var entries = worlds.listWorlds();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("protocolVersion", PROTOCOL_VERSION);
            java.util.List<Map<String, Object>> worldMaps = new java.util.ArrayList<>();
            for (dev.example.mapi.internal.client.ClientBridge.WorldsBackend.WorldEntry entry : entries) {
                worldMaps.add(entry.toMap());
            }
            out.put("worlds", worldMaps);
            respond(exchange, 200, JsonWriter.write(out));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ProblemException problem) {
                throw problem;
            }
            throw new ProblemException(ProblemCode.INTERNAL, "world list failed: " + e);
        }
    }

    private void handleShutdown(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        checkAccess("process.shutdown", grants, body);
        boolean accepted = runtime.requestProcessShutdown();
        if (!accepted) {
            throw new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                    "graceful shutdown is not implemented on this platform adapter");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("accepted", true);
        out.put("note", "graceful local shutdown requested; process-scoped sessions stay available");
        respond(exchange, 200, JsonWriter.write(out));
    }

    private void sendLogs(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        long cursor = 0;
        if (params.get("cursor") != null) {
            try {
                cursor = Long.parseLong(params.get("cursor"));
                if (cursor < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "cursor must be a non-negative integer");
            }
        }
        int limit = intParam(exchange, "limit", 200);
        var logs = runtime.logs();
        var result = logs.entriesAfter(cursor, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("attachedAtEpochMs", logs.attachedAtEpochMs());
        out.put("latestSeq", logs.latestSeq());
        if (result.gap()) {
            out.put("gap", true);
            out.put("droppedUpToSeq", result.droppedUpToSeq());
        }
        out.put("cursor", result.newCursor());
        out.put("entries", result.entries().stream()
                .map(dev.example.mapi.internal.logging.LogCaptureService.Entry::toMap).toList());
        respond(exchange, 200, JsonWriter.write(out));
    }

    // ------------------------------------------------------------------
    // Client handlers (spec §3, §9, §20)
    // ------------------------------------------------------------------

    private void sendClientInfo(HttpExchange exchange) throws IOException {
        var bridge = runtime.clientBridge();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("bridgeId", bridge.bridgeId());
        out.put("capabilities", bridge.supportedCapabilities().stream().sorted().toList());
        out.put("input", bridge.input().isPresent());
        out.put("screenshots", bridge.screenshots().isPresent());
        out.put("window", bridge.window().isPresent());
        bridge.input().ifPresent(input -> out.put("inputCoverage", java.util.Map.of(
                "callbackDispatch", input.coverage().callbackDispatch(),
                "keybindingState", input.coverage().keybindingState(),
                "helperPolling", input.coverage().helperPolling(),
                "screenDispatch", input.coverage().screenDispatch(),
                "unsupportedNativePolling", input.coverage().unsupportedNativePolling())));
        respond(exchange, 200, JsonWriter.write(out));
    }

    private void sendWindowInfo(HttpExchange exchange) throws IOException {
        var window = runtime.clientBridge().window()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "window control is not available on this process"));
        var state = runtime.callOnClientThread(window::state);
        respond(exchange, 200, JsonWriter.write(windowStateMap(state)));
    }

    private void handleWindowSet(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants, String operation) throws IOException {
        var window = runtime.clientBridge().window()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "window control is not available on this process"));
        var state = runtime.callOnClientThread(() -> switch (operation) {
            case "windowed" -> {
                int width = (int) longField(body, "width", -1);
                int height = (int) longField(body, "height", -1);
                if (width < 320 || width > 3840 || height < 240 || height > 2160) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST,
                            "width must be 320..3840 and height 240..2160");
                }
                yield window.setWindowed(width, height);
            }
            case "fullscreen" -> {
                if (!(body.get("fullscreen") instanceof Boolean fullscreen)) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST, "fullscreen must be a boolean");
                }
                yield window.setFullscreen(fullscreen);
            }
            default -> {
                int scale = (int) longField(body, "guiScale", -1);
                if (scale < 0 || scale > 4) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST, "guiScale must be 0..4");
                }
                yield window.setGuiScale(scale);
            }
        });
        respond(exchange, 200, JsonWriter.write(windowStateMap(state)));
    }

    private Map<String, Object> windowStateMap(
            dev.example.mapi.internal.client.ClientBridge.WindowBackend.WindowState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("width", state.width());
        out.put("height", state.height());
        out.put("framebufferWidth", state.framebufferWidth());
        out.put("framebufferHeight", state.framebufferHeight());
        out.put("guiScale", state.guiScale());
        out.put("fullscreen", state.fullscreen());
        out.put("revision", state.revision());
        return out;
    }

    private void sendScreenshot(HttpExchange exchange) throws IOException {
        var screenshots = runtime.clientBridge().screenshots()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "screenshots are not available on this process"));
        // Phase 1 (client thread): schedule the GPU readback.
        var pending = runtime.callOnClientThread(screenshots::beginCapture);
        // Phase 2 (HTTP worker): poll the temp file — the PNG fills on a
        // later frame; blocking the client thread here would deadlock.
        long deadline = System.currentTimeMillis() + 5000;
        byte[] png;
        try {
            while (true) {
                long size = Files.exists(pending.tempPath())
                        ? Files.size(pending.tempPath()) : 0;
                if (size > 0) {
                    png = Files.readAllBytes(pending.tempPath());
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw new ProblemException(ProblemCode.SERVER_BUSY,
                            "GPU readback did not complete within 5000 ms");
                }
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProblemException(ProblemCode.SERVER_BUSY, "interrupted");
        } finally {
            try {
                Files.deleteIfExists(pending.tempPath());
            } catch (IOException ignored) {
                // cleanup is best-effort; response already built below if read
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSION);
        out.put("width", pending.width());
        out.put("height", pending.height());
        out.put("frame", pending.frame());
        out.put("guiScale", pending.guiScale());
        if (pending.screenId() != null) {
            out.put("screenId", pending.screenId());
        }
        out.put("capturedAtEpochMs", System.currentTimeMillis());
        out.put("pngBase64", java.util.Base64.getEncoder().encodeToString(png));
        respond(exchange, 200, JsonWriter.write(out));
    }

    private void handleHoldKey(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        var service = runtime.clientActions()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "client actions are not available on this process"));
        if (!(body.get("keyCode") instanceof Number keyCode)
                || !(body.get("ticks") instanceof Number ticks)) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "keyCode and ticks are required");
        }
        ExecutionMode mode = ExecutionMode.RAW_INPUT;
        if (body.get("executionMode") instanceof String requested) {
            mode = java.util.Arrays.stream(ExecutionMode.values())
                    .filter(value -> value.wireName().equals(requested))
                    .findFirst()
                    .orElseThrow(() -> new ProblemException(ProblemCode.BAD_REQUEST,
                            "unknown executionMode: " + requested));
        }
        long deadline = longField(body, "deadlineEpochMs", System.currentTimeMillis() + 10_000);
        try {
            var receipt = service.holdKey(new dev.example.mapi.internal.client.ActionDispatchService.ActionRequest(
                    "hold-key", mode, keyCode.intValue(), ticks.intValue(), deadline), grants);
            respond(exchange, 200, JsonWriter.write(receipt));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ProblemException problem) {
                throw problem;
            }
            throw new ProblemException(ProblemCode.INTERNAL, "hold-key failed: " + e);
        }
    }

    // ------------------------------------------------------------------
    // GET handlers (server observability)
    // ------------------------------------------------------------------

    private void sendWorldInfo(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        var lifecycle = runtime.worldLifecycle();
        body.put("phase", lifecycle.phase().name());
        lifecycle.currentSessionId().ifPresent(value -> body.put("worldSessionId", value));
        var bridge = runtime.serverBridge();
        body.put("bridgeId", bridge.bridgeId());
        body.put("capabilities", bridge.supportedCapabilities().stream().sorted().toList());
        body.put("tickControl", runtime.tickControl().isPresent());
        body.put("worldQueries", runtime.worldQueries().isPresent());
        body.put("commands", runtime.commands().isPresent());
        body.put("clocks", runtime.clocks().toMap());
        respond(exchange, 200, JsonWriter.write(body));
    }

    private void sendTickState(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", PROTOCOL_VERSION);
        var service = runtime.tickControl();
        if (service.isEmpty()) {
            body.put("available", false);
            respond(exchange, 200, JsonWriter.write(body));
            return;
        }
        var state = runtime.callOnServerThread(service.get()::state);
        body.put("available", true);
        body.putAll(tickStateMap(state));
        body.put("rateBounds", service.get().rateBounds());
        service.get().holderId().ifPresent(value -> body.put("leaseId", value));
        respond(exchange, 200, JsonWriter.write(body));
    }

    private Map<String, Object> tickStateMap(dev.example.mapi.internal.tick.TickControlBackend.State state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("frozen", state.frozen());
        map.put("sprinting", state.sprinting());
        map.put("tickRate", state.tickRate());
        map.put("tickCount", state.tickCount());
        return map;
    }

    private void sendPlayerQuery(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        int max = intParam(exchange, "max", 20);
        respond(exchange, 200, JsonWriter.write(Map.of("players", service.players(max))));
    }

    private void sendEntityQuery(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        var query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = queryParams(query);
        String dimension = params.getOrDefault("dimension", "minecraft:overworld");
        double x = Double.parseDouble(params.getOrDefault("x", "0"));
        double y = Double.parseDouble(params.getOrDefault("y", "0"));
        double z = Double.parseDouble(params.getOrDefault("z", "0"));
        int radius = intParam(exchange, "radius", 32);
        int max = intParam(exchange, "max", 50);
        respond(exchange, 200, JsonWriter.write(Map.of("entities",
                service.entities(dimension, x, y, z, radius, max))));
    }

    private void sendBlockQuery(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        String dimension = params.getOrDefault("dimension", "minecraft:overworld");
        try {
            int x = Integer.parseInt(params.getOrDefault("x", "0"));
            int y = Integer.parseInt(params.getOrDefault("y", "0"));
            int z = Integer.parseInt(params.getOrDefault("z", "0"));
            respond(exchange, 200, JsonWriter.write(service.block(dimension, x, y, z)
                    .map(Map.class::cast)
                    .orElseGet(() -> Map.of("blockId", "minecraft:air", "unloaded", false))));
        } catch (NumberFormatException e) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "x/y/z must be integers");
        }
    }

    private void sendRegistryList(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        respond(exchange, 200, JsonWriter.write(Map.of("registries", service.registries())));
    }

    private void sendRegistryEntries(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
        String registryId = params.get("registryId");
        if (registryId == null || registryId.isBlank()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "registryId is required");
        }
        int max = intParam(exchange, "max", 100);
        respond(exchange, 200, JsonWriter.write(Map.of("registryId", registryId,
                "entries", service.registryEntries(registryId, max))));
    }

    private void sendJobView(HttpExchange exchange, String jobId) throws IOException {
        var view = runtime.jobs().view(jobId)
                .orElseThrow(() -> new ProblemException(ProblemCode.NOT_FOUND, "unknown job: " + jobId));
        respond(exchange, 200, JsonWriter.write(view.toMap()));
    }

    // ------------------------------------------------------------------
    // Helpers for the operation surface
    // ------------------------------------------------------------------

    private dev.example.mapi.internal.tick.TickControlService requireTickControl() {
        return runtime.tickControl().orElseThrow(() -> new ProblemException(
                ProblemCode.CAPABILITY_UNAVAILABLE,
                "tick control is not available on this loader bridge"));
    }

    private dev.example.mapi.internal.query.WorldQueryService requireWorldQueries() {
        return runtime.worldQueries().orElseThrow(() -> new ProblemException(
                ProblemCode.CAPABILITY_UNAVAILABLE,
                "world queries are not available on this loader bridge"));
    }

    private void checkAccess(String operationId, java.util.Set<Scope> grants, Map<String, Object> body) {
        OperationDescriptor descriptor = operations.find(operationId)
                .orElseThrow(() -> new IllegalStateException("unregistered operation " + operationId));
        ExecutionMode requested = null;
        if (body.get("executionMode") instanceof String mode) {
            requested = java.util.Arrays.stream(ExecutionMode.values())
                    .filter(value -> value.wireName().equals(mode))
                    .findFirst()
                    .orElseThrow(() -> new ProblemException(ProblemCode.BAD_REQUEST,
                            "unknown executionMode: " + mode));
        }
        guard.checkAccess(descriptor, grants, false, requested);
    }

    private static String stringField(Map<String, Object> body, String key) {
        return body.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private static long longField(Map<String, Object> body, String key, long fallback) {
        return body.get(key) instanceof Number number ? number.longValue() : fallback;
    }

    private Map<String, Object> readJsonObject(HttpExchange exchange, String path) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ProblemException(ProblemCode.PAYLOAD_TOO_LARGE,
                        "Request body exceeds " + MAX_BODY_BYTES + " bytes");
            }
            if (bytes.length == 0) {
                return Map.of();
            }
            try {
                Object parsed = JsonReader.parse(bytes);
                if (!(parsed instanceof Map<?, ?> map)) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST,
                            "request body must be a JSON object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) map;
                return result;
            } catch (IllegalArgumentException e) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "invalid JSON body: " + e.getMessage());
            }
        }
    }

    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorizationHeader.substring(BEARER_PREFIX.length()).trim();
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            params.put(java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return params;
    }

    private static int intParam(HttpExchange exchange, String name, int fallback) {
        String raw = queryParams(exchange.getRequestURI().getRawQuery()).get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, name + " must be a positive integer");
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

    /**
     * Header-only size pre-check. The body stream itself is read (and capped)
     * by {@link #readJsonObject}; draining it here would make every POST body
     * unreadable by the handlers.
     */
    private static boolean declaredBodyTooLarge(HttpExchange exchange) {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength == null) {
            return false;
        }
        long value;
        try {
            value = Long.parseLong(contentLength.trim());
        } catch (NumberFormatException e) {
            return true;
        }
        return value > MAX_BODY_BYTES;
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
