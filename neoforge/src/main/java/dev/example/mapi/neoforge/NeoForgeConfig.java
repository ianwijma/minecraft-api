package dev.example.mapi.neoforge;

import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.config.MapiConfigException;
import java.util.Arrays;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge-native configuration (spec §9.2/§9.3 keys, loader convention:
 * auto-generated {@code config/mapi-common.toml} with comments and defaults
 * on first run — nothing hand-created). Environment variables
 * ({@code MAPI_HTTP_*}) override file values at load time.
 */
public final class NeoForgeConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue HTTP_ENABLED = BUILDER
            .comment("Enable the local loopback HTTP API (bearer-token authenticated)")
            .define("http.enabled", false);

    private static final ModConfigSpec.IntValue HTTP_PORT = BUILDER
            .comment("Loopback port to bind (fixed to 127.0.0.1, not configurable)")
            .defineInRange("http.port", MapiConfig.DEFAULT_PORT, 1, 65535);

    private static final ModConfigSpec.ConfigValue<String> HTTP_TOKEN = BUILDER
            .comment("Bearer token (>= 16 chars; prefer the MAPI_HTTP_TOKEN env var). "
                    + "Generate: python3 -c \"import secrets; print(secrets.token_urlsafe(32))\"")
            .define("http.token", "");

    private static final ModConfigSpec.IntValue HTTP_RATE_LIMIT = BUILDER
            .comment("Requests per client per minute")
            .defineInRange("http.rateLimitPerMinute", MapiConfig.DEFAULT_RATE_LIMIT, 1, 100_000);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> HTTP_SCOPES = BUILDER
            .comment("Granted scopes (spec §14); empty list grants the full set. "
                    + "Valid: client:connect, client:settings, server:tick-control, "
                    + "server:publish, operations:destructive, operations:unrestricted")
            .defineList("http.scopes", List.of(), entry -> entry instanceof String s
                    && !s.isBlank());

    private static final ModConfigSpec.ConfigValue<List<? extends String>> CONNECT_ALLOWLIST = BUILDER
            .comment("Direct-connection targets as host[:port] (spec §9.2); "
                    + "empty list denies all connections")
            .defineList("client.connect.allowlist", List.of(), entry -> entry instanceof String s
                    && s.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?"));

    private static final ModConfigSpec.BooleanValue SERVER_LAN = BUILDER
            .comment("Whether integrated-server LAN publication is permitted (spec §9.3)")
            .define("server.lan.enabled", false);

    /** The spec registered with the mod container (generates the TOML). */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private NeoForgeConfig() {
    }

    /**
     * Maps the loaded spec values into the loader-neutral configuration, with
     * environment variables winning over file values.
     *
     * @param env    process environment
     * @param logger platform logger
     * @return the validated configuration
     * @throws MapiConfigException when values are invalid
     */
    public static MapiConfig toMapiConfig(java.util.Map<String, String> env,
            org.slf4j.Logger logger) {
        java.util.Set<dev.example.mapi.internal.operation.Scope> scopes =
                parseScopes(HTTP_SCOPES.get());
        java.util.List<String> allowlist = List.copyOf(CONNECT_ALLOWLIST.get());
        return MapiConfig.fromValues(
                HTTP_ENABLED.get(),
                HTTP_PORT.get(),
                HTTP_TOKEN.get(),
                HTTP_RATE_LIMIT.get(),
                scopes,
                allowlist,
                SERVER_LAN.get(),
                env, logger);
    }

    private static java.util.Set<dev.example.mapi.internal.operation.Scope> parseScopes(
            List<? extends String> raw) {
        if (raw == null || raw.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.EnumSet<dev.example.mapi.internal.operation.Scope> parsed =
                java.util.EnumSet.noneOf(dev.example.mapi.internal.operation.Scope.class);
        for (String name : raw) {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            java.util.Optional<dev.example.mapi.internal.operation.Scope> match =
                    Arrays.stream(dev.example.mapi.internal.operation.Scope.values())
                            .filter(scope -> scope.wireName().equalsIgnoreCase(trimmed))
                            .findFirst();
            if (match.isEmpty()) {
                throw new MapiConfigException("http.scopes contains unknown scope '" + trimmed + "'");
            }
            parsed.add(match.get());
        }
        return java.util.Collections.unmodifiableSet(parsed);
    }
}
