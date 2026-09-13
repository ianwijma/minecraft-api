package dev.example.mapi.fabric;

import dev.example.mapi.internal.server.ServerBridge;
import java.util.Set;

/**
 * Fabric server-side bridge. Capabilities are added here one chunk at a
 * time; a capability is advertised only once it is implemented and tested on
 * this loader (spec §15.3).
 */
final class FabricServerBridge implements ServerBridge {

    @Override
    public String bridgeId() {
        return "mapi-fabric";
    }

    @Override
    public Set<String> supportedCapabilities() {
        return Set.of();
    }
}
