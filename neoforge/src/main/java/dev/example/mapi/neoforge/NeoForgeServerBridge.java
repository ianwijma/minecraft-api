package dev.example.mapi.neoforge;

import dev.example.mapi.internal.server.ServerBridge;
import java.util.Set;

/**
 * NeoForge server-side bridge. Capabilities are added here one chunk at a
 * time; a capability is advertised only once it is implemented and tested on
 * this loader (spec §15.3).
 */
final class NeoForgeServerBridge implements ServerBridge {

    @Override
    public String bridgeId() {
        return "mapi-neoforge";
    }

    @Override
    public Set<String> supportedCapabilities() {
        return Set.of("server.progress-detection");
    }
}
