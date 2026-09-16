package dev.example.mapi.fabric;

import dev.example.mapi.internal.server.ServerBridge;
import dev.example.mapi.internal.tick.TickControlBackend;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.MinecraftServer;

/**
 * Fabric server-side bridge. Capabilities are added here one chunk at a
 * time; a capability is advertised only once it is implemented and tested on
 * this loader (spec §15.3).
 */
final class FabricServerBridge implements ServerBridge {

    private volatile MinecraftServer current;

    void onServerStarting(MinecraftServer server) {
        this.current = server;
    }

    void onServerStopped() {
        this.current = null;
    }

    @Override
    public String bridgeId() {
        return "mapi-fabric";
    }

    @Override
    public Set<String> supportedCapabilities() {
        return current == null ? Set.of() : Set.of(
                "server.progress-detection", "server.tick-control", "server.world-queries",
                "server.commands");
    }

    @Override
    public Optional<TickControlBackend> tickControl() {
        MinecraftServer server = current;
        return server == null ? Optional.empty() : Optional.of(new FabricTickControlBackend(server));
    }

    @Override
    public Optional<dev.example.mapi.internal.query.WorldQueryBackend> worldQueries() {
        MinecraftServer server = current;
        return server == null ? Optional.empty()
                : Optional.of(new FabricWorldQueryBackend(server));
    }

    @Override
    public Optional<dev.example.mapi.internal.command.CommandBackend> commands() {
        MinecraftServer server = current;
        return server == null ? Optional.empty() : Optional.of(new FabricCommandBackend(server));
    }
}
