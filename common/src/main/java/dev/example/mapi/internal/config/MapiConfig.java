package dev.example.mapi.internal.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

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
 *   <tr><td>http.tokenFile</td><td>MAPI_HTTP_TOKEN_FILE</td><td>{@code <gameDir>/mcapi/token}</td>
 *       <td>Token file; auto-generated when enabled and absent</td></tr>
 *   <tr><td>http.rateLimitPerMinute</td><td>MAPI_HTTP_RATE_LIMIT_PER_MINUTE</td><td>60</td><td>Requests per client per minute</td></tr>
 *   <tr><td>http.instanceId</td><td>MAPI_INSTANCE_ID</td><td>{@code mapi-<port>}</td>
 *       <td>Instance identifier (sanitized); used by the discovery file</td></tr>
 *   <tr><td>http.portFallback</td><td>MAPI_HTTP_PORT_FALLBACK</td><td>0</td>
 *       <td>Try up to N consecutive ports above http.port when binding</td></tr>
 *   <tr><td>http.failFast</td><td>MAPI_HTTP_FAIL_FAST</td><td>false</td>
 *       <td>Fail startup when no port can be bound (harness/CI runs)</td></tr>
 *   <tr><td>http.discoveryHeartbeatSeconds</td><td>MAPI_DISCOVERY_HEARTBEAT_SECONDS</td><td>30</td>
 *       <td>Discovery file refresh interval (staleness signal)</td></tr>
 *   <tr><td>http.scopes</td><td>MAPI_HTTP_SCOPES</td><td>all scopes</td>
 *       <td>Comma-separated scope set bound to the token (spec §4.2)</td></tr>
 *   <tr><td>http.commandPermissionLevel</td><td>MAPI_HTTP_COMMAND_PERMISSION_LEVEL</td><td>2</td>
 *       <td>Command permission ceiling (0..4) for command execution</td></tr>
 *   <tr><td>reflection.enabled</td><td>MAPI_REFLECTION_ENABLED</td><td>false</td>
 *       <td>Enable the unsafe reflection/invoke surface (spec §4.5)</td></tr>
 * </table>
 *
 * <p>Token resolution order when HTTP is enabled: {@code MAPI_HTTP_TOKEN},
 * then {@code http.token}, then the token file (auto-generated if absent).
 * The token value is never logged; a non-secret SHA-256 fingerprint of a
 * newly generated token is logged together with the file location. The bind
 * address is fixed to loopback and is deliberately not configurable.
 */
