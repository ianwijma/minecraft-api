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

    /**
     * @return the server-side bridge capabilities for this loader; the
     *     default reports no capabilities (nothing beyond the base runtime)
     */
    default dev.example.mapi.internal.server.ServerBridge serverBridge() {
        return dev.example.mapi.internal.server.ServerBridge.NONE;
    }

    /**
     * @return the client-side bridge while a game client is present and the
     *     loader implements client capabilities; the default reads the
     *     client-only registration point
     *     ({@link dev.example.mapi.internal.client.ClientBridgeHolder}) so
     *     dedicated servers keep
     *     {@link dev.example.mapi.internal.client.ClientBridge#NONE}
     */
    default dev.example.mapi.internal.client.ClientBridge clientBridge() {
        return dev.example.mapi.internal.client.ClientBridgeHolder.get();
    }

    /**
     * Attaches a bounded log-capture sink to the process logging backend
     * (spec §17.1). The default is a no-op: loader adapters wire the Log4j
     * appender in their own chunks; no global logging reconfiguration is
     * permitted.
     *
     * @param capture the sink to feed, never {@code null}
     */
    default void attachLogCapture(dev.example.mapi.internal.logging.LogCaptureService capture) {
    }

    /**
     * Requests a graceful local shutdown of the process (spec §1.1: the mod
     * performs graceful local shutdown only; the runner owns launch/kill).
     * The default reports unsupported.
     *
     * @return true when the shutdown was accepted
     */
    default boolean requestProcessShutdown() {
        return false;
    }
}
