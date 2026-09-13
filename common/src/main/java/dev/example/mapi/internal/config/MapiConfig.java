package dev.example.mapi.internal.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;

/**
 * MAPI configuration, resolved from {@code <configDir>/mapi.properties} and
 * environment variables. Environment variables win over the file.
 *
 * <p>Recognized keys (file) / environment variables:
 * <table border="1">
 *   <caption>Configuration keys</caption>
 *   <tr><th>File</th><th>Environment</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>http.enabled</td><td>MAPI_HTTP_ENABLED</td><td>false</td><td>Enable the local HTTP API</td></tr>
 *   <tr><td>http.port</td><td>MAPI_HTTP_PORT</td><td>25586</td><td>Loopback port to bind</td></tr>
 *   <tr><td>http.token</td><td>MAPI_HTTP_TOKEN</td><td>none</td><td>Bearer token; prefer the env var</td></tr>
 *   <tr><td>http.rateLimitPerMinute</td><td>MAPI_HTTP_RATE_LIMIT_PER_MINUTE</td><td>60</td><td>Requests per client per minute</td></tr>
 *   <tr><td>http.scopes</td><td>MAPI_HTTP_SCOPES</td><td>all</td><td>Comma-separated granted scopes (spec §14); absent/blank grants the full set</td></tr>
 * </table>
 *
 * <p>Secrets are never logged. The bind address is fixed to loopback and is
 * deliberately not configurable.
 */
