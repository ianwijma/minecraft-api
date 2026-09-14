package dev.example.mapi.internal.config;

import dev.example.mapi.internal.json.JsonReader;
import dev.example.mapi.internal.json.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Load-or-generate JSON config file for loaders without a native config
 * system (Fabric: {@code config/mapi.json}, the common hand-rolled
 * convention there). On first run the file is generated with defaults;
 * afterwards the file wins over defaults and environment variables win over
 * the file. Validation stays in {@link MapiConfig#fromValues}.
 */
public final class MapiJsonConfigFile {

    /** Config file name inside the config directory (Fabric path). */
    public static final String JSON_FILE_NAME = "mapi.json";

    private MapiJsonConfigFile() {
    }

    /**
     * Loads (or generates) the JSON file and maps it into the configuration.
     *
     * @param configDir instance configuration directory
     * @param env       process environment (env wins over file values)
     * @param logger    logger; secrets are never logged
     * @return a validated configuration
     * @throws MapiConfigException when the file is unreadable or values are
     *     invalid
     */
    public static MapiConfig load(Path configDir, Map<String, String> env, Logger logger) {
        Path file = configDir.resolve(JSON_FILE_NAME);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("http.enabled", Boolean.FALSE);
        values.put("http.port", MapiConfig.DEFAULT_PORT);
        values.put("http.token", MapiTokens.generate());
        values.put("http.rateLimitPerMinute", MapiConfig.DEFAULT_RATE_LIMIT);
        values.put("http.scopes", new ArrayList<String>());
        values.put("client.connect.allowlist", new ArrayList<String>());
        values.put("server.lan.enabled", Boolean.FALSE);

        if (Files.isRegularFile(file)) {
            try {
                Object parsed = JsonReader.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (!(parsed instanceof Map<?, ?> document)) {
                    throw new MapiConfigException(
                            file.getFileName() + " must contain a JSON object");
                }
                for (Map.Entry<?, ?> entry : document.entrySet()) {
                    values.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } catch (IOException | IllegalArgumentException e) {
                throw new MapiConfigException("Failed to read " + file + ": " + e.getMessage());
            }
        } else {
            try {
                Files.createDirectories(configDir);
                Files.writeString(file, JsonWriter.write(values) + "\n",
                        StandardCharsets.UTF_8);
                logger.info("MAPI: generated default config {}", file);
            } catch (IOException e) {
                throw new MapiConfigException("Failed to generate " + file + ": " + e.getMessage());
            }
        }

        boolean enabled = booleanValue(values.get("http.enabled"), "http.enabled", false);
        int port = intValue(values.get("http.port"), "http.port", MapiConfig.DEFAULT_PORT);
        String token = textValue(values.get("http.token"));
        int rateLimit = intValue(values.get("http.rateLimitPerMinute"),
                "http.rateLimitPerMinute", MapiConfig.DEFAULT_RATE_LIMIT);
        java.util.Set<dev.example.mapi.internal.operation.Scope> scopes =
                parseScopes(listValue(values.get("http.scopes"), "http.scopes"));
        List<String> allowlist = stringList(values.get("client.connect.allowlist"),
                "client.connect.allowlist");
        boolean lanEnabled = booleanValue(values.get("server.lan.enabled"),
                "server.lan.enabled", false);

        return MapiConfig.fromValues(enabled, port, token, rateLimit, scopes, allowlist,
                lanEnabled, env, logger);
    }

    private static boolean booleanValue(Object value, String key, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new MapiConfigException(key + " must be a boolean");
    }

    private static int intValue(Object value, String key, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new MapiConfigException(key + " must be an integer");
    }

    private static String textValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> listValue(Object value, String key) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        throw new MapiConfigException(key + " must be a list of strings");
    }

    private static List<String> stringList(Object value, String key) {
        List<String> raw = listValue(value, key);
        for (String entry : raw) {
            if (!entry.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) {
                throw new MapiConfigException(
                        key + " entry is not host[:port]: '" + entry + "'");
            }
        }
        return List.copyOf(raw);
    }

    private static java.util.Set<dev.example.mapi.internal.operation.Scope> parseScopes(
            List<String> raw) {
        if (raw.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.EnumSet<dev.example.mapi.internal.operation.Scope> parsed =
                java.util.EnumSet.noneOf(dev.example.mapi.internal.operation.Scope.class);
        for (String name : raw) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            java.util.Optional<dev.example.mapi.internal.operation.Scope> match =
                    java.util.Arrays.stream(dev.example.mapi.internal.operation.Scope.values())
                            .filter(scope -> scope.wireName().equalsIgnoreCase(trimmed))
                            .findFirst();
            if (match.isEmpty()) {
                throw new MapiConfigException(
                        "http.scopes contains unknown scope '" + trimmed + "'");
            }
            parsed.add(match.get());
        }
        return java.util.Collections.unmodifiableSet(parsed);
    }
}
