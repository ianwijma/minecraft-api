package dev.example.mapi.api;

import java.util.Optional;

/**
 * Public, loader-neutral MAPI facade. Obtain an instance with
 * {@link MapiApi#get()} (or {@link MapiApi#require()}) after MAPI has been
 * bootstrapped by the loader entrypoint.
 *
 * <p>Every implementation is immutable and thread-safe.
 */
public interface Mapi {

    /**
     * @return the MAPI mod version (for example {@code "0.1.0"}), never
     *         {@code null}
     */
    String modVersion();

    /**
     * @return the version of this public Java API. For MAPI 0.x this equals
     *         {@link #modVersion()}; the two may diverge once MAPI reaches
     *         1.0.0. Never {@code null}.
     */
    String apiVersion();

    /**
     * @return the running Minecraft version as reported by the loader (for
     *         example {@code "26.2"}), never {@code null}
     */
    String minecraftVersion();

    /**
     * @return the platform MAPI runs on, never {@code null}
     */
    PlatformType platform();

    /**
     * @return the version string of the loader itself (Fabric Loader or
     *         NeoForge), never {@code null} but may be {@code "unknown"} on
     *         unexpected loaders
     */
    String platformVersion();

    /**
     * Captures a point-in-time server status snapshot.
     *
     * <p>The snapshot is taken on the Minecraft server thread with a bounded
     * wait, so this method never blocks the caller indefinitely and never
     * blocks the tick thread. Behavior:
     *
     * <ul>
     *   <li>No server is running: returns {@link Optional#empty()}.</li>
     *   <li>A server is running but a snapshot could not be taken within the
     *       bounded wait (busy tick thread): returns {@link Optional#empty()}
     *       and logs at debug level.</li>
     * </ul>
     *
     * @return the current snapshot, or an empty optional as described above
     */
    Optional<ServerStatusSnapshot> serverStatus();

    /**
     * @return the extension/service registry, never {@code null}
     */
    MapiServices services();
}
