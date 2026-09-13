package dev.example.mapi.example;

import dev.example.mapi.api.Mapi;
import dev.example.mapi.api.MapiApi;
import dev.example.mapi.api.MapiService;
import dev.example.mapi.api.ServerStatusSnapshot;
import java.util.Locale;
import java.util.Optional;

/**
 * Minimal consumer example: uses ONLY the documented public API
 * ({@code dev.example.mapi.api}) and must compile without any loader
 * dependencies. This module is excluded from all production jars.
 *
 * <p>Inside a real mod, call these methods after MAPI bootstrap (mod
 * construction / Fabric onInitialize). Outside a game process (tests,
 * tools), {@link MapiApi#get()} is empty — which this class demonstrates.
 */
public final class ConsumerExample {

    private ConsumerExample() {
    }

    /**
     * Returns the current server status snapshot, or an empty optional when
     * MAPI is not bootstrapped or no server is running.
     *
     * @return the snapshot, if available
     */
    public static Optional<ServerStatusSnapshot> serverStatus() {
        return MapiApi.get().flatMap(Mapi::serverStatus);
    }

    /**
     * Returns the platform MAPI runs on, or an empty optional before
     * bootstrap.
     *
     * @return lowercase platform id, e.g. {@code "fabric"} or
     *         {@code "neoforge"}
     */
    public static Optional<String> platformId() {
        return MapiApi.get().map(mapi -> mapi.platform().id().toLowerCase(Locale.ROOT));
    }

    /**
     * Builds a tiny status line for logging or dashboards.
     *
     * @return one-line summary; never {@code null}
     */
    public static String statusLine() {
        return MapiApi.get()
                .map(mapi -> "MAPI %s on %s (Minecraft %s)".formatted(
                        mapi.modVersion(), mapi.platform().id(), mapi.minecraftVersion()))
                .orElse("MAPI not initialized");
    }

    /**
     * Example extension registration. Call this during your mod's
     * initialization phase, after MAPI bootstrap.
     */
    public static void registerExampleService() {
        MapiApi.require().services().register("example-consumer", new MapiService() {
            @Override
            public String id() {
                return "example-consumer";
            }

            @Override
            public void onServerStart() {
                // Invoked on the server thread; keep it fast.
            }
        });
    }
}
