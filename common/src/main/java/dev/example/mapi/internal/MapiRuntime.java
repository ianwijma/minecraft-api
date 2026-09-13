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

    /** Default retention period for retained snapshots (30 minutes). */
    public static final long SNAPSHOT_TTL_MS = 30L * 60L * 1000L;

    /** Default maximum number of retained snapshots. */
    public static final int SNAPSHOT_MAX_COUNT = 64;

    /** Default tick-control rate bounds (spec §5: configured bounds; config wiring in chunk 4.2). */
    public static final float MIN_TICK_RATE = 1.0f;
    public static final float MAX_TICK_RATE = 100.0f;

    private volatile dev.example.mapi.internal.tick.TickControlService tickControl;
    private volatile dev.example.mapi.internal.query.WorldQueryService worldQueries;

    private final MapiPlatform platform;
    private final MapiServicesImpl services = new MapiServicesImpl();
    private final dev.example.mapi.internal.event.EventBus eventBus =
            new dev.example.mapi.internal.event.EventBus();
    private final dev.example.mapi.internal.clock.ClockRegistry clocks =
            new dev.example.mapi.internal.clock.ClockRegistry();
    private final dev.example.mapi.internal.serverstate.ServerProgressTracker progressTracker =
            new dev.example.mapi.internal.serverstate.ServerProgressTracker(clocks, eventBus);
    private final dev.example.mapi.internal.job.JobManager jobManager = new dev.example.mapi.internal.job.JobManager();
    private final dev.example.mapi.internal.lease.LeaseManager leaseManager =
            new dev.example.mapi.internal.lease.LeaseManager();
    private final dev.example.mapi.internal.snapshot.SnapshotStore snapshots =
            new dev.example.mapi.internal.snapshot.SnapshotStore(
                    SNAPSHOT_TTL_MS, SNAPSHOT_MAX_COUNT,
                    dev.example.mapi.internal.encoding.EncodingLimits.DEFAULT);
    private final dev.example.mapi.internal.world.WorldLifecycleCoordinator worldLifecycle =
            new dev.example.mapi.internal.world.WorldLifecycleCoordinator(
                    jobManager, snapshots, leaseManager, eventBus);

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
                var backend = platform.serverBridge().tickControl();
                tickControl = backend
                        .<dev.example.mapi.internal.tick.TickControlService>map(
                                b -> new dev.example.mapi.internal.tick.TickControlService(
                                        b, leaseManager, progressTracker, worldLifecycle,
                                        MIN_TICK_RATE, MAX_TICK_RATE))
                        .orElse(null);
                var queryBackend = platform.serverBridge().worldQueries();
                worldQueries = queryBackend
                        .map(b -> new dev.example.mapi.internal.query.WorldQueryService(
                                b, worldLifecycle, MapiRuntime.this::callOnServerThread))
                        .orElse(null);
                worldLifecycle.beginLoad();
                services.fireServerStart(handle, platform.logger());
                startHttp(handle);
            }

            @Override
            public void onServerStarted() {
                worldLifecycle.activated();
            }

            @Override
            public void onServerStopping() {
                worldLifecycle.beginUnload();
                stopHttp();
                ServerHandle handle = serverHandle;
                if (handle != null) {
                    services.fireServerStop(handle, platform.logger());
                }
            }

            @Override
            public void onServerStopped() {
                worldLifecycle.unloaded();
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

    /** @return the runtime job manager; internal accessor */
    public dev.example.mapi.internal.job.JobManager jobs() {
        return jobManager;
    }

    /** @return the runtime lease manager; internal accessor */
    public dev.example.mapi.internal.lease.LeaseManager leases() {
        return leaseManager;
    }

    /** @return the runtime snapshot store; internal accessor */
    public dev.example.mapi.internal.snapshot.SnapshotStore snapshots() {
        return snapshots;
    }

    /** @return the world-lifecycle coordinator; internal accessor */
    public dev.example.mapi.internal.world.WorldLifecycleCoordinator worldLifecycle() {
        return worldLifecycle;
    }

    /** @return the named-clock registry; internal accessor */
    public dev.example.mapi.internal.clock.ClockRegistry clocks() {
        return clocks;
    }

    /** @return the server progress tracker; internal accessor */
    public dev.example.mapi.internal.serverstate.ServerProgressTracker progressTracker() {
        return progressTracker;
    }

    /** @return the tick-control service while the bridge supports it, empty otherwise */
    public java.util.Optional<dev.example.mapi.internal.tick.TickControlService> tickControl() {
        return java.util.Optional.ofNullable(tickControl);
    }

    /** @return the world-query service while the bridge supports it, empty otherwise */
    public java.util.Optional<dev.example.mapi.internal.query.WorldQueryService> worldQueries() {
        return java.util.Optional.ofNullable(worldQueries);
    }

    /**
     * Runs a supplier on the server thread with the bounded snapshot wait,
     * translating failures into problem exceptions.
     *
     * @param task supplier to run
     * @param <T>  result type
     * @return the result
     */
    <T> T callOnServerThread(java.util.function.Supplier<T> task) {
        ServerHandle handle = serverHandle;
        if (handle == null) {
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.WORLD_NOT_LOADED,
                    "no server is running");
        }
        var future = new java.util.concurrent.FutureTask<>(task::get);
        handle.executeOnServerThread(future);
        try {
            return future.get(SNAPSHOT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(false);
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.SERVER_BUSY,
                    "server thread busy; query timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.SERVER_BUSY,
                    "interrupted while waiting for the server thread");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof dev.example.mapi.internal.problem.ProblemException problem) {
                throw problem;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.INTERNAL,
                    "world query failed: " + cause);
        }
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
            progressTracker.observe(new dev.example.mapi.internal.serverstate.TickObservation(
                    captured, raw.tickCount(), raw.tickFrozen(), raw.sprinting()));
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