public record MapiConfig(
        boolean httpEnabled, int httpPort, String httpToken, int rateLimitPerMinute,
        java.util.Set<dev.example.mapi.internal.operation.Scope> httpScopes) {

    /** Default HTTP port. */
    public static final int DEFAULT_PORT = 25586;

    /** Default rate limit (requests per client per minute). */
    public static final int DEFAULT_RATE_LIMIT = 60;

    /** Minimum accepted bearer token length when HTTP is enabled. */
    public static final int MIN_TOKEN_LENGTH = 16;

    /** Configuration file name inside the config directory. */
    public static final String CONFIG_FILE_NAME = "mapi.properties";

    /**
     * Creates a configuration with the historical shape: no scope
     * restrictions (the token grants the full scope set).
     *
     * @param httpEnabled        whether the HTTP API is enabled
     * @param httpPort           loopback port
     * @param httpToken          bearer token
     * @param rateLimitPerMinute requests per client per minute
     */
    public MapiConfig(boolean httpEnabled, int httpPort, String httpToken, int rateLimitPerMinute) {
        this(httpEnabled, httpPort, httpToken, rateLimitPerMinute, java.util.Set.of());
    }

    /**
     * @param token the presented bearer token, may be {@code null}
     * @return the scopes granted to this token: the configured subset, or the
     *     full set when no subset is configured; empty for an unknown token
     */
    public java.util.Set<dev.example.mapi.internal.operation.Scope> grantedScopes(String token) {
        if (token == null || httpToken == null
                || !java.security.MessageDigest.isEqual(
                        httpToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return java.util.Set.of();
        }
        if (httpScopes.isEmpty()) {
            return java.util.Collections.unmodifiableSet(
                    java.util.EnumSet.allOf(dev.example.mapi.internal.operation.Scope.class));
        }
        return httpScopes;
    }

    /**
     * Loads and validates the configuration.
     *
     * @param configDir instance configuration directory
     * @param env       process environment (passed in for testability)
     * @param logger    logger for warnings; secrets are never logged
     * @return a validated configuration
     * @throws MapiConfigException if values are invalid, or if HTTP is
     *                             enabled without a usable token
     */
    public static MapiConfig load(Path configDir, Map<String, String> env, Logger logger) {
        Properties file = readPropertiesFile(configDir, logger);

        boolean enabled = readBool(file, env, "http.enabled", "MAPI_HTTP_ENABLED", false, logger);
        int port = readInt(file, env, "http.port", "MAPI_HTTP_PORT", DEFAULT_PORT);
        int rateLimit = readInt(file, env, "http.rateLimitPerMinute", "MAPI_HTTP_RATE_LIMIT_PER_MINUTE",
                DEFAULT_RATE_LIMIT);
        String token = readString(file, env, "http.token", "MAPI_HTTP_TOKEN", null);
        java.util.Set<dev.example.mapi.internal.operation.Scope> scopes =
                readScopes(file, env, logger);

        if (port < 1 || port > 65535) {
            throw new MapiConfigException("http.port must be between 1 and 65535 (got " + port + ")");
        }
        if (rateLimit < 1 || rateLimit > 100_000) {
            throw new MapiConfigException("http.rateLimitPerMinute must be between 1 and 100000 (got " + rateLimit + ")");
        }
        if (enabled) {
            if (token == null || token.isBlank()) {
                throw new MapiConfigException("MAPI HTTP API is enabled but no bearer token is configured. "
                        + "Set the MAPI_HTTP_TOKEN environment variable (preferred) or http.token in "
                        + configDir.resolve(CONFIG_FILE_NAME) + ". The HTTP API refuses to start without one.");
            }
            if (token.trim().length() < MIN_TOKEN_LENGTH) {
                throw new MapiConfigException("MAPI HTTP API bearer token is shorter than " + MIN_TOKEN_LENGTH
                        + " characters. Generate a long random token, for example: "
                        + "python3 -c \"import secrets; print(secrets.token_urlsafe(32))\"");
            }
        }
        return new MapiConfig(enabled, port, token == null ? null : token.trim(), rateLimit, scopes);
    }

    private static java.util.Set<dev.example.mapi.internal.operation.Scope> readScopes(
            Properties file, Map<String, String> env, Logger logger) {
        String raw = effective(file, env, "http.scopes", "MAPI_HTTP_SCOPES");
        if (raw == null || raw.isBlank()) {
            return java.util.Set.of();
        }
        java.util.EnumSet<dev.example.mapi.internal.operation.Scope> parsed =
                java.util.EnumSet.noneOf(dev.example.mapi.internal.operation.Scope.class);
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            java.util.Optional<dev.example.mapi.internal.operation.Scope> match =
                    java.util.Arrays.stream(dev.example.mapi.internal.operation.Scope.values())
                            .filter(scope -> scope.wireName().equalsIgnoreCase(name))
                            .findFirst();
            if (match.isEmpty()) {
                throw new MapiConfigException("http.scopes contains unknown scope '" + name
                        + "'. Valid scopes: " + java.util.Arrays.stream(dev.example.mapi.internal.operation.Scope.values())
                                .map(dev.example.mapi.internal.operation.Scope::wireName)
                                .reduce((a, b) -> a + ", " + b).orElse(""));
            }
            parsed.add(match.get());
        }
        logger.info("MAPI: HTTP scope grants restricted to {} scope(s)", parsed.size());
        return java.util.Collections.unmodifiableSet(parsed);
    }

    private static Properties readPropertiesFile(Path configDir, Logger logger) {
        Properties properties = new Properties();
        Path file = configDir.resolve(CONFIG_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new MapiConfigException("Failed to read " + file + ": " + e.getMessage());
        }
        Properties trimmed = new Properties();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            trimmed.setProperty(key.trim(), value == null ? null : value.trim());
            if (!KNOWN_KEYS.contains(key.trim())) {
                logger.warn("MAPI: ignoring unknown config key '{}' in {}", key, file.getFileName());
            }
        }
        return trimmed;
    }

    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "http.enabled", "http.port", "http.token", "http.rateLimitPerMinute", "http.scopes");

    private static String effective(Properties file, Map<String, String> env, String fileKey, String envKey) {
        String fromEnv = env.get(envKey);
        if (fromEnv != null) {
            return fromEnv;
        }
        return file.getProperty(fileKey);
    }

    private static boolean readBool(Properties file, Map<String, String> env, String fileKey, String envKey,
            boolean fallback, Logger logger) {
        String raw = effective(file, env, fileKey, envKey);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        throw new MapiConfigException(fileKey + " must be 'true' or 'false' (got a non-boolean value)");
    }

    private static int readInt(Properties file, Map<String, String> env, String fileKey, String envKey, int fallback) {
        String raw = effective(file, env, fileKey, envKey);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new MapiConfigException(fileKey + " must be an integer (got a non-integer value)");
        }
    }

    private static String readString(Properties file, Map<String, String> env, String fileKey, String envKey,
            String fallback) {
        String raw = effective(file, env, fileKey, envKey);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim();
    }
}
