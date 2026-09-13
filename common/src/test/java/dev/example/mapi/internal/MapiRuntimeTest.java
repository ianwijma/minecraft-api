package dev.example.mapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.api.Mapi;
import dev.example.mapi.api.MapiApi;
import dev.example.mapi.api.MapiService;
import dev.example.mapi.api.MapiServices;
import dev.example.mapi.api.PlatformType;
import dev.example.mapi.api.ServerStatusSnapshot;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapiRuntimeTest {

    private static final Logger LOG = LoggerFactory.getLogger(MapiRuntimeTest.class);

    @Test
    void bootstrapBindsPublicApiExactlyOnce() {
        MapiBootstrap.initialize(new TestPlatform(LOG));
        assertNotNull(MapiApi.require());
        assertEquals("0.1.0", MapiApi.require().modVersion());
        assertEquals("0.1.0", MapiApi.require().apiVersion());
        MapiBootstrap.initialize(new TestPlatform(LOG));
        assertThrows(IllegalStateException.class, () -> MapiApi.bind(new TestMapi()));
    }

    @Test
    void lifecycleAndServiceCallbacksFireInline() {
        TestPlatform platform = new TestPlatform(LOG);
        MapiRuntime runtime = new MapiRuntime(platform);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        runtime.services().register("test-service", new MapiService() {
            @Override
            public String id() {
                return "test-service";
            }

            @Override
            public void onServerStart() {
                starts.incrementAndGet();
            }

            @Override
            public void onServerStop() {
                stops.incrementAndGet();
            }
        });

        assertTrue(runtime.serverStatus().isEmpty(), "no server: API must be empty");

        TestServerHandle handle = TestServerHandle.inline();
        platform.listener.onServerStarting(handle);
        assertEquals(1, starts.get());
        assertFalse(runtime.httpRunning(), "HTTP must stay disabled by default");

        Optional<ServerStatusSnapshot> status = runtime.serverStatus();
        assertTrue(status.isPresent());
        assertEquals(3, status.orElseThrow().playerCount());
        assertEquals(20, status.orElseThrow().maxPlayers());
        assertEquals("A Test World", status.orElseThrow().motd());

        platform.listener.onServerStopping();
        assertEquals(1, stops.get());
        assertFalse(runtime.httpRunning());

        platform.listener.onServerStopped();
        assertTrue(runtime.serverStatus().isEmpty());
    }

    @Test
    void duplicateServiceIdIsRejected() {
        MapiRuntime runtime = new MapiRuntime(new TestPlatform(LOG));
        runtime.services().register("dup", () -> "dup");
        assertThrows(IllegalStateException.class, () -> runtime.services().register("dup", () -> "dup"));
        assertThrows(IllegalArgumentException.class, () -> runtime.services().register("Bad Id", () -> "x"));
        assertEquals(1, runtime.services().all().size());
    }

    @Test
    void snapshotTimesOutWhenServerThreadIsBlocked() {
        TestPlatform platform = new TestPlatform(LOG);
        MapiRuntime runtime = new MapiRuntime(platform);
        platform.listener.onServerStarting(TestServerHandle.blocked());
        long begin = System.nanoTime();
        assertTrue(runtime.serverStatus().isEmpty());
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        assertTrue(elapsedMs >= 400, "bounded wait should be respected, was " + elapsedMs + " ms");
        platform.listener.onServerStopping();
        platform.listener.onServerStopped();
    }

    @Test
    void worldSessionLifecycleIsTrackedAcrossServerLifecycle() {
        TestPlatform platform = new TestPlatform(LOG);
        MapiRuntime runtime = new MapiRuntime(platform);
        assertEquals(dev.example.mapi.internal.world.WorldPhase.NONE,
                runtime.worldLifecycle().phase());

        TestServerHandle handle = TestServerHandle.inline();
        platform.listener.onServerStarting(handle);
        assertEquals(dev.example.mapi.internal.world.WorldPhase.LOADING,
                runtime.worldLifecycle().phase());
        String sessionId = runtime.worldLifecycle().currentSessionId().orElseThrow();

        platform.listener.onServerStarted();
        assertEquals(dev.example.mapi.internal.world.WorldPhase.ACTIVE,
                runtime.worldLifecycle().phase());
        assertEquals(sessionId, runtime.worldLifecycle().requireActive(Optional.of(sessionId)));

        platform.listener.onServerStopping();
        assertEquals(dev.example.mapi.internal.world.WorldPhase.UNLOADING,
                runtime.worldLifecycle().phase());
        platform.listener.onServerStopped();
        assertEquals(dev.example.mapi.internal.world.WorldPhase.NONE,
                runtime.worldLifecycle().phase());
        assertThrows(dev.example.mapi.internal.problem.ProblemException.class,
                () -> runtime.worldLifecycle().requireActive(Optional.empty()));

        // The four world-session events were published in order.
        var lifecycle = runtime.eventBus().eventsAfter(0,
                dev.example.mapi.internal.event.EventFilter.any(), 100).events();
        assertEquals(java.util.List.of("world.loading", "world.loaded", "world.unloading", "world.unloaded"),
                lifecycle.stream().map(e -> e.type()).toList());
    }

    /**
     * Minimal Mapi implementation for negative binding tests.
     */
    public static final class TestMapi implements Mapi {
        @Override
        public String modVersion() {
            return "x";
        }

        @Override
        public String apiVersion() {
            return "0.1.0";
        }

        @Override
        public String minecraftVersion() {
            return "26.2";
        }

        @Override
        public PlatformType platform() {
            return PlatformType.FABRIC;
        }

        @Override
        public String platformVersion() {
            return "0";
        }

        @Override
        public Optional<ServerStatusSnapshot> serverStatus() {
            return Optional.empty();
        }

        @Override
        public MapiServices services() {
            return null;
        }
    }

    /**
     * Test platform storing the lifecycle listener for manual driving.
     */
    public static class TestPlatform implements MapiPlatform {
        final Logger logger;
        ServerLifecycleListener listener;

        public TestPlatform(Logger logger) {
            this.logger = logger;
        }

        /**
         * @return the lifecycle listener registered by the runtime
         */
        public ServerLifecycleListener lifecycleListener() {
            return listener;
        }

        @Override
        public PlatformType type() {
            return PlatformType.FABRIC;
        }

        @Override
        public String platformVersion() {
            return "test-loader";
        }

        @Override
        public String minecraftVersion() {
            return "26.2";
        }

        @Override
        public Path gameDir() {
            return Path.of(".");
        }

        @Override
        public Path configDir() {
            return Path.of(".");
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public void registerServerLifecycle(ServerLifecycleListener listener) {
            this.listener = listener;
        }
    }

    /**
     * Server handle whose {@link #executeOnServerThread(Runnable)} either runs
     * tasks inline or never runs them (simulating a busy server thread).
     */
    public static final class TestServerHandle implements ServerHandle {
        private final long startedAtEpochMs;
        private final Supplier<RawServerInfo> info;
        private final boolean runTasks;

        public static TestServerHandle inline() {
            return new TestServerHandle(1_000L, () -> new RawServerInfo(1_000L, 3, 20, 42, 1.0d, "A Test World"),
                    true);
        }

        public static TestServerHandle blocked() {
            return new TestServerHandle(1_000L, () -> {
                throw new AssertionError("must not be invoked when blocked");
            }, false);
        }

        private TestServerHandle(long startedAtEpochMs, Supplier<RawServerInfo> info, boolean runTasks) {
            this.startedAtEpochMs = startedAtEpochMs;
            this.info = info;
            this.runTasks = runTasks;
        }

        @Override
        public long startedAtEpochMs() {
            return startedAtEpochMs;
        }

        @Override
        public void executeOnServerThread(Runnable task) {
            if (runTasks) {
                task.run();
            }
        }

        @Override
        public Supplier<RawServerInfo> infoSupplier() {
            return info;
        }
    }
}