public record MapiConfig(
        boolean httpEnabled,
        int httpPort,
        String httpToken,
        int rateLimitPerMinute,
        String instanceId,
        Path tokenFile,
        int portFallback,
        boolean failFast,
        int discoveryHeartbeatSeconds,
        Set<String> scopes,
        int commandPermissionLevel,
        boolean reflectionEnabled) {

    /** Default HTTP port. */
    public static final int DEFAULT_PORT = 25586;

    /** Default rate limit (requests per client per minute). */
    public static final int DEFAULT_RATE_LIMIT = 60;

    /** Default discovery file heartbeat interval in seconds. */
    public static final int DEFAULT_DISCOVERY_HEARTBEAT_SECONDS = 30;

    /** Default command permission ceiling (gamemaster level). */
    public static final int DEFAULT_COMMAND_PERMISSION_LEVEL = 2;

    /** Minimum accepted bearer token length when HTTP is enabled. */
    public static final int MIN_TOKEN_LENGTH = 16;

    /** Directory (inside the game dir) for token and discovery files. */
    public static final String DATA_DIR_NAME = "mcapi";

    /** Default token file name inside {@link #DATA_DIR_NAME}. */
    public static final String TOKEN_FILE_NAME = "token";

    /** Configuration file name inside the config directory. */
    public static final String CONFIG_FILE_NAME = "mapi.properties";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Convenience constructor matching the pre-0.1 shape (no token file or
     * instance id); used by tests and callers that set both explicitly.
     *
     * @param httpEnabled       whether the HTTP API is enabled
     * @param httpPort          loopback port
     * @param httpToken         bearer token or {@code null}
     * @param rateLimitPerMinute requests per client per minute
     */
    public MapiConfig(boolean httpEnabled, int httpPort, String httpToken, int rateLimitPerMinute) {
        this(httpEnabled, httpPort, httpToken, rateLimitPerMinute, "mapi-" + httpPort, null, 0, false,
                DEFAULT_DISCOVERY_HEARTBEAT_SECONDS, dev.example.mapi.internal.auth.Scope.ALL,
                DEFAULT_COMMAND_PERMISSION_LEVEL, false);
    }

    /**
     * Loads and validates the configuration.
     *
     * @param configDir instance configuration directory
     * @param gameDir   instance game directory (default token file location)
     * @param env       process environment (passed in for testability)
     * @param logger    logger for warnings; secrets are never logged
     * @return a validated configuration
     * @throws MapiConfigException if values are invalid, or if HTTP is
     *                             enabled and no usable token can be
     *                             resolved or generated
     */
    public static MapiConfig load(Path configDir, Path gameDir, Map<String, String> env, Logger logger) {
        Properties file = readPropertiesFile(configDir, logger);

        boolean enabled = readBool(file, env, "http.enabled", "MAPI_HTTP_ENABLED", false, logger);
        int port = readInt(file, env, "http.port", "MAPI_HTTP_PORT", DEFAULT_PORT);
        int rateLimit = readInt(file, env, "http.rateLimitPerMinute", "MAPI_HTTP_RATE_LIMIT_PER_MINUTE",
                DEFAULT_RATE_LIMIT);
        String token = readString(file, env, "http.token", "MAPI_HTTP_TOKEN", null);
        String tokenFileRaw = readString(file, env, "http.tokenFile", "MAPI_HTTP_TOKEN_FILE", null);
        String instanceIdRaw = readString(file, env, "http.instanceId", "MAPI_INSTANCE_ID", null);
        int portFallback = readInt(file, env, "http.portFallback", "MAPI_HTTP_PORT_FALLBACK", 0);
        boolean failFast = readBool(file, env, "http.failFast", "MAPI_HTTP_FAIL_FAST", false, logger);
        int heartbeat = readInt(file, env, "http.discoveryHeartbeatSeconds",
                "MAPI_DISCOVERY_HEARTBEAT_SECONDS", DEFAULT_DISCOVERY_HEARTBEAT_SECONDS);
        Set<String> scopes;
        try {
            scopes = dev.example.mapi.internal.auth.Scope.parse(
                    readString(file, env, "http.scopes", "MAPI_HTTP_SCOPES", null));
        } catch (IllegalArgumentException e) {
            throw new MapiConfigException(e.getMessage());
        }
        int commandLevel = readInt(file, env, "http.commandPermissionLevel",
                "MAPI_HTTP_COMMAND_PERMISSION_LEVEL", DEFAULT_COMMAND_PERMISSION_LEVEL);
        boolean reflectionEnabled = readBool(file, env, "reflection.enabled", "MAPI_REFLECTION_ENABLED",
                false, logger);

        if (port < 1 || port > 65535) {
            throw new MapiConfigException("http.port must be between 1 and 65535 (got " + port + ")");
        }
        if (rateLimit < 1 || rateLimit > 100_000) {
            throw new MapiConfigException("http.rateLimitPerMinute must be between 1 and 100000 (got " + rateLimit + ")");
        }
        if (portFallback < 0 || portFallback > 64) {
            throw new MapiConfigException("http.portFallback must be between 0 and 64 (got " + portFallback + ")");
        }
        if (heartbeat < 5 || heartbeat > 3600) {
            throw new MapiConfigException("http.discoveryHeartbeatSeconds must be between 5 and 3600 (got "
                    + heartbeat + ")");
        }
        if (commandLevel < 0 || commandLevel > 4) {
            throw new MapiConfigException("http.commandPermissionLevel must be between 0 and 4 (got "
                    + commandLevel + ")");
        }

        Path tokenFilePath = resolveTokenFile(tokenFileRaw, gameDir);
        String instanceId = sanitizeInstanceId(instanceIdRaw, port, logger);

        if (enabled && (token == null || token.isBlank())) {
            token = readOrCreateTokenFile(tokenFilePath, logger);
        }
        if (enabled) {
            requireUsableToken(token, configDir);
        }

        return new MapiConfig(enabled, port, token == null ? null : token.trim(), rateLimit, instanceId,
                tokenFilePath, portFallback, failFast, heartbeat, scopes, commandLevel, reflectionEnabled);
    }

    @Override
    public String toString() {
        // Records would include the raw token; keep every log path safe.
        return "MapiConfig[httpEnabled=" + httpEnabled
                + ", httpPort=" + httpPort
                + ", httpToken=" + (httpToken == null ? "null" : "<redacted>")
                + ", rateLimitPerMinute=" + rateLimitPerMinute
                + ", instanceId=" + instanceId
                + ", tokenFile=" + tokenFile
                + ", portFallback=" + portFallback
                + ", failFast=" + failFast
                + ", discoveryHeartbeatSeconds=" + discoveryHeartbeatSeconds
                + ", scopes=" + scopes
                + ", commandPermissionLevel=" + commandPermissionLevel
                + ", reflectionEnabled=" + reflectionEnabled
                + "]";
    }

    private static void requireUsableToken(String token, Path configDir) {
        if (token == null || token.isBlank()) {
            throw new MapiConfigException("MAPI HTTP API is enabled but no bearer token is available. "
                    + "Set the MAPI_HTTP_TOKEN environment variable, http.token in "
                    + configDir.resolve(CONFIG_FILE_NAME) + ", or make the token file writable at "
                    + "<gameDir>/" + DATA_DIR_NAME + "/" + TOKEN_FILE_NAME + " (it is created automatically).");
        }
        if (token.trim().length() < MIN_TOKEN_LENGTH) {
            throw new MapiConfigException("MAPI HTTP API bearer token is shorter than " + MIN_TOKEN_LENGTH
                    + " characters. Generate a long random token, for example: "
                    + "python3 -c \"import secrets; print(secrets.token_urlsafe(32))\"");
        }
    }

    private static Path resolveTokenFile(String raw, Path gameDir) {
        Path path;
        if (raw == null || raw.isBlank()) {
            path = gameDir.resolve(DATA_DIR_NAME).resolve(TOKEN_FILE_NAME);
        } else {
            path = Path.of(raw.trim());
            if (!path.isAbsolute()) {
                path = gameDir.resolve(path);
            }
        }
        return path.normalize();
    }

    /**
     * Reads the token file, or generates a fresh one when it does not exist.
     * Generation is atomic and never logs the token value.
     */
    private static String readOrCreateTokenFile(Path tokenFile, Logger logger) {
        if (Files.isRegularFile(tokenFile)) {
            try {
                String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            } catch (IOException e) {
                throw new MapiConfigException("Failed to read token file " + tokenFile + ": " + e.getMessage());
            }
        }
        return generateTokenFile(tokenFile, logger);
    }

    private static String generateTokenFile(Path tokenFile, Logger logger) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            Path dir = tokenFile.getParent();
            Files.createDirectories(dir);
            restrictPermissions(dir, true);
            Path temp = dir.resolve(tokenFile.getFileName() + ".tmp-" + ProcessHandle.current().pid());
            Files.writeString(temp, token + "\n", StandardCharsets.UTF_8);
            restrictPermissions(temp, false);
            try {
                Files.move(temp, tokenFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, tokenFile, StandardCopyOption.REPLACE_EXISTING);
                restrictPermissions(tokenFile, false);
            }
            logger.info("MAPI: generated bearer token file {} (fingerprint {}). Never share or commit this file.",
                    tokenFile, fingerprint(token));
            return token;
        } catch (IOException e) {
            throw new MapiConfigException("Failed to write token file " + tokenFile + ": " + e.getMessage()
                    + ". Fix permissions or set MAPI_HTTP_TOKEN / http.token instead.");
        }
    }

    private static void restrictPermissions(Path path, boolean executable) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(executable ? "rwx------" : "rw-------"));
            }
        } catch (IOException e) {
            throw new MapiConfigException("Failed to restrict permissions on " + path + ": " + e.getMessage());
        }
    }

    /**
     * @return the first 12 hex characters of the token's SHA-256 digest, a
     *         stable non-secret fingerprint for log correlation
     */
    static String fingerprint(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)), 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    /**
     * Sanitizes the configured instance id: lowercase, {@code [a-z0-9-]} only,
     * at most 32 characters. Invalid input degrades to the default instead of
     * failing the game.
     */
    static String sanitizeInstanceId(String raw, int port, Logger logger) {
        String base = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
                sb.append(c);
            } else if (c == '_' || c == ' ' || c == '.') {
                sb.append('-');
            }
        }
        String sanitized = sb.toString();
        while (sanitized.startsWith("-")) {
            sanitized = sanitized.substring(1);
        }
        while (sanitized.endsWith("-")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        if (sanitized.length() > 32) {
            sanitized = sanitized.substring(0, 32);
        }
        if (sanitized.isBlank()) {
            if (raw != null && !raw.isBlank()) {
                logger.warn("MAPI: http.instanceId '{}' is not usable; falling back to the default id", raw);
            }
            sanitized = "mapi-" + port;
        }
        return sanitized;
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
            "http.enabled", "http.port", "http.token", "http.tokenFile", "http.rateLimitPerMinute",
            "http.instanceId", "http.portFallback", "http.failFast", "http.discoveryHeartbeatSeconds",
            "http.scopes", "http.commandPermissionLevel", "reflection.enabled");

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
