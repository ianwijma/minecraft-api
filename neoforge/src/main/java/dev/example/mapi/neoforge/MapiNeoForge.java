package dev.example.mapi.neoforge;

import dev.example.mapi.internal.MapiBootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint. Deliberately thin: forwards to the loader-neutral
 * bootstrap. All real work lives in the common module.
 */
@Mod(MapiNeoForge.MOD_ID)
public final class MapiNeoForge {

    /**
     * The MAPI mod id ({@value}).
     */
    public static final String MOD_ID = "mapi";

    /**
     * NeoForge entrypoint invoked when the mod container is constructed,
     * before any server can start.
     *
     * @param modEventBus mod-specific event bus (unused; server events are
     *                    registered on the game bus by the platform adapter)
     * @param modContainer this mod's container (unused today, kept for future
     *                     configuration integration)
     */
    public MapiNeoForge(IEventBus modBus, ModContainer modContainer) {
        MapiBootstrap.initialize(new NeoForgePlatform());
        // Dist-guarded client registration (docs/architecture.md): the
        // listener body never runs on a dedicated server, so the client-only
        // bridge class is never loaded there (spec §20).
        modBus.addListener(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class,
                event -> {
                    if (net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()) {
                        dev.example.mapi.neoforge.client.NeoForgeClientBridge bridge =
                                new dev.example.mapi.neoforge.client.NeoForgeClientBridge();
                        bridge.initialize();
                        dev.example.mapi.internal.client.ClientBridgeHolder.set(bridge);
                    }
                });
    }
}
