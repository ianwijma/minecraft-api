package dev.example.mapi.internal;

import dev.example.mapi.api.Mapi;
import dev.example.mapi.api.MapiService;
import dev.example.mapi.api.MapiServices;
import dev.example.mapi.api.PlatformType;
import dev.example.mapi.api.ServerStatusSnapshot;
import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.config.MapiConfigException;
import dev.example.mapi.internal.discovery.DiscoveryFile;
import dev.example.mapi.internal.discovery.DiscoveryFile.DiscoverySnapshot;
import dev.example.mapi.internal.events.EventLog;
import dev.example.mapi.internal.http.HttpApiServer;
import dev.example.mapi.internal.http.TicketStore;
import dev.example.mapi.internal.lease.LeaseManager;
import dev.example.mapi.internal.task.TaskManager;
import dev.example.mapi.internal.task.WaitForTickTask;
import dev.example.mapi.internal.ws.WebSocketConnection;
import dev.example.mapi.internal.ws.WebSocketFrames;
import dev.example.mapi.internal.ws.WebSocketServer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Loader-neutral MAPI runtime: owns lifecycle state, the service registry,
 * and the optional HTTP server. One instance per game process.
 */
public final class MapiRuntime implements Mapi {

    /**
     * Maximum time a status snapshot may block on the server thread, in
     * milliseconds.
     */
    public static final long SNAPSHOT_WAIT_MS = 500L;

    private final MapiPlatform platform;
    private final MapiServicesImpl services = new MapiServicesImpl();
    private final TaskManager taskManager;
    private final LeaseManager leaseManager;
    private final EventLog eventLog;
    private final TicketStore ticketStore = new TicketStore();
    private final WebSocketServer wsServer;
    private volatile dev.example.mapi.internal.client.MapiClientOps clientOps;
    private volatile java.util.function.Consumer<Runnable> clientScheduler;

    private final String processSessionId = UUID.randomUUID().toString();
    private final Instant processStartedAt = Instant.now();
    private volatile String worldSessionId;
    private volatile ServerHandle serverHandle;
    private volatile HttpApiServer httpServer;
    private volatile MapiConfig activeConfig;
    private volatile ScheduledExecutorService heartbeatExecutor;

    /**
     * Creates the runtime and starts the optional HTTP service for the
     * process lifetime when enabled: the listener is up before any world
     * session and survives repeated integrated-server sessions (readiness
     * reflects world state). Public because loader modules and tests live in
     * sibling packages; still internal API.
     *
     * @param platform the loader platform adapter, never {@code null}
     */
    public MapiRuntime(MapiPlatform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.eventLog = new EventLog(processSessionId, () -> worldSessionId);
        this.taskManager = new TaskManager(platform.logger(),
                (type, data) -> eventLog.publish(type, "api-originated", data));
        this.taskManager.registerKind(new WaitForTickTask(this));
        this.leaseManager = new LeaseManager(platform.logger(), snapshot ->
                eventLog.publish("lease.changed", "api-originated", Map.of(
                        "leaseId", snapshot.id(),
                        "lease", snapshot.lease(),
                        "state", snapshot.state())));
        this.leaseManager.registerExpiryHook("client.input", () ->
                tryReadOnClientThread(() -> {
                    var ops = clientOps;
                    if (ops != null) {
                        ops.releaseAllKeys();
                    }
                    return null;
                }));
        this.wsServer = new WebSocketServer(ticketStore, this::authorizeToken, new WsEventListener(),
                platform.logger());
        platform.registerServerLifecycle(new ServerLifecycleListener() {
            @Override
            public void onServerStarting(ServerHandle handle) {
                serverHandle = handle;
                worldSessionId = UUID.randomUUID().toString();
                services.fireServerStart(handle, platform.logger());
                publishLifecycle("server.starting", "http", "worldReady");
                refreshDiscovery();
            }

            @Override
            public void onServerStopping() {
                ServerHandle handle = serverHandle;
                if (handle != null) {
                    services.fireServerStop(handle, platform.logger());
                }
            }

            @Override
            public void onServerStopped() {
                serverHandle = null;
                worldSessionId = null;
                taskManager.failAllForLifecycleChange(
                        "World session ended; tasks did not run to completion.");
                leaseManager.releaseAll();
                publishLifecycle("server.stopped", "worldReady", "http");
                refreshDiscovery();
            }
        });
        startHttp();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mapi-shutdown"));
    }

