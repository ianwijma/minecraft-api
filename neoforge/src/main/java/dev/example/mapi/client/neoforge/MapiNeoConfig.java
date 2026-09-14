package dev.example.mapi.client.neoforge;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.config.MapiConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Loader-native MAPI configuration for NeoForge: a COMMON
 * {@link ModConfigSpec} registered through the {@code ModContainer}, so FML
 * generates and manages {@code mapi-common.toml} exactly like any other mod
 * (creation, comments, correction, reload). Keys are identical to the
 * loader-neutral TOML; {@code ModConfigEvent.Loading/Reloading} hands the
 * values to {@link MapiRuntime#startHttpWith}.
 *
 * <p>The {@code http.token} value defaults to empty: prefer the
 * {@code MAPI_HTTP_TOKEN} environment variable or the auto-generated token
 * file ({@code mcapi/token}) — the file value is a documented convenience
 * for single-operator machines.
 */
public final class MapiNeoConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<java.lang.Boolean> HTTP_ENABLED =
            BUILDER.comment("Enable the local HTTP API (loopback only, bearer token required).")
                    .define("http.enabled", false);

    private static final ModConfigSpec.ConfigValue<java.lang.Integer> HTTP_PORT =
            BUILDER.comment("Loopback port to bind.")
                    .defineInRange("http.port", 25586, 1, 65535);

    private static final ModConfigSpec.ConfigValue<java.lang.Integer> RATE_LIMIT =
            BUILDER.comment("Requests per client per minute.")
                    .defineInRange("http.rateLimitPerMinute", 60, 1, 100_000);

    private static final ModConfigSpec.ConfigValue<java.lang.String> INSTANCE_ID =
            BUILDER.comment("Instance identifier (sanitized to [a-z0-9-], max 32 chars).")
                    .define("http.instanceId", "mapi");

    private static final ModConfigSpec.ConfigValue<java.lang.String> TOKEN =
            BUILDER.comment("Bearer token. Prefer the MAPI_HTTP_TOKEN env var or the auto-generated "
                    + "token file; never commit a real token.")
                    .define("http.token", "");

    private static final ModConfigSpec.ConfigValue<java.lang.String> TOKEN_FILE =
            BUILDER.comment("Token file (relative to the game dir unless absolute); auto-generated.")
                    .define("http.tokenFile", "mcapi/token");

    private static final ModConfigSpec.ConfigValue<java.lang.Integer> PORT_FALLBACK =
            BUILDER.comment("Try up to N consecutive ports above http.port when binding (0-64).")
                    .defineInRange("http.portFallback", 0, 0, 64);

    private static final ModConfigSpec.ConfigValue<java.lang.Boolean> FAIL_FAST =
            BUILDER.comment("Fail startup when no port can be bound (harness/CI runs).")
                    .define("http.failFast", false);

    private static final ModConfigSpec.ConfigValue<java.lang.String> SCOPES =
            BUILDER.comment("Comma-separated scopes for the token (empty = all). See docs/CAPABILITIES.md.")
                    .define("http.scopes", "");

    private static final ModConfigSpec.ConfigValue<java.lang.Integer> COMMAND_LEVEL =
            BUILDER.comment("Command permission ceiling (0..4).")
                    .defineInRange("http.commandPermissionLevel", 2, 0, 4);

    private static final ModConfigSpec.ConfigValue<java.lang.Integer> HEARTBEAT =
            BUILDER.comment("Discovery file refresh interval in seconds (5-3600).")
                    .defineInRange("http.discoveryHeartbeatSeconds", 30, 5, 3600);

    private static final ModConfigSpec.ConfigValue<java.lang.Boolean> REFLECTION =
            BUILDER.comment("EXPERIMENTAL: enable /api/v1/unsafe/* (runs with game privileges, no sandbox).")
                    .define("reflection.enabled", false);

    private static final ModConfigSpec.ConfigValue<java.lang.Boolean> FILES =
            BUILDER.comment("EXPERIMENTAL: enable the sandboxed /api/v1/files surface.")
                    .define("files.enabled", false);

    private static final ModConfigSpec SPEC = BUILDER.build();

    private MapiNeoConfig() {
    }

    /**
     * Registers the config and the load/reload listeners that feed the
     * runtime.
     *
     * @param modContainer  this mod's container (registers the config file)
     * @param modEventBus   mod event bus for {@link ModConfigEvent}
     * @param gameDir       instance game directory (token file root)
     * @param runtimeSupplier supplies the runtime once initialized
     */
    public static void register(net.neoforged.fml.ModContainer modContainer, IEventBus modEventBus,
            Path gameDir, java.util.function.Supplier<MapiRuntime> runtimeSupplier) {
        modContainer.registerConfig(ModConfig.Type.COMMON, spec());
        IEventBus bus = modContainer.getEventBus();
        Runnable apply = () -> {
            MapiRuntime runtime = runtimeSupplier.get();
            if (runtime != null) {
                runtime.startHttpWith(build(gameDir));
            }
        };
        bus.addListener((ModConfigEvent.Loading event) -> {
            if (isMapiConfig(event)) {
                apply.run();
            }
        });
        bus.addListener((ModConfigEvent.Reloading event) -> {
            if (isMapiConfig(event)) {
                apply.run();
            }
        });
    }

    private static boolean isMapiConfig(ModConfigEvent event) {
        return "mapi".equals(event.getConfig().getModId());
    }

    /** @return the registered spec */
    static ModConfigSpec spec() {
        return SPEC;
    }

    /**
     * Maps spec values into a validated {@link MapiConfig} (env overrides
     * apply; token resolution including auto-generation is shared).
     */
    static MapiConfig build(Path gameDir) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("http.enabled", String.valueOf(HTTP_ENABLED.get()));
        values.put("http.port", String.valueOf(HTTP_PORT.get()));
        values.put("http.rateLimitPerMinute", String.valueOf(RATE_LIMIT.get()));
        values.put("http.instanceId", INSTANCE_ID.get());
        values.put("http.token", TOKEN.get() == null ? "" : TOKEN.get());
        values.put("http.tokenFile", TOKEN_FILE.get());
        values.put("http.portFallback", String.valueOf(PORT_FALLBACK.get()));
        values.put("http.failFast", String.valueOf(FAIL_FAST.get()));
        values.put("http.scopes", SCOPES.get());
        values.put("http.commandPermissionLevel", String.valueOf(COMMAND_LEVEL.get()));
        values.put("http.discoveryHeartbeatSeconds", String.valueOf(HEARTBEAT.get()));
        values.put("reflection.enabled", String.valueOf(REFLECTION.get()));
        values.put("files.enabled", String.valueOf(FILES.get()));
        return MapiConfig.resolve(gameDir.resolve("config"), gameDir, values, System.getenv(),
                org.slf4j.LoggerFactory.getLogger("mapi"));
    }
}
