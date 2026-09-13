package dev.example.mapi.fabric;

import dev.example.mapi.internal.MapiBootstrap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric entrypoint. Deliberately thin: forwards to the loader-neutral
 * bootstrap. All real work lives in the common module.
 */
public final class MapiFabric implements ModInitializer {

    /**
     * Fabric entrypoint invoked during mod initialization, before any server
     * can start.
     */
    @Override
    public void onInitialize() {
        MapiBootstrap.initialize(new FabricPlatform());
    }

    /**
     * @return the Fabric Loader version string
     */
    static String loaderVersion() {
        return FabricLoader.getInstance().getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /**
     * @return the Minecraft version string as reported by Fabric Loader
     */
    static String minecraftVersion() {
        return FabricLoader.getInstance().getRawGameVersion();
    }
}
