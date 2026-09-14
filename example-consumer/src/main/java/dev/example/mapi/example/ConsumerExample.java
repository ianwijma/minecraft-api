package dev.example.mapi.example;

import dev.example.mapi.api.Mapi;
import dev.example.mapi.api.MapiApi;
import dev.example.mapi.api.MapiHttpExtension;
import dev.example.mapi.api.MapiService;
import dev.example.mapi.api.ServerStatusSnapshot;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Example HTTP extension (slice 2.4 SPI): exposes {@code ping} under
     * {@code /api/v1/ext/example-ext/ping} and a self-describing schema at
     * {@code /api/v1/ext/example-ext/$schema}. Handlers run on HTTP worker
     * threads — schedule game-state work onto the owning thread yourself.
     */
    public static void registerExampleHttpExtension() {
        MapiApi.require().services().register("example-ext", new MapiHttpExtension() {
            @Override
            public String id() {
                return "example-ext";
            }

            @Override
            public String requiredScope() {
                // Exposes a read-only ping: observe matches the effect.
                return "observe";
            }

            @Override
            public java.util.Map<String, Object> schema() {
                java.util.Map<String, Object> operations = new LinkedHashMap<>();
                operations.put("ping", "returns pong with the caller's name parameter");
                java.util.Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("operations", operations);
                return schema;
            }

            @Override
            public MapiHttpResponse handle(MapiHttpRequest request) {
                String name = request.query().getOrDefault("name", "world");
                return new MapiHttpResponse(200,
                        java.util.Map.of("pong", name, "extension", id()));
            }
        });
    }
}
