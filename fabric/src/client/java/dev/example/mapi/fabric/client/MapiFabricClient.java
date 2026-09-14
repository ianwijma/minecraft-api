package dev.example.mapi.fabric.client;

import dev.example.mapi.fabric.MapiFabricHooks;
import dev.example.mapi.internal.client.ClientBridgeHolder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * Fabric client entrypoint (client source set only). Registers the client
 * bridge and the client lifecycle hook in the loader-neutral holder/main
 * seam; a dedicated server never loads this class.
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
        MapiFabricHooks.setClientLifecycleRegistrar(listener -> {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> listener.onClientStarted());
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> listener.onClientStopping());
        });
    }
}
