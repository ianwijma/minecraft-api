package dev.example.mapi.api;

/**
 * A MAPI extension. Implementations may be registered with
 * {@link MapiServices#register(String, MapiService)} at any point after MAPI
 * bootstrap (typically during your mod's construction or initialization
 * phase); see {@code docs/api.md} for the exact timing on each loader.
 *
 * <p>Lifecycle callbacks are invoked on the Minecraft server thread while a
 * server is active. They must return quickly and must not block.
 */
public interface MapiService {

    /**
     * Unique service identifier, matching {@code [a-z][a-z0-9_-]{1,63}}.
     *
     * @return the service id, never {@code null} or blank
     */
    String id();

    /**
     * Called once on the server thread when a server (dedicated or
     * integrated) is starting. Exceptions are logged and do not abort the
     * server start or other services.
     */
    default void onServerStart() {
    }

    /**
     * Called once on the server thread when the server is stopping. Cleanup
     * should be performed here; services must not rely on being called again.
     */
    default void onServerStop() {
    }
}