    private void publishLifecycle(String type, String fromReadiness, String toReadiness) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("readiness", toReadiness);
        data.put("previousReadiness", fromReadiness);
        eventLog.publish(type, "instrumented", data);
        eventLog.publish("readiness.changed", "instrumented", data);
    }

    private boolean authorizeToken(String candidate) {
        String token = activeConfig != null ? activeConfig.httpToken() : null;
        return token != null && candidate != null
                && java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * @param scope scope name
     * @return true when the active token carries the scope (false while the
     *         API has never started)
     */
    public boolean hasScope(String scope) {
        MapiConfig config = activeConfig;
        return config != null && config.scopes().contains(scope);
    }

    /**
     * WebSocket application behavior: hello on open, subscribe handling with
     * resume + GAP semantics, event delivery.
     */
    private final class WsEventListener implements WebSocketConnection.Listener {

        @Override
        public void onOpen(WebSocketConnection connection) {
            if (!hasScope(dev.example.mapi.internal.auth.Scope.OBSERVE)) {
                connection.close(WebSocketFrames.CLOSE_POLICY, "token lacks the observe scope");
                return;
            }
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("type", "hello");
            hello.put("protocolVersion", 1);
            hello.put("processSessionId", processSessionId);
            synchronized (eventLog) {
                hello.put("headSeq", eventLog.headSeq());
                hello.put("oldestSeq", eventLog.oldestSeq());
            }
            connection.sendText(dev.example.mapi.internal.json.JsonWriter.write(hello));
        }

        @Override
        public void onMessage(WebSocketConnection connection, String text) {
            Map<String, Object> message;
            try {
                message = WebSocketConnection.parseMessage(text);
            } catch (RuntimeException e) {
                connection.close(WebSocketFrames.CLOSE_INVALID_PAYLOAD, "messages must be JSON objects");
                return;
            }
            if (!"subscribe".equals(message.get("type"))) {
                connection.close(WebSocketFrames.CLOSE_INVALID_PAYLOAD,
                        "unknown message type; expected 'subscribe'");
                return;
            }
            if (message.get("policy") instanceof String policyName) {
                WebSocketConnection.Policy policy = WebSocketConnection.Policy.fromWire(policyName);
                if (policy == null) {
                    connection.close(WebSocketFrames.CLOSE_INVALID_PAYLOAD,
                            "policy must be 'drop-oldest' or 'disconnect'");
                    return;
                }
                connection.setPolicy(policy);
            }
            long after = -1;
            if (message.get("after") instanceof Number number) {
                after = number.longValue();
            }
            long afterFinal = after;
            synchronized (eventLog) {
                long oldest = eventLog.oldestSeq();
                boolean gap = afterFinal >= 0 && (afterFinal + 1) < oldest;
                connection.setSubscription(eventLog.subscribeAfter(afterFinal, event ->
                        connection.sendText(WebSocketConnection.eventJson(event))));
                if (gap) {
                    Map<String, Object> gapMessage = new LinkedHashMap<>();
                    gapMessage.put("type", "gap");
                    gapMessage.put("from", afterFinal + 1);
                    gapMessage.put("to", oldest - 1);
                    connection.sendText(dev.example.mapi.internal.json.JsonWriter.write(gapMessage));
                }
            }
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("type", "subscribed");
            if (afterFinal >= 0) {
                ack.put("after", afterFinal);
            }
            ack.put("policy", connection.policyWireName());
            connection.sendText(dev.example.mapi.internal.json.JsonWriter.write(ack));
        }

        @Override
        public void onClose(WebSocketConnection connection) {
            // Nothing to clean up: the subscription unsubscribes itself.
        }
    }

    // ------------------------------------------------------------------
    // Mapi (public API)
    // ------------------------------------------------------------------

    @Override
    public String modVersion() {
        return MapiBootstrap.MOD_VERSION;
    }

    @Override
    public String apiVersion() {
        return MapiBootstrap.API_VERSION;
    }

    @Override
    public String minecraftVersion() {
        return platform.minecraftVersion();
    }

    @Override
    public PlatformType platform() {
        return platform.type();
    }

    @Override
    public String platformVersion() {
        return platform.platformVersion();
    }

    @Override
    public Optional<ServerStatusSnapshot> serverStatus() {
        SnapshotResult result = trySnapshot();
        if (!result.serverRunning() || result.timedOut() || result.snapshot() == null) {
            return Optional.empty();
        }
        return Optional.of(result.snapshot());
    }

    @Override
    public MapiServicesImpl services() {
        return services;
    }

    /**
     * @return session identity: unique per process launch, never {@code null}
     */
    public String processSessionId() {
        return processSessionId;
    }

    /**
     * @return session identity for the current world/server session, empty
     *         while no world session is active
     */
    public Optional<String> worldSessionId() {
        return Optional.ofNullable(worldSessionId);
    }

    /**
     * @return the physical side of this process
     */
    public PhysicalSide physicalSide() {
        return platform.physicalSide();
    }

    /**
     * @return metadata for every loaded mod (sorted by id)
     */
    public List<RawModInfo> mods() {
        return platform.mods();
    }

    /**
     * @return the instance game directory (sandbox root for the file surface)
     */
    public java.nio.file.Path gameDir() {
        return platform.gameDir();
    }

    /**
     * @return the logical sides this process can currently serve; the client
     *         side appears once client operations are registered
     */
    public List<String> availableLogicalSides() {
        if (serverRunning() && clientOps != null) {
            return List.of("client", "server");
        }
        if (clientOps != null) {
            return List.of("client");
        }
        return serverRunning() ? List.of("server") : List.of();
    }

    /**
     * @return true while a world/server session is active
     */
    public boolean serverRunning() {
        return serverHandle != null;
    }

    /**
     * @return the coarse readiness state: {@code http} (listener answering,
     *         no world session) or {@code worldReady} (server session active);
     *         {@code clientJoined} is reserved for client operations
     */
    public String readiness() {
        return serverRunning() ? "worldReady" : "http";
    }

    /**
     * @return the instance identifier in use, or {@code null} while the HTTP
     *         API has never started (no loaded config)
     */
    public String instanceId() {
        MapiConfig config = activeConfig;
        return config == null ? null : config.instanceId();
    }

    /**
     * @return true if the local HTTP API listener is currently running;
     *         internal accessor (also used by tests)
     */
    public boolean httpRunning() {
        return httpServer != null;
    }

    /**
     * @return the actual bound loopback port when running (may differ from
     *         {@code http.port} when a fallback port was used), else -1
     */
    public int httpBoundPort() {
        HttpApiServer server = httpServer;
        return server != null && server.boundAddress() != null ? server.boundAddress().getPort() : -1;
    }

    // ------------------------------------------------------------------
    // Snapshot machinery (shared by the Java API and the HTTP endpoint)
    // ------------------------------------------------------------------

    /**
     * Attempts a bounded snapshot. Never blocks longer than
     * {@link #SNAPSHOT_WAIT_MS} beyond the server's queueing delay, and never
     * blocks the server tick thread.
     *
     * @return a non-{@code null} result describing the outcome
     */
    public SnapshotResult trySnapshot() {
        ServerHandle handle = serverHandle;
        if (handle == null) {
            return SnapshotResult.notRunning();
        }
        var task = new java.util.concurrent.FutureTask<>(() -> handle.infoSupplier().get());
        handle.executeOnServerThread(task);
        try {
            RawServerInfo raw = task.get(SNAPSHOT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            long captured = System.currentTimeMillis();
            ServerStatusSnapshot snapshot = new ServerStatusSnapshot(captured, raw.startedAtEpochMs(),
                    raw.playerCount(), raw.maxPlayers(), raw.tickCount(), raw.averageTickTimeMs(), raw.motd());
            return SnapshotResult.of(snapshot);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SnapshotResult.timedOutResult();
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(false);
            platform.logger().debug("MAPI: server status snapshot timed out after {} ms", SNAPSHOT_WAIT_MS);
            return SnapshotResult.timedOutResult();
        } catch (java.util.concurrent.ExecutionException e) {
            platform.logger().warn("MAPI: server status snapshot failed", e.getCause());
            return SnapshotResult.timedOutResult();
        }
    }

    /**
     * Outcome of a bounded owning-thread read.
     *
     * @param value         the read value ({@code null} when not running/busy
     *                      or when the supplier legitimately returned null)
     * @param serverRunning true while a world session is active
     * @param timedOut      true when the bounded wait expired
     * @param failure       supplier failure (rethrown on the caller's thread)
     * @param <T>           value type
     */
    public record ReadResult<T>(T value, boolean serverRunning, boolean timedOut, RuntimeException failure) {

        static <T> ReadResult<T> notRunning() {
            return new ReadResult<>(null, false, false, null);
        }

        static <T> ReadResult<T> busy() {
            return new ReadResult<>(null, true, true, null);
        }

        /**
         * @return true when the value should be used
         */
        public boolean ok() {
            return serverRunning && !timedOut && failure == null;
        }
    }

    /**
     * Executes a read on the owning (server) thread with the documented
     * bounded wait. Never blocks the server tick thread beyond the supplier
     * itself; live objects never escape the supplier.
     *
     * @param supplier read executed on the server thread
     * @param <T>      result type
     * @return the outcome; {@link ReadResult#failure()} carries supplier
     *         exceptions (e.g. {@link UnknownDimensionException}) for the
     *         caller to translate
     */
    public <T> ReadResult<T> tryReadOnServerThread(java.util.function.Supplier<T> supplier) {
        ServerHandle handle = serverHandle;
        if (handle == null) {
            return ReadResult.notRunning();
        }
        var task = new java.util.concurrent.FutureTask<>(() -> supplier.get());
        handle.executeOnServerThread(task);
        try {
            return new ReadResult<>(task.get(SNAPSHOT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS),
                    true, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReadResult.busy();
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(false);
            return ReadResult.busy();
        } catch (java.util.concurrent.ExecutionException e) {
            RuntimeException cause = e.getCause() instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(e.getCause());
            return new ReadResult<>(null, true, false, cause);
        }
    }

    // ------------------------------------------------------------------
    // HTTP lifecycle
    // ------------------------------------------------------------------

    private void startHttp() {
        MapiConfig config;
        try {
            config = MapiConfig.load(platform.configDir(), platform.gameDir(), System.getenv(),
                    platform.logger());
        } catch (MapiConfigException e) {
            platform.logger().error("MAPI: HTTP API not started: {}", e.getMessage());
            return;
        }
        if (!config.httpEnabled()) {
            platform.logger().info("MAPI: local HTTP API is disabled (enable with http.enabled=true in "
                    + "{}/mapi.properties)", platform.configDir());
            return;
        }
        HttpApiServer httpServer = new HttpApiServer(config, this, platform.logger());
        if (!httpServer.start()) {
            if (config.failFast()) {
                throw new IllegalStateException("MAPI: http.failFast is set and no HTTP port could be bound "
                        + "(tried " + config.httpPort() + " with " + config.portFallback() + " fallbacks)");
            }
            return;
        }
        this.httpServer = httpServer;
        this.activeConfig = config;
        startEvents();
        writeDiscoveryFile(config);
        startHeartbeat(config);
        eventLog.publish("api.started", "api-originated", Map.of(
                "port", httpBoundPort(),
                "eventsPort", wsBoundPort()));
    }

    private void startEvents() {
        if (!wsServer.start(0)) {
            platform.logger().warn("MAPI: WebSocket event stream unavailable; HTTP polling still works");
        }
    }

    /**
     * @return the bound WebSocket event port, or -1 when not running
     */
    public int wsBoundPort() {
        return wsServer.boundPort();
    }

    /**
     * @return the event log (always present)
     */
    public EventLog eventLog() {
        return eventLog;
    }

    /**
     * @return the single-use WebSocket ticket store
     */
    public TicketStore ticketStore() {
        return ticketStore;
    }

    /**
     * Registers client-only operations (physical clients only). Idempotent:
     * later registrations replace earlier ones (dev loops).
     *
     * @param ops     client operations implementation, never {@code null}
     * @param scheduler client-thread scheduler, never {@code null}
     */
    public void registerClientOps(dev.example.mapi.internal.client.MapiClientOps ops,
            java.util.function.Consumer<Runnable> scheduler) {
        this.clientOps = Objects.requireNonNull(ops, "ops");
        this.clientScheduler = Objects.requireNonNull(scheduler, "scheduler");
        platform.logger().info("MAPI: client operations registered");
    }

    /**
     * @return client operations or {@code null} (dedicated servers; clients
     *         before registration)
     */
    public dev.example.mapi.internal.client.MapiClientOps clientOps() {
        return clientOps;
    }

    /**
     * Executes a read on the owning (client) thread with the documented
     * bounded wait.
     *
     * @param supplier read executed on the client thread
     * @param <T>      result type
     * @return the outcome; {@link ReadResult#failure()} carries supplier
     *         exceptions for the caller to translate
     */
    public <T> ReadResult<T> tryReadOnClientThread(java.util.function.Supplier<T> supplier) {
        java.util.function.Consumer<Runnable> scheduler = clientScheduler;
        if (scheduler == null) {
            return ReadResult.notRunning();
        }
        var task = new java.util.concurrent.FutureTask<>(() -> supplier.get());
        scheduler.accept(task);
        try {
            return new ReadResult<>(task.get(SNAPSHOT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS),
                    true, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReadResult.busy();
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(false);
            return ReadResult.busy();
        } catch (java.util.concurrent.ExecutionException e) {
            RuntimeException cause = e.getCause() instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(e.getCause());
            return new ReadResult<>(null, true, false, cause);
        }
    }

    /**
     * Stops the process-scoped services (tasks, discovery heartbeat, HTTP
     * listener) and removes the discovery file. Idempotent; also registered
     * as a JVM shutdown hook so a clean exit never leaves a stale discovery
     * file.
     */
    public synchronized void shutdown() {
        leaseManager.releaseAll();
        taskManager.shutdown();
        stopHeartbeat();
        stopHttp();
    }

    /**
     * @return the task registry/executor (always present; kinds are
     *         registered even when the HTTP API is disabled)
     */
    public TaskManager taskManager() {
        return taskManager;
    }

    /**
     * @return the control-lease registry
     */
    public LeaseManager leaseManager() {
        return leaseManager;
    }

    /**
     * @return the active server handle, or {@code null} between world
     *         sessions; only for owner-thread suppliers
     */
    public ServerHandle serverHandle() {
        return serverHandle;
    }

    private void startHeartbeat(MapiConfig config) {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mapi-discovery-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor.scheduleWithFixedDelay(this::refreshDiscovery, config.discoveryHeartbeatSeconds(),
                config.discoveryHeartbeatSeconds(), TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void refreshDiscovery() {
        MapiConfig config = activeConfig;
        if (config != null) {
            writeDiscoveryFile(config);
        }
    }

    private void stopHttp() {
        HttpApiServer httpServer = this.httpServer;
        this.httpServer = null;
        this.activeConfig = null;
        if (httpServer != null) {
            eventLog.publish("api.stopped", "api-originated", Map.of());
        }
        wsServer.stop();
        deleteDiscoveryFile();
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    private void writeDiscoveryFile(MapiConfig config) {
        HttpApiServer server = httpServer;
        int boundPort = server != null && server.boundAddress() != null
                ? server.boundAddress().getPort()
                : config.httpPort();
        DiscoverySnapshot snapshot = new DiscoverySnapshot(
                config.instanceId(),
                processSessionId,
                ProcessHandle.current().pid(),
                processStartedAt,
                Instant.now(),
                readiness(),
                platform.physicalSide().id(),
                platform.type().id(),
                platform.minecraftVersion(),
                boundPort,
                wsBoundPort() >= 0 ? wsBoundPort() : null,
                Map.of());
        DiscoveryFile.write(dataDir(), snapshot, platform.logger());
    }

    private void deleteDiscoveryFile() {
        DiscoveryFile.delete(dataDir(), platform.logger());
    }

    private java.nio.file.Path dataDir() {
        return platform.gameDir().resolve(MapiConfig.DATA_DIR_NAME);
    }

    // ------------------------------------------------------------------
    // Service registry implementation
    // ------------------------------------------------------------------

    /**
     * Thread-safe service registry implementation. Public only because it
     * implements the public {@code Mapi.services()} return type; treat as
     * internal.
     */
    public static final class MapiServicesImpl implements dev.example.mapi.api.MapiServices {

        private final ConcurrentMap<String, MapiService> services = new ConcurrentHashMap<>();

        @Override
        public MapiService register(String id, MapiService service) {
            Objects.requireNonNull(service, "service");
            if (id == null || id.isBlank() || !id.matches("[a-z][a-z0-9_-]{1,63}")) {
                throw new IllegalArgumentException(
                        "Service id must match [a-z][a-z0-9_-]{1,63}: " + id);
            }
            MapiService existing = services.putIfAbsent(id, Objects.requireNonNull(service, "service"));
            if (existing != null) {
                throw new IllegalStateException("MAPI service id already registered: " + id);
            }
            return service;
        }

        @Override
        public Optional<MapiService> get(String id) {
            return Optional.ofNullable(services.get(id));
        }

        @Override
        public java.util.Map<String, MapiService> all() {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(services));
        }

        void fireServerStart(ServerHandle handle, Logger logger) {
            services.values().forEach(service -> handle.executeOnServerThread(() -> {
                try {
                    service.onServerStart();
                } catch (RuntimeException e) {
                    logger.warn("MAPI: service '{}' failed during onServerStart", service.id(), e);
                }
            }));
        }

        void fireServerStop(ServerHandle handle, Logger logger) {
            services.values().forEach(service -> handle.executeOnServerThread(() -> {
                try {
                    service.onServerStop();
                } catch (RuntimeException e) {
                    logger.warn("MAPI: service '{}' failed during onServerStop", service.id(), e);
                }
            }));
        }
    }
}
