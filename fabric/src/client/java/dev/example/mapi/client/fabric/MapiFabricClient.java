package dev.example.mapi.client.fabric;

import dev.example.mapi.internal.MapiBootstrap;
import dev.example.mapi.internal.client.MapiClientOps;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entrypoint (slice 0.6). Lives in the client source set so a
 * dedicated server never loads it; registers the loader-neutral client
 * operations with the runtime.
 */
public final class MapiFabricClient implements ClientModInitializer {

    /**
     * Fabric client entrypoint invoked on physical clients only.
     */
    @Override
    public void onInitializeClient() {
        MapiClientOps ops = new FabricClientOps();
        MapiBootstrap.registerClientOps(ops, runnable -> FabricClientOps.schedule(runnable));
    }
}
