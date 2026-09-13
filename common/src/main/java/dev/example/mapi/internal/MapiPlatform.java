package dev.example.mapi.internal;

import dev.example.mapi.api.PlatformType;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Loader-neutral platform contract implemented once per loader module. This
 * is the only seam through which MAPI touches the mod loader; the common code
 * must never import loader classes directly.
 */
public interface MapiPlatform {

    /**
     * @return the platform type, never {@code null}
     */
    PlatformType type();

    /**
     * @return the loader version string (Fabric Loader or NeoForge), never
     *         {@code null}
     */
    String platformVersion();

    /**
     * @return the running Minecraft version as reported by the loader, never
     *         {@code null}
     */
    String minecraftVersion();

    /**
     * @return the instance game directory, never {@code null}
     */
    Path gameDir();

    /**
     * @return the instance configuration directory ({@code <gameDir>/config}
     *         by convention), never {@code null}
     */
    Path configDir();

    /**
     * @return the physical side of this process, never {@code null}; a client
     *         process is {@link PhysicalSide#CLIENT} even while it hosts an
     *         integrated server
     */
    PhysicalSide physicalSide();

    /**
     * @return metadata for every loaded mod (loader containers), sorted by
     *         id, never {@code null}; process-wide and thread-safe
     */
    java.util.List<RawModInfo> mods();

    /**
     * @return the platform-provided logger, never {@code null}
     */
    Logger logger();

    /**
     * Registers the given listener for server lifecycle callbacks. Called
     * exactly once during MAPI bootstrap, before any server can start.
     *
     * @param listener the listener, never {@code null}
     */
    void registerServerLifecycle(ServerLifecycleListener listener);
}
