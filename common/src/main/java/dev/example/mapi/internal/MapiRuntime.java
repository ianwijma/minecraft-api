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
import dev.example.mapi.internal.http.HttpApiServer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    private final String processSessionId = UUID.randomUUID().toString();
    private final Instant processStartedAt = Instant.now();
    private volatile String worldSessionId;
    private volatile ServerHandle serverHandle;
    private volatile HttpApiServer httpServer;
    private volatile MapiConfig activeConfig;

    /**
     * Creates the runtime. Public because loader modules and tests live in
     * sibling packages; still internal API.
     *
     * @param platform the loader platform adapter, never {@code null}
     */
    public MapiRuntime(MapiPlatform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
        platform.registerServerLifecycle(new ServerLifecycleListener() {
            @Override
            public void onServerStarting(ServerHandle handle) {
                serverHandle = handle;
                worldSessionId = UUID.randomUUID().toString();
                services.fireServerStart(handle, platform.logger());
                startHttp();
            }

            @Override
            public void onServerStopping() {
                stopHttp();
                ServerHandle handle = serverHandle;
                if (handle != null) {
                    services.fireServerStop(handle, platform.logger());
                }
            }

            @Override
            public void onServerStopped() {
                serverHandle = null;
                worldSessionId = null;
            }
        });
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
     * @return the logical sides this process can currently serve; the client
     *         side appears once client operations exist
     */
    public List<String> availableLogicalSides() {
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
        if (httpServer.start()) {
            this.httpServer = httpServer;
            this.activeConfig = config;
            writeDiscoveryFile(config);
        }
    }

    private void stopHttp() {
        HttpApiServer httpServer = this.httpServer;
        this.httpServer = null;
        this.activeConfig = null;
        deleteDiscoveryFile();
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    private void writeDiscoveryFile(MapiConfig config) {
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
                config.httpPort(),
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
