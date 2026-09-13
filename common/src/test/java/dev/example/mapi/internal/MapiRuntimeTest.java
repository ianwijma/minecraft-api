package dev.example.mapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.List;
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
    void processSessionIdIsStableAndWorldSessionChangesPerSession() {
        TestPlatform platform = new TestPlatform(LOG);
        MapiRuntime runtime = new MapiRuntime(platform);
        assertFalse(runtime.processSessionId().isBlank());
        assertEquals(runtime.processSessionId(), runtime.processSessionId());
        assertTrue(runtime.worldSessionId().isEmpty(), "no world session before server start");
        assertEquals("http", runtime.readiness());
        assertTrue(runtime.availableLogicalSides().isEmpty());

        platform.listener.onServerStarting(TestServerHandle.inline());
        String first = runtime.worldSessionId().orElseThrow();
        assertEquals("worldReady", runtime.readiness());
        assertEquals(List.of("server"), runtime.availableLogicalSides());

        platform.listener.onServerStopping();
        platform.listener.onServerStopped();
        assertTrue(runtime.worldSessionId().isEmpty());

        platform.listener.onServerStarting(TestServerHandle.inline());
        String second = runtime.worldSessionId().orElseThrow();
        assertNotEquals(first, second, "a replaced world session must get a fresh id");
        platform.listener.onServerStopping();
        platform.listener.onServerStopped();
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
        public dev.example.mapi.internal.PhysicalSide physicalSide() {
            return dev.example.mapi.internal.PhysicalSide.DEDICATED_SERVER;
        }

        @Override
        public java.util.List<dev.example.mapi.internal.RawModInfo> mods() {
            return List.of(new dev.example.mapi.internal.RawModInfo("mapi", "Minecraft API", "0.1.0"));
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
     * Read suppliers return fixed fakes.
     */
    public static final class TestServerHandle implements ServerHandle {
        private final long startedAtEpochMs;
        private final Supplier<RawServerInfo> info;
        private final boolean runTasks;
        private final Supplier<java.util.List<dev.example.mapi.internal.RawPlayerSnapshot>> players;
        private final dev.example.mapi.internal.RawBlockRead block;
        private final dev.example.mapi.internal.RawWorldTime time;
        private final int dataVersion;
        private final java.util.function.BiFunction<String, Integer, dev.example.mapi.internal.RawCommandResult>
                commandExecutor;

        public static TestServerHandle inline() {
            return new TestServerHandle(1_000L, () -> new RawServerInfo(1_000L, 3, 20, 42, 1.0d, "A Test World"),
                    true,
                    java.util.List.of(
                            new dev.example.mapi.internal.RawPlayerSnapshot("Asha",
                                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                    "minecraft:overworld", 1.5, -64.0, 2.5),
                            new dev.example.mapi.internal.RawPlayerSnapshot("Bram",
                                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
                                    "minecraft:the_nether", 10.0, 32.0, -3.0)),
                    new dev.example.mapi.internal.RawBlockRead("minecraft:stone", java.util.Map.of(),
                            "minecraft:overworld", 0, -64, 0),
                    new dev.example.mapi.internal.RawWorldTime(12345L, 6000L, 6000L), 4189,
                    (command, level) -> new dev.example.mapi.internal.RawCommandResult(1, true,
                            List.of("Executed " + command + " at level " + level)));
        }

        public static TestServerHandle blocked() {
            return new TestServerHandle(1_000L, () -> {
                throw new AssertionError("must not be invoked when blocked");
            }, false, java.util.List.of(), null,
                    new dev.example.mapi.internal.RawWorldTime(0, 0, 0), 0,
                    (command, level) -> {
                        throw new AssertionError("must not be invoked when blocked");
                    });
        }

        private TestServerHandle(long startedAtEpochMs, Supplier<RawServerInfo> info, boolean runTasks,
                java.util.List<dev.example.mapi.internal.RawPlayerSnapshot> players,
                dev.example.mapi.internal.RawBlockRead block,
                dev.example.mapi.internal.RawWorldTime time, int dataVersion,
                java.util.function.BiFunction<String, Integer, dev.example.mapi.internal.RawCommandResult>
                        commandExecutor) {
            this.startedAtEpochMs = startedAtEpochMs;
            this.info = info;
            this.runTasks = runTasks;
            this.players = () -> players;
            this.block = block;
            this.time = time;
            this.dataVersion = dataVersion;
            this.commandExecutor = commandExecutor;
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

        @Override
        public Supplier<java.util.List<dev.example.mapi.internal.RawPlayerSnapshot>> playersSupplier() {
            return players;
        }

        @Override
        public Supplier<dev.example.mapi.internal.RawBlockRead> blockSupplier(String dimension, int x, int y,
                int z) {
            if (dimension != null && dimension.equals("minecraft:nowhere")) {
                throw new dev.example.mapi.internal.UnknownDimensionException("Unknown dimension: " + dimension);
            }
            return () -> block;
        }

        @Override
        public Supplier<dev.example.mapi.internal.RawWorldTime> timeSupplier(String dimension) {
            if (dimension != null && dimension.equals("minecraft:nowhere")) {
                throw new dev.example.mapi.internal.UnknownDimensionException("Unknown dimension: " + dimension);
            }
            return () -> time;
        }

        @Override
        public Supplier<Integer> dataVersionSupplier() {
            return () -> dataVersion;
        }

        @Override
        public Supplier<dev.example.mapi.internal.RawCommandResult> commandSupplier(String command,
                int permissionLevel) {
            return () -> commandExecutor.apply(command, permissionLevel);
        }

        @Override
        public Supplier<dev.example.mapi.internal.ServerHandle.RegistryIdPage> registryIdsSupplier(
                String type, int limit, int offset) {
            if (!type.equals("block")) {
                throw new dev.example.mapi.internal.UnknownRegistryTypeException("Unknown registry type: " + type);
            }
            java.util.List<String> ids = java.util.List.of("minecraft:air", "minecraft:dirt",
                    "minecraft:stone");
            return () -> new dev.example.mapi.internal.ServerHandle.RegistryIdPage(
                    ids.subList(Math.min(offset, ids.size()), Math.min(offset + limit, ids.size())), ids.size());
        }

        @Override
        public Supplier<java.util.List<String>> tagIdsSupplier(String type) {
            if (!type.equals("block")) {
                throw new dev.example.mapi.internal.UnknownRegistryTypeException("Unknown registry type: " + type);
            }
            return () -> java.util.List.of("minecraft:logs", "minecraft:planks");
        }

        @Override
        public Supplier<java.util.List<String>> tagMembersSupplier(String type, String tagId) {
            if (!type.equals("block")) {
                throw new dev.example.mapi.internal.UnknownRegistryTypeException("Unknown registry type: " + type);
            }
            return () -> tagId.equals("minecraft:planks")
                    ? java.util.List.of("minecraft:oak_planks", "minecraft:spruce_planks")
                    : java.util.List.of();
        }
    }
}
