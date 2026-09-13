package dev.example.mapi.neoforge;

import dev.example.mapi.api.PlatformType;
import dev.example.mapi.internal.MapiPlatform;
import dev.example.mapi.internal.RawServerInfo;
import dev.example.mapi.internal.ServerHandle;
import dev.example.mapi.internal.ServerLifecycleListener;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge implementation of the loader-neutral platform contract.
 */
final class NeoForgePlatform implements MapiPlatform {

    private static final Logger LOG = LoggerFactory.getLogger("mapi");

    @Override
    public PlatformType type() {
        return PlatformType.NEOFORGE;
    }

    @Override
    public String platformVersion() {
        return ModList.get().getModContainerById("neoforge")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public String minecraftVersion() {
        return ModList.get().getModContainerById("minecraft")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Logger logger() {
        return LOG;
    }

    @Override
    public void registerServerLifecycle(ServerLifecycleListener listener) {
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) ->
                listener.onServerStarting(NeoForgeServerHandle.starting(event.getServer())));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> listener.onServerStopping());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> listener.onServerStopped());
    }

    /**
     * Server handle backed by a live {@link MinecraftServer}. Start time is
     * captured at construction (the ServerStartingEvent).
     */
    private record NeoForgeServerHandle(MinecraftServer server, long startedAtEpochMs) implements ServerHandle {

        private NeoForgeServerHandle {
            java.util.Objects.requireNonNull(server, "server");
        }

        static NeoForgeServerHandle starting(MinecraftServer server) {
            return new NeoForgeServerHandle(server, System.currentTimeMillis());
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
                    server.getMotd());
        }
    }
}
