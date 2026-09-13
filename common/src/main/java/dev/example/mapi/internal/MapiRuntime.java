package dev.example.mapi.internal;

import dev.example.mapi.api.Mapi;
import dev.example.mapi.api.MapiService;
import dev.example.mapi.api.MapiServices;
import dev.example.mapi.api.PlatformType;
import dev.example.mapi.api.ServerStatusSnapshot;
import dev.example.mapi.internal.http.HttpApiServer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final dev.example.mapi.internal.event.EventBus eventBus =
            new dev.example.mapi.internal.event.EventBus();

    private volatile ServerHandle serverHandle;
    private volatile HttpApiServer httpServer;

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
                services.fireServerStart(handle, platform.logger());
                startHttp(handle);
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
     * @return true if the local HTTP API listener is currently running;
     *         internal accessor (also used by tests)
     */
    public boolean httpRunning() {
        return httpServer != null;
    }

    /**
     * @return the runtime-wide ordered event bus for lifecycle and operation
     *     events (spec §6, §13); internal accessor (also used by tests)
     */
    public dev.example.mapi.internal.event.EventBus eventBus() {
        return eventBus;
    }

    /**
     * @return the loader's server-side bridge capabilities; internal accessor
     */
    public dev.example.mapi.internal.server.ServerBridge serverBridge() {
        return platform.serverBridge();
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

    private void startHttp(ServerHandle handle) {
        dev.example.mapi.internal.config.MapiConfig config;
        try {
            config = dev.example.mapi.internal.config.MapiConfig.load(platform.configDir(), System.getenv(),
                    platform.logger());
        } catch (dev.example.mapi.internal.config.MapiConfigException e) {
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
        }
    }

    private void stopHttp() {
        HttpApiServer httpServer = this.httpServer;
        this.httpServer = null;
        if (httpServer != null) {
            httpServer.stop();
        }
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
