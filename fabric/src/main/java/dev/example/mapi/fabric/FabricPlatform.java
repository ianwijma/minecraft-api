package dev.example.mapi.fabric;

import dev.example.mapi.api.PlatformType;
import dev.example.mapi.internal.MapiPlatform;
import dev.example.mapi.internal.RawServerInfo;
import dev.example.mapi.internal.ServerHandle;
import dev.example.mapi.internal.ServerLifecycleListener;
import java.nio.file.Path;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric implementation of the loader-neutral platform contract.
 */
final class FabricPlatform implements MapiPlatform {

    private static final Logger LOG = LoggerFactory.getLogger("mapi");

    private final FabricServerBridge bridge = new FabricServerBridge();

    @Override
    public PlatformType type() {
        return PlatformType.FABRIC;
    }

    @Override
    public String platformVersion() {
        return MapiFabric.loaderVersion();
    }

    @Override
    public String minecraftVersion() {
        return MapiFabric.minecraftVersion();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Logger logger() {
        return LOG;
    }

    @Override
    public void registerServerLifecycle(ServerLifecycleListener listener) {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            bridge.onServerStarting(server);
            listener.onServerStarting(FabricServerHandle.starting(server));
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> listener.onServerStarted());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> listener.onServerStopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            bridge.onServerStopped();
            listener.onServerStopped();
        });
    }

    @Override
    public dev.example.mapi.internal.server.ServerBridge serverBridge() {
        return bridge;
    }

    /**
     * Server handle backed by a live {@link MinecraftServer}. Start time is
     * captured at construction (the SERVER_STARTING event).
     */
    private record FabricServerHandle(MinecraftServer server, long startedAtEpochMs) implements ServerHandle {

        private FabricServerHandle {
            java.util.Objects.requireNonNull(server, "server");
        }

        static FabricServerHandle starting(MinecraftServer server) {
            return new FabricServerHandle(server, System.currentTimeMillis());
        }

        @Override
        public void executeOnServerThread(Runnable task) {
            server.execute(task);
        }

        @Override
        public java.util.function.Supplier<RawServerInfo> infoSupplier() {
            return () -> new RawServerInfo(
                    startedAtEpochMs(),
                    server.getPlayerCount(),
                    server.getPlayerList().getMaxPlayers(),
                    server.getTickCount(),
                    server.getAverageTickTimeNanos() / 1_000_000.0d,
                    server.getMotd(),
                    server.tickRateManager().isFrozen(),
                    server.tickRateManager().isSprinting());
        }
    }
}
