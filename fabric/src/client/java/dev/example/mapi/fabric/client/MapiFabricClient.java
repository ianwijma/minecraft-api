package dev.example.mapi.fabric.client;

import dev.example.mapi.internal.client.ClientBridgeHolder;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entrypoint (client source set only). Registers the client
 * bridge in the loader-neutral holder so the runtime exposes client
 * capabilities; a dedicated server never loads this class.
 */
public final class MapiFabricClient implements ClientModInitializer {

    /**
     * Client entrypoint invoked on the render thread during client
     * initialization.
     */
    @Override
    public void onInitializeClient() {
        FabricClientBridge bridge = new FabricClientBridge();
        bridge.initialize();
        ClientBridgeHolder.set(bridge);
    }
}
